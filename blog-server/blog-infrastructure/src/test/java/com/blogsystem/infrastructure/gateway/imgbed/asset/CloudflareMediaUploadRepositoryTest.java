package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareMediaUploadRepository;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;

import java.io.ByteArrayInputStream;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException.Outcome;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CloudflareMediaUploadRepositoryTest {
    @Test void uploadAdapterImplementsOnlyTheInwardStorageContract() {
        assertEquals("com.blogsystem.infrastructure.gateway.imgbed.asset", CloudflareMediaUploadRepository.class.getPackageName());
        assertTrue(java.util.Arrays.stream(CloudflareMediaUploadRepository.class.getInterfaces())
                .anyMatch(type -> type.getName().equals("com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway")));
    }
    HttpServer server;String base;List<String> requests=new ArrayList<>();List<String> bodies=new ArrayList<>();
    List<String> replies=new ArrayList<>();int responseStatus=200;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",e->{
            requests.add(e.getRequestMethod()+" "+e.getRequestURI());
            assertEquals("Bearer fake-token",e.getRequestHeaders().getFirst("Authorization"));
            assertTrue(e.getRequestHeaders().getFirst("Cache-Control").contains("no-store"));
            bodies.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] b=(replies.isEmpty()?"{}":replies.remove(0)).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(responseStatus,b.length);e.getResponseBody().write(b);e.close();
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    CloudflareMediaUploadRepository repository(){return new CloudflareMediaUploadRepository(MediaUploadProviderResponseParserTest.settings(base,Duration.ofSeconds(30)),new ObjectMapper(),HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());}
    String receipt(){return "[{\"src\":\"/file/"+MediaUploadProviderResponseParserTest.FOLDER+"/confirmed.mp3\"}]";}
    @Test void emitsExactDirectAndChunkForms() {
        var direct=MediaUploadProviderResponseParserTest.intent(1);var chunks=MediaUploadProviderResponseParserTest.intent(16000001);
        replies.addAll(List.of(receipt(),"{\"success\":true,\"uploadId\":\"session_1\"}","{\"success\":true,\"uploadId\":\"session_1\",\"chunkIndex\":1}",receipt()));
        var repo=repository();
        repo.uploadDirect(direct,new MediaUploadContent(){public long size(){return 1;}public String fileName(){return "unsafe.html";}public String mediaType(){return "audio/mpeg";}public InputStream openStream(){return new ByteArrayInputStream(new byte[]{1});}});
        String session=repo.beginChunks(chunks);repo.uploadChunk(chunks,session,1,new byte[]{2});repo.mergeChunks(chunks,session);
        assertEquals(4,requests.size());
        for(String route:requests){assertTrue(route.startsWith("POST /upload?"));assertTrue(route.contains("uploadChannel=telegram"));assertTrue(route.contains("channelName=configured"));assertTrue(route.contains("autoRetry=false"));assertTrue(route.contains("serverCompress=false"));}
        assertTrue(requests.get(0).contains("uploadNameType=origin"));assertTrue(requests.get(0).contains("uploadFolder=blog%2Fmanaged-uploads%2F"));
        assertTrue(requests.get(1).contains("initChunked=true"));assertTrue(requests.get(2).contains("chunked=true"));assertTrue(requests.get(3).contains("merge=true"));
        assertTrue(bodies.get(0).contains("filename=\"00000000-0000-0000-0000-000000000302.mp3\""));
        assertFalse(String.join("",bodies).contains("private-name"));assertFalse(bodies.get(0).contains("unsafe.html"));
        for(int i=1;i<4;i++){assertTrue(bodies.get(i).contains("name=\"totalChunks\"\r\n\r\n2"));assertTrue(bodies.get(i).contains("name=\"originalFileType\"\r\n\r\naudio/mpeg"));}
        assertTrue(bodies.get(2).contains("name=\"chunkIndex\"\r\n\r\n1"));
    }
    @Test void neverRedirectsRetriesOrDeletes() {
        responseStatus=302;replies.add("{}");
        assertEquals(MediaUploadStorageException.Outcome.UNKNOWN,assertThrows(MediaUploadStorageException.class,()->repository().beginChunks(MediaUploadProviderResponseParserTest.intent(16000001))).outcome());
        assertEquals(1,requests.size());assertFalse(requests.get(0).contains("cleanup"));assertFalse(requests.get(0).contains("delete"));
    }
    @Test void timeoutsAndInvalidBodiesAreUnknown() {
        for(String reply:List.of("not-json","{\"success\":true}","{\"success\":false,\"secret\":\"fake-token\"}")){
            replies.add(reply);var error=assertThrows(MediaUploadStorageException.class,()->repository().beginChunks(MediaUploadProviderResponseParserTest.intent(16000001)));
            assertEquals(MediaUploadStorageException.Outcome.UNKNOWN,error.outcome());assertNull(error.getCause());assertFalse(error.toString().contains("fake-token"));
        }
        responseStatus=500;replies.add("{}");assertThrows(MediaUploadStorageException.class,()->repository().beginChunks(MediaUploadProviderResponseParserTest.intent(16000001)));
        assertEquals(4,requests.size());
    }
    String candidate(String name){return "{\"name\":\""+name+"\",\"metadata\":{\"Channel\":\"telegram\",\"ChannelName\":\"configured\",\"File-Mime\":\"audio/mpeg\",\"FileSizeBytes\":1}}";}
    @Test void emptyIndexDoesNotProveFailure() {
        replies.add("{\"totalCount\":0,\"files\":[]}");var result=repository().probe(MediaUploadProviderResponseParserTest.intent(1));assertTrue(result.complete());assertTrue(result.candidates().isEmpty());
        assertEquals(1,requests.size());assertTrue(requests.get(0).contains("recursive=true"));assertTrue(requests.get(0).contains("count=2"));assertFalse(requests.get(0).contains("fileType"));assertFalse(requests.get(0).contains("action="));
    }
    @Test void oneExactCandidateNeedsCompletePagination() {
        replies.add("{\"totalCount\":1,\"files\":["+candidate(MediaUploadProviderResponseParserTest.FOLDER+"/one.mp3")+"]}");assertTrue(repository().probe(MediaUploadProviderResponseParserTest.intent(1)).complete());
        replies.add("{\"totalCount\":2,\"files\":["+candidate(MediaUploadProviderResponseParserTest.FOLDER+"/one.mp3")+"]}");replies.add("{\"totalCount\":2,\"files\":[]}");assertFalse(repository().probe(MediaUploadProviderResponseParserTest.intent(1)).complete());
    }
    @Test void twoDuplicateOrOutOfFolderCandidatesNeverAdopt() {
        String one=candidate(MediaUploadProviderResponseParserTest.FOLDER+"/one.mp3");
        replies.add("{\"totalCount\":2,\"files\":["+one+","+candidate(MediaUploadProviderResponseParserTest.FOLDER+"/two.mp3")+"]}");assertEquals(2,repository().probe(MediaUploadProviderResponseParserTest.intent(1)).candidates().size());
        replies.add("{\"totalCount\":2,\"files\":["+one+","+one+"]}");assertFalse(repository().probe(MediaUploadProviderResponseParserTest.intent(1)).complete());
        replies.add("{\"totalCount\":1,\"files\":["+candidate("other/one.mp3")+"]}");assertFalse(repository().probe(MediaUploadProviderResponseParserTest.intent(1)).complete());
    }
    @Test void candidateBudgetCannotOverflowAcrossPages() {
        replies.add("{\"totalCount\":3,\"files\":["+candidate(MediaUploadProviderResponseParserTest.FOLDER+"/one.mp3")+"]}");
        replies.add("{\"totalCount\":3,\"files\":["+candidate(MediaUploadProviderResponseParserTest.FOLDER+"/two.mp3")+","+candidate(MediaUploadProviderResponseParserTest.FOLDER+"/three.mp3")+"]}");
        assertEquals(2,repository().probe(MediaUploadProviderResponseParserTest.intent(1)).candidates().size());
        assertEquals(2,requests.size());
    }
    @Test void responseAndTimeoutBudgetsAreEnforced() {
        replies.add(" ".repeat(1048577));assertEquals(MediaUploadStorageException.Outcome.UNKNOWN,assertThrows(MediaUploadStorageException.class,()->repository().beginChunks(MediaUploadProviderResponseParserTest.intent(16000001))).outcome());
        HttpClient mock=org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(mock.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        org.mockito.Mockito.when(mock.sendAsync(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class))).thenReturn(new java.util.concurrent.CompletableFuture<>());
        var settings=new com.blogsystem.infrastructure.configuration.asset.MediaUploadProviderSettings(base,"fake-token","telegram","configured","blog",Duration.ofMillis(30),Duration.ofMillis(30),Duration.ofMillis(30),Duration.ofMillis(30));
        assertEquals(MediaUploadStorageException.Outcome.UNKNOWN,assertThrows(MediaUploadStorageException.class,()->new CloudflareMediaUploadRepository(settings,new ObjectMapper(),mock).beginChunks(MediaUploadProviderResponseParserTest.intent(16000001))).outcome());
        assertEquals(1,requests.size());
    }
}

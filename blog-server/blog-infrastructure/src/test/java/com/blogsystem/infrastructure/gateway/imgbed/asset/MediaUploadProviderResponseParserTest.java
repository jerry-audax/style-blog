package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.MediaUploadProviderResponseParser;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Intent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import com.blogsystem.infrastructure.configuration.asset.MediaUploadProviderSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadProviderResponseParserTest {
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000301");
    static final String FOLDER="blog/managed-uploads/"+ID;
    final ObjectMapper json=new ObjectMapper();
    final MediaUploadProviderResponseParser parser=new MediaUploadProviderResponseParser(settings("https://img.example",Duration.ofSeconds(30)),json);
    static MediaUploadProviderSettings settings(String base,Duration direct){return new MediaUploadProviderSettings(base,"fake-token","telegram","configured","blog",direct,Duration.ofSeconds(30),Duration.ofSeconds(60),Duration.ofSeconds(120));}
    static MediaUploadOperation.Intent intent(long size){
        var file=new MediaUploadFile("private-name.mp3","audio/mpeg",size,"a".repeat(64));
        var request=new MediaUploadRequest(ID,MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.AUDIO,0L,file,null);
        return MediaUploadPolicy.ready(1,request,new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.AUDIO,0L,null,null),"cloudflare-imgbed",new MediaObjectKey("blog"),UUID.fromString("00000000-0000-0000-0000-000000000302"),Instant.EPOCH).intent();
    }
    @Test void srcEncodingCannotEscapeOperationFolder() throws Exception {
        for(String src:List.of("/file/"+FOLDER+"/%E4%B8%AD%E6%96%87%25+.mp3","https://img.example/file/"+FOLDER+"/%E4%B8%AD%E6%96%87%25+.mp3"))
            assertEquals(FOLDER+"/中文%+.mp3",parser.receipt(json.readTree("[{\"src\":\""+src+"\"}]"),intent(1)).objectKey().value());
        for(String suffix:List.of("%2fother.mp3","%5cother.mp3","%2e%2e/x.mp3","%c3%28.mp3","bad%.mp3","a.mp3?q=1","a.mp3#f"))
            assertThrows(MediaUploadStorageException.class,()->parser.receipt(json.readTree("[{\"src\":\"/file/"+FOLDER+"/"+suffix+"\"}]"),intent(1)),suffix);
        for(String body:List.of("[]","[{\"src\":\"https://evil.example/file/"+FOLDER+"/x.mp3\"}]","[{\"src\":\"/file/other/x.mp3\"}]","[{\"src\":\"/file/"+FOLDER+"/a\"},{\"src\":\"/file/"+FOLDER+"/b\"}]"))
            assertThrows(MediaUploadStorageException.class,()->parser.receipt(json.readTree(body),intent(1)));
    }
    @Test void matchesChunkAcknowledgement() throws Exception {
        assertEquals("session_1",parser.initialized(json.readTree("{\"success\":true,\"uploadId\":\"session_1\"}")));
        assertDoesNotThrow(()->parser.chunk(json.readTree("{\"success\":true,\"uploadId\":\"session_1\",\"chunkIndex\":0}"),"session_1",0));
        for(String body:List.of("{\"success\":true,\"uploadId\":\"other\",\"chunkIndex\":0}","{\"success\":true,\"uploadId\":\"session_1\",\"chunkIndex\":1}","{\"success\":true,\"uploadId\":\"session_1\",\"chunkIndex\":\"0\"}"))
            assertThrows(MediaUploadStorageException.class,()->parser.chunk(json.readTree(body),"session_1",0));
    }
    @Test void unknownUnitsChannelOrTypeStayUnknown() throws Exception {
        String valid="{\"name\":\""+FOLDER+"/a.mp3\",\"metadata\":{\"Channel\":\"TelegramNew\",\"ChannelName\":\"configured\",\"FileType\":\"audio/mpeg\",\"File-Size\":\"1\"}}";
        assertEquals(1,parser.page(json.readTree("{\"totalCount\":1,\"files\":["+valid+"]}"),intent(1)).candidates().size());
        for(String invalid:List.of(valid.replace("\"1\"","\"1 MB\""),valid.replace("\"1\"","1.0"),valid.replace("\"1\"","true"),valid.replace("configured","another"),valid.replace("audio/mpeg","image/png"),valid.replace("TelegramNew","s3"),valid.replace("\"ChannelName\":\"configured\",","")))
            assertThrows(MediaUploadStorageException.class,()->parser.page(json.readTree("{\"totalCount\":1,\"files\":["+invalid+"]}"),intent(1)));
    }
}

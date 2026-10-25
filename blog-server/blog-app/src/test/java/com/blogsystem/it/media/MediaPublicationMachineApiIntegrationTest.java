package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.blogsystem.application.asset.publication.service.MediaPublicationMachineApplicationService;
import com.blogsystem.infrastructure.security.publication.PublicationMachineAuthenticator;
import com.blogsystem.trigger.http.asset.MediaPublicationMachineController;
import com.blogsystem.trigger.http.asset.MediaPublicationMachineExceptionHandler;
import com.blogsystem.trigger.interceptor.PublicationMachineInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MediaPublicationMachineApiIntegrationTest {
    static final String BASE = "/internal/media-publications";
    static final String TOKEN = "isolated-publication-machine-test-key-1234567890";
    static final String BODY = "{\"protocolVersion\":1,\"releaseId\":\"release-test\",\"sourceDigest\":\"" + "a".repeat(64)
            + "\",\"links\":[\"/file/blog/covers/a.png\"],\"contents\":[],\"htmlContents\":[]}";

    MockMvc mvc(MediaPublicationPinWorkflowFixture f) throws Exception {
        f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/a.png' WHERE id=?", MediaPublicationManifestIntegrationTest.COVER);
        var service = new MediaPublicationMachineApplicationService(f.service, MediaPublicationManifestIntegrationTest.resolver(f), "local", "hexo");
        return MockMvcBuilders.standaloneSetup(new MediaPublicationMachineController(service))
                .setControllerAdvice(new MediaPublicationMachineExceptionHandler())
                .addInterceptors(new PublicationMachineInterceptor(new PublicationMachineAuthenticator(() -> TOKEN))).build();
    }

    @Test void machineCredentialIsRequiredAndOriginIsRejectedBeforeAnyPinWrite() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            for (var route : java.util.List.of(post(BASE + "/prepare").content(BODY).contentType("application/json"),
                    get(BASE + "/release-test"), post(BASE + "/release-test/release").content("{}").contentType("application/json"),
                    post(BASE + "/release-test/verify").content("{}").contentType("application/json"))) {
                mvc.perform(route).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
                mvc.perform(route.header("Authorization", "owner-session-is-not-a-machine-key")).andExpect(status().isUnauthorized());
                mvc.perform(route.header("X-Publication-Key", "wrong")).andExpect(status().isUnauthorized());
            }
            for (String origin : java.util.List.of("https://blog.example", ""))
                mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json")).header("Origin", origin)).andExpect(status().isForbidden());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void realOwnerSessionAndMachineKeyCannotSubstituteForEachOther() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture(); var owner = new com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport()) {
            var adminService = org.mockito.Mockito.mock(com.blogsystem.application.content.service.SitePublicationApplicationService.class);
            org.mockito.Mockito.when(adminService.status()).thenReturn(com.blogsystem.domain.content.query.SitePublicationView.unavailable());
            var admin = owner.build(new Object[]{new com.blogsystem.trigger.http.content.SitePublicationController(adminService)},
                    new com.blogsystem.trigger.http.support.GlobalExceptionHandler());
            String session = owner.token(1);
            admin.perform(get("/api/admin/publication/status").header("Authorization", session)).andExpect(status().isOk());
            admin.perform(get("/api/admin/publication/status").header("X-Publication-Key", TOKEN)).andExpect(status().isUnauthorized());
            mvc(f).perform(post(BASE + "/prepare").header("Authorization", session).content(BODY).contentType("application/json"))
                    .andExpect(status().isUnauthorized());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void prepareVerifyAndReleaseAreReplayableWithoutLeakingObjectLocations() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            var response = mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.environmentId").value("local"))
                    .andExpect(jsonPath("$.data.publisherId").value("hexo")).andExpect(jsonPath("$.data.version").value("0"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("blog/covers")); assertFalse(response.contains("objectKey"));
            String digest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("manifestDigest").asText();
            mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json"))).andExpect(status().isOk());
            mvc.perform(auth(get(BASE + "/release-test"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("HELD"));
            String output = "{\"protocolVersion\":1,\"manifestDigest\":\"" + digest + "\",\"sourceDigest\":\"" + "b".repeat(64)
                    + "\",\"links\":[\"/file/blog/covers/a.png\"],\"contents\":[],\"htmlContents\":[]}";
            mvc.perform(auth(post(BASE + "/release-test/verify").content(output).contentType("application/json")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.verified").value(true));
            String release = "{\"protocolVersion\":1,\"manifestDigest\":\"" + digest + "\",\"expectedVersion\":\"0\",\"retirementConfirmed\":true}";
            for (int n = 0; n < 2; n++) mvc.perform(auth(post(BASE + "/release-test/release").content(release).contentType("application/json")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RELEASED")).andExpect(jsonPath("$.data.version").value("1"));
            mvc.perform(auth(post(BASE + "/release-test/verify").content(output).contentType("application/json"))).andExpect(status().isConflict());
            mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json"))).andExpect(status().isConflict());
            assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void malformedVersionUnknownFieldsDuplicateKeysAndTrailingJsonCannotCreateAPin() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            for (String body : java.util.List.of(BODY.replace("\"protocolVersion\":1", "\"protocolVersion\":1.5"),
                    BODY.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1"), BODY + "{}",
                    BODY.replace("\"releaseId\"", "\"environmentId\":\"production\",\"releaseId\""),
                    BODY.replace("\"releaseId\"", "\"publisherId\":\"other\",\"releaseId\""))) {
                mvc.perform(auth(post(BASE + "/prepare").content(body).contentType("application/json"))).andExpect(status().isBadRequest());
            }
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void chunkedBodyLimitIsEnforcedByActualBytesNotOnlyContentLength() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            byte[] oversized = new byte[4 * 1024 * 1024 + 1]; java.util.Arrays.fill(oversized, (byte) ' ');
            mvc.perform(auth(post(BASE + "/prepare").content(oversized).contentType("application/json")))
                    .andExpect(status().isPayloadTooLarge());
            mvc.perform(auth(post(BASE + "/prepare").content(oversized).contentType("application/json")).with(request -> {
                var chunked = new MockHttpServletRequest(request.getServletContext(), request.getMethod(), request.getRequestURI()) {
                    @Override public int getContentLength() { return -1; }
                    @Override public long getContentLengthLong() { return -1; }
                };
                chunked.setContent(request.getContentAsByteArray()); chunked.setContentType("application/json");
                chunked.addHeader("X-Publication-Key", TOKEN); chunked.addHeader("Transfer-Encoding", "chunked");
                return chunked;
            })).andExpect(status().isPayloadTooLarge());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void unsupportedEncodingAndMediaTypeAreRejectedWithoutDatabaseWrite() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("text/plain"))).andExpect(status().isUnsupportedMediaType());
            mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json")).header("Content-Encoding", "gzip"))
                    .andExpect(status().isUnsupportedMediaType());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void outputCannotExpandTheHeldManifestOrAcceptAnUnknownManagedFile() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            String response = mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String digest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("manifestDigest").asText();
            String audioKey = f.jdbc.queryForObject("SELECT object_key FROM media_asset WHERE id=?", String.class, java.util.UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO));
            for (String extra : java.util.List.of("/file/" + audioKey, "/file/blog/covers/missing.png")) {
                String output = "{\"protocolVersion\":1,\"manifestDigest\":\"" + digest + "\",\"sourceDigest\":\"" + "a".repeat(64)
                        + "\",\"links\":[\"" + extra + "\"],\"contents\":[],\"htmlContents\":[]}";
                mvc.perform(auth(post(BASE + "/release-test/verify").content(output).contentType("application/json"))).andExpect(status().isConflict());
            }
            assertEquals(1, f.service.get(new com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey(
                    "local", "hexo", "release-test")).assetIds().size());
        }
    }

    @Test void releaseRequiresStrictVersionDigestAndExplicitRetirementConfirmation() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            String response = mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String digest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("manifestDigest").asText();
            for (String version : java.util.List.of("0", "\"01\"", "\"-1\"", "\"9223372036854775808\"")) {
                String release = "{\"protocolVersion\":1,\"manifestDigest\":\"" + digest + "\",\"expectedVersion\":" + version + ",\"retirementConfirmed\":true}";
                mvc.perform(auth(post(BASE + "/release-test/release").content(release).contentType("application/json"))).andExpect(status().isBadRequest());
            }
            String release = "{\"protocolVersion\":1,\"manifestDigest\":\"" + digest + "\",\"expectedVersion\":\"0\",\"retirementConfirmed\":true}";
            mvc.perform(auth(post(BASE + "/release-test/release").content(release.replace("true", "false")).contentType("application/json"))).andExpect(status().isBadRequest());
            mvc.perform(auth(post(BASE + "/release-test/release").content(release.replace(digest, "f".repeat(64))).contentType("application/json"))).andExpect(status().isConflict());
            mvc.perform(auth(post(BASE + "/release-test/release").content(release.replace("\"expectedVersion\":\"0\"", "\"expectedVersion\":\"1\"")).contentType("application/json"))).andExpect(status().isConflict());
            f.jdbc.update("UPDATE media_publication_pin SET version=9007199254740993");
            mvc.perform(auth(post(BASE + "/release-test/release").content(release.replace("\"expectedVersion\":\"0\"", "\"expectedVersion\":\"9007199254740993\"")).contentType("application/json")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("9007199254740994"));
            mvc.perform(auth(get(BASE + "/missing"))).andExpect(status().isNotFound());
        }
    }

    @Test void missingPinTableIsUnavailableNotSuccessOrRawSql() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f); f.schema.execute("ALTER TABLE media_publication_pin RENAME TO unavailable_pins");
            String response = mvc.perform(auth(get(BASE + "/release-test"))).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("SELECT")); assertFalse(response.contains("media_publication_pin")); assertFalse(response.contains(TOKEN));
        }
    }

    @Test void explicitEmptyManifestIsDurableButMissingCollectionsAreNotAnEmptyPublication() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            String empty = BODY.replace("[\"/file/blog/covers/a.png\"]", "[]");
            mvc.perform(auth(post(BASE + "/prepare").content(empty.replace("\"contents\":[],", "")).contentType("application/json")))
                    .andExpect(status().isBadRequest());
            mvc.perform(auth(post(BASE + "/prepare").content(empty).contentType("application/json")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.assetIds").isEmpty()).andExpect(jsonPath("$.data.status").value("HELD"));
            assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(30)
    void concurrentPrepareRequestsShareOneDurablePin() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f); var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<Integer> call = () -> {
                    assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    return mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json"))).andReturn().getResponse().getStatus();
                };
                var first = pool.submit(call); var second = pool.submit(call); start.countDown();
                assertEquals(200, first.get(15, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(200, second.get(15, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
        }
    }

    @Test void semanticAndScanLimitsReturn413BeforeAnyPinIsWritten() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f); var json = new com.fasterxml.jackson.databind.ObjectMapper();
            String firstHalf = java.util.stream.IntStream.range(0, 5000).mapToObj(i -> "<img src='/file/blog/" + i + ".png'>").collect(java.util.stream.Collectors.joining());
            String secondHalf = java.util.stream.IntStream.range(5000, 10001).mapToObj(i -> "<img src='/file/blog/" + i + ".png'>").collect(java.util.stream.Collectors.joining());
            for (var fields : java.util.List.of(
                    java.util.Map.of("links", java.util.List.of("中".repeat(349526)), "contents", java.util.List.of(), "htmlContents", java.util.List.of()),
                    java.util.Map.of("links", java.util.Collections.nCopies(10001, ""), "contents", java.util.List.of(), "htmlContents", java.util.List.of()),
                    java.util.Map.of("links", java.util.List.of(), "contents", java.util.List.of(), "htmlContents", java.util.List.of("<img src='/file/blog/x.png'>".repeat(10001))),
                    java.util.Map.of("links", java.util.List.of(), "contents", java.util.List.of(), "htmlContents", java.util.List.of(firstHalf, secondHalf)))) {
                var body = new java.util.HashMap<String, Object>(fields);
                body.put("protocolVersion", 1); body.put("releaseId", "release-test"); body.put("sourceDigest", "a".repeat(64));
                byte[] bytes = json.writeValueAsBytes(body); assertTrue(bytes.length < 4 * 1024 * 1024);
                mvc.perform(auth(post(BASE + "/prepare").content(bytes).contentType("application/json")))
                        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
            }
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void wildcardTypesCannotUseTheConcreteJsonGate() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            for (String type : java.util.List.of("application/*", "*/*", "application/*+json"))
                mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType(type))).andExpect(status().isUnsupportedMediaType());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            mvc.perform(auth(post(BASE + "/prepare").content(BODY).contentType("application/json;charset=UTF-8"))).andExpect(status().isOk());
        }
    }

    @Test void anyOriginIs403EvenWhenTheCredentialIsAbsentWrongOrDuplicated() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            for (String origin : java.util.List.of("", "https://blog.example")) {
                mvc.perform(post(BASE + "/prepare").header("Origin", origin).content("not json").contentType("text/plain"))
                        .andExpect(status().isForbidden());
                mvc.perform(post(BASE + "/prepare").header("Origin", origin).header("X-Publication-Key", "wrong").content(BODY).contentType("application/json"))
                        .andExpect(status().isForbidden());
                mvc.perform(post(BASE + "/prepare").header("Origin", origin).header("X-Publication-Key", TOKEN, TOKEN).content(BODY).contentType("application/json"))
                        .andExpect(status().isForbidden());
            }
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) { return builder.header("X-Publication-Key", TOKEN); }

    @Test void cssStringImagesCannotApproveUnpinnedOrUnknownResourcesOrExpandThePin() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var mvc = mvc(f);
            String response = mvc.perform(auth(post(BASE + "/prepare").content(BODY.replace("[\"/file/blog/covers/a.png\"]", "[]"))
                    .contentType("application/json"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String digest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("manifestDigest").asText();
            for (String file : java.util.List.of("/file/blog/covers/a.png", "/file/blog/covers/unknown.png")) {
                for (String html : java.util.List.of("<div style=\"background-image:image-set('" + file + "' 1x)\"></div>",
                        "<style>.x{background-image:-webkit-image-set('" + file + "' 1x)}</style>")) {
                    String output = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                            "protocolVersion", 1, "manifestDigest", digest, "sourceDigest", "a".repeat(64),
                            "links", java.util.List.of(), "contents", java.util.List.of(), "htmlContents", java.util.List.of(html)));
                    mvc.perform(auth(post(BASE + "/release-test/verify").content(output).contentType("application/json")))
                            .andExpect(status().isConflict());
                }
            }
            var held = f.service.get(new com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey("local", "hexo", "release-test"));
            assertTrue(held.assetIds().isEmpty()); assertEquals(digest, held.manifestDigest());
            assertEquals("HELD", held.status().name()); assertEquals(0, held.version());
        }
    }

}

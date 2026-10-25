package com.blogsystem.it.media;

import com.blogsystem.app.config.asset.ImageMetadataConfiguration;
import com.blogsystem.application.asset.image.result.ImageCatalogCapabilities;
import com.blogsystem.application.asset.image.service.ImageApplicationService;
import com.blogsystem.application.asset.image.service.ImageCatalogQueryApplicationService;
import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.domain.asset.image.gateway.ImageStorageGateway;
import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.domain.asset.image.model.valobj.*;
import com.blogsystem.domain.asset.image.repository.ImageMetadataRepository;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.valobj.*;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisImageMetadataRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.DeletionGuardedImageStorageGateway;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.ImageMetadataMapper;
import com.blogsystem.trigger.http.asset.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaImageCatalogApiIntegrationTest {
    private static final String UPLOAD = "/api/admin/media/uploads", IMAGES = "/api/admin/media/images";

    @Test void ownerImageUploadReturnsOnlyCommittedImageAndStrictNewDecimalVersion() throws Exception {
        try (var f = new MediaUploadWorkflowFixture(); var auth = new CatalogMvcTestSupport()) {
            var mvc = uploadMvc(f, auth); String token = auth.token(1);
            for (var purpose : ImagePurpose.values()) {
                var request = MediaImageUploadWorkflowIntegrationTest.request(purpose, MediaImageUploadWorkflowIntegrationTest.PNG);
                String json = new ObjectMapper().writeValueAsString(fields(request));
                mvc.perform(post(UPLOAD).header("Authorization", token).contentType("application/json").content(json)).andExpect(status().isCreated());
                String body = mvc.perform(multipart(UPLOAD + "/" + request.operationId() + "/content").file(new MockMultipartFile("file",
                                request.file().fileName(), "image/png", MediaImageUploadWorkflowIntegrationTest.PNG)).with(r -> { r.setMethod("PUT"); return r; })
                                .header("Authorization", token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.result.targetType").value("IMAGE_CREATE"))
                        .andExpect(jsonPath("$.data.result.image.assetVersion").value("0"))
                        .andExpect(jsonPath("$.data.result.image.fileName").value("图片+100%.png"))
                        .andExpect(jsonPath("$.data.result.music").doesNotExist()).andExpect(jsonPath("$.data.result.video").doesNotExist())
                        .andExpect(jsonPath("$.data.result.image.provider").doesNotExist()).andReturn().getResponse().getContentAsString();
                assertFalse(body.contains("objectKey"));
            }
            assertEquals(List.of("direct", "direct", "direct"), f.storage.calls);
        }
    }

    @Test void badImagePurposeMixedSeedAndOversizeAreRejectedBeforeProvider() throws Exception {
        try (var f = new MediaUploadWorkflowFixture(); var auth = new CatalogMvcTestSupport()) {
            var mvc = uploadMvc(f, auth); String token = auth.token(1);
            var request = MediaImageUploadWorkflowIntegrationTest.request(ImagePurpose.AVATAR, MediaImageUploadWorkflowIntegrationTest.PNG);
            for (String field : List.of("title", "targetId", "expectedVersion", "imagePurpose")) {
                var values = fields(request);
                values.put(field, field.equals("expectedVersion") ? 0 : "unexpected");
                mvc.perform(post(UPLOAD).header("Authorization", token).contentType("application/json").content(new ObjectMapper().writeValueAsString(values)))
                        .andExpect(status().isUnprocessableEntity());
            }
            var tooLarge = fields(request); tooLarge.put("fileSize", 2097153);
            mvc.perform(post(UPLOAD).header("Authorization", token).contentType("application/json").content(new ObjectMapper().writeValueAsString(tooLarge)))
                    .andExpect(status().isPayloadTooLarge());
            assertTrue(f.storage.calls.isEmpty());
        }
    }

    @Test void databaseImageQueryIsOwnerOnlyNoStoreAndWorksWithoutUploadLedger() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema(); var auth = new CatalogMvcTestSupport()) {
            schema.executeDdl();
            try (var session = MediaImageMetadataRepositoryIntegrationTest.session(schema)) {
                var repository = new MybatisImageMetadataRepository(session.getMapper(ImageMetadataMapper.class), new MediaAssetConverter());
                var query = new ImageCatalogQueryApplicationService(repository, (p,k) -> "https://example.test/file/" + k.value(), "cloudflare_imgbed", new MediaObjectKey("blog"));
                var mvc = auth.build(new Object[]{new ImageCatalogController(query, auth.identity())}, new ImageExceptionHandler());
                mvc.perform(get(IMAGES)).andExpect(status().isUnauthorized());
                mvc.perform(get(IMAGES).header("Authorization", auth.token(2))).andExpect(status().isForbidden());
                mvc.perform(get(IMAGES).header("Authorization", auth.token(1))).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.total").value("0"));
                mvc.perform(get(IMAGES).param("count", "51").header("Authorization", auth.token(1))).andExpect(status().isBadRequest());
                session.commit(); schema.execute("ALTER TABLE media_asset RENAME TO unavailable_assets");
                mvc.perform(get(IMAGES).header("Authorization", auth.token(1))).andExpect(status().isServiceUnavailable());
            }
        }
    }

    @Test void queryProjectionPreservesNullAndZeroAndNeverExposesStorageIdentity() throws Exception {
        try (var auth = new CatalogMvcTestSupport()) {
            var rows = List.of(MediaAsset.register(UUID.randomUUID(), "cloudflare_imgbed", new MediaObjectKey("blog/images/中文.png"),
                            MediaKind.IMAGE, new MediaMetadata("中文.png", null, null, null, null, null, null), Instant.EPOCH),
                    MediaAsset.register(UUID.randomUUID(), "cloudflare_imgbed", new MediaObjectKey("blog/images/zero.png"),
                            MediaKind.IMAGE, new MediaMetadata("zero.png", "image/png", 0L, null, null, null, null), Instant.EPOCH));
            ImageMetadataRepository repo = q -> new MediaAssetPage(rows, 9007199254740993L, q.offset(), q.limit());
            var query = new ImageCatalogQueryApplicationService(repo, (p,k) -> "https://example.test/file/" + k.value(), "cloudflare_imgbed", new MediaObjectKey("blog"));
            auth.build(new Object[]{new ImageCatalogController(query, auth.identity())}, new ImageExceptionHandler())
                    .perform(get(IMAGES).header("Authorization", auth.token(1))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.images[0].fileSize").doesNotExist())
                    .andExpect(jsonPath("$.data.images[1].fileSize").value(0))
                    .andExpect(jsonPath("$.data.images[0].objectKey").doesNotExist());
        }
    }

    @Test void postgresOldImageRoutesCannotBypassLedgerWhileExplicitLegacyKeepsWorking() throws Exception {
        try (var auth = new CatalogMvcTestSupport()) {
            var provider = mock(ImageStorageGateway.class);
            when(provider.upload(any(), eq(1L))).thenReturn(new StoredImage("blog/a.png", "https://example.test/file/blog/a.png", "image/png", 12));
            when(provider.list(0,20,"")).thenReturn(new ImagePage(List.of(),0));
            for (boolean legacy : List.of(false, true)) {
                var mvc = auth.build(new Object[]{new ImageController(new ImageApplicationService(new DeletionGuardedImageStorageGateway(provider, legacy)))}, new ImageExceptionHandler());
                for (String purpose : List.of("article", "cover", "avatar"))
                    mvc.perform(multipart("/api/images/" + purpose).file(new MockMultipartFile("file", "a.png", "image/png", MediaImageUploadWorkflowIntegrationTest.PNG))
                            .header("Authorization", auth.token(1))).andExpect(status().is(legacy ? 200 : 503));
                mvc.perform(get("/api/images").header("Authorization", auth.token(1))).andExpect(status().is(legacy ? 200 : 503));
                mvc.perform(delete("/api/images").header("Authorization", auth.token(1)).contentType("application/json").content("{\"path\":\"blog/a.png\"}"))
                        .andExpect(status().is(legacy ? 200 : 503));
                if (!legacy) verifyNoInteractions(provider);
            }
        }
    }

    @Test void imageCapabilitiesAreExplicitAndDeletionDecoratorCannotEnableImageDelete() {
        var images = ImageCatalogCapabilities.postgres(false);
        var capabilities = new MediaCatalogCapabilities(true,true,false,false,false,false).withImages(images).withTrackedDeletion(true);
        assertEquals(images, capabilities.images()); assertFalse(capabilities.images().deleteEnabled());
        assertNull(images.uploadProtocol()); assertFalse(images.uploadEnabled());
        assertEquals("tracked-image-v1", ImageCatalogCapabilities.postgres(true).uploadProtocol());
        assertEquals("legacy", ImageCatalogCapabilities.legacy().uploadProtocol());
        new ApplicationContextRunner().withUserConfiguration(ImageMetadataConfiguration.class)
                .withPropertyValues("media.catalog.storage=postgres", "media.catalog.uploads.enabled=false")
                .withBean(ImageMetadataRepository.class, () -> q -> new MediaAssetPage(List.of(),0,q.offset(),q.limit()))
                .withBean(MediaPublicUrlGateway.class, () -> (p,k) -> "https://example.test/file/" + k.value())
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure()); assertNotNull(ctx.getBean(ImageCatalogQueryApplicationService.class));
                    assertFalse(ctx.containsBean("mediaUploadSchemaVerifier")); assertFalse(ctx.containsBean("mediaUploadStorageRepository"));
                });
    }

    private static MockMvc uploadMvc(MediaUploadWorkflowFixture f, CatalogMvcTestSupport auth) {
        return auth.build(new Object[]{new MediaUploadController(MediaImageUploadWorkflowIntegrationTest.service(f), new MediaUploadViewProjector(f.urls), auth.identity())}, new MediaUploadExceptionHandler());
    }
    static Map<String,Object> fields(com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest r) {
        return new HashMap<>(Map.of("operationId",r.operationId().toString(),"targetType","IMAGE_CREATE","slot",r.slot().name(),
                "imagePurpose",r.image().purpose().name(),"fileName",r.file().fileName(),"fileSize",r.file().size(),"mediaType",r.file().mediaType(),"sourceSha256",r.file().sourceSha256()));
    }
}

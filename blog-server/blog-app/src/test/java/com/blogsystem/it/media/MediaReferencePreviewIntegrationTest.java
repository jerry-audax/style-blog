package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.app.config.asset.MediaReferenceConfiguration;
import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceQueryException;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionOperationRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackDeletionRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoDeletionRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionOperationMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaPublicationPinMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaReferenceMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.trigger.http.asset.MediaDeletionController;
import com.blogsystem.trigger.http.asset.MediaDeletionExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaReferencePreviewIntegrationTest {
    private static final String URL = "/api/admin/media/deletions/preview";
    private static final String COVER = "{\"target\":{\"kind\":\"MUSIC_MEDIA\",\"id\":\"  Existing-视频-ID  \",\"slot\":\"COVER\"}}";
    private final ObjectMapper json = new ObjectMapper();

    private static MediaDeletionApplicationService service(MediaReferenceWorkflowFixture fixture, MediaReferenceRepository references) throws Exception {
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setDefaultStatementTimeout(10);
        config.addMapper(MediaDeletionOperationMapper.class); config.addMapper(MusicTrackMapper.class); config.addMapper(SurpriseVideoMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(fixture.jdbc.getDataSource()); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var operations = new MybatisMediaDeletionOperationRepository(session.getMapper(MediaDeletionOperationMapper.class), new MediaDeletionConverter(new MediaAssetConverter()));
        return new MediaDeletionApplicationService(fixture.base.assets, operations,
                new MybatisMusicTrackDeletionRepository(session.getMapper(MusicTrackMapper.class)),
                new MybatisSurpriseVideoDeletionRepository(session.getMapper(SurpriseVideoMapper.class)), fixture.base.tx, Clock.systemUTC(), "local", true, () -> true, references);
    }
    private static MockMvc mvc(MediaDeletionApplicationService service, CatalogMvcTestSupport auth) {
        return auth.build(new Object[]{new MediaDeletionController(service, auth.identity())}, new MediaDeletionExceptionHandler());
    }

    @Test void actualDraftDisabledMusicAndAvatarUsagesReachAnAuthenticatedSafePreview() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture(); var auth = new CatalogMvcTestSupport()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); String imageUrl = fixture.url(image);
            fixture.jdbc.update("UPDATE music_track SET enabled=false"); fixture.article(1, imageUrl, "<img src='" + imageUrl + "'>", null); fixture.owner(7, imageUrl);
            var response = mvc(service(fixture, fixture.references), auth).perform(post(URL).header("Authorization", auth.token(1)).contentType("application/json").content(COVER))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.candidates[0].usageCount").value("4"))
                    .andExpect(jsonPath("$.data.referenceCoverageComplete").value(false))
                    .andExpect(jsonPath("$.data.candidates[0].sourceFailures[0]").value("PUBLICATION")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var candidate = json.readTree(response).at("/data/candidates/0");
            assertTrue(candidate.get("usageCount").isTextual()); assertEquals(4, candidate.get("usages").size());
            assertEquals("MUSIC", candidate.at("/usages/0/source").asText());
            assertEquals("文章封面", candidate.at("/usages/1/label").asText());
            assertEquals("账号头像", candidate.at("/usages/3/label").asText());
            for (String privateValue : Set.of("https://", "blog/images", "contentMd", "contentHtml", "test-only-not-a-real-secret", "private title", "objectKey")) assertFalse(response.contains(privateValue));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM media_delete_operation", Integer.class));
            assertEquals(image, fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE));
        }
    }

    @Test void viewCapsFiftyUsagesButKeepsTheExactUntruncatedTotal() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture(); var auth = new CatalogMvcTestSupport()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE);
            for (int i = 1; i <= 51; i++) fixture.article(i, fixture.url(image), null, null);
            var response = mvc(service(fixture, fixture.references), auth).perform(post(URL).header("Authorization", auth.token(1)).contentType("application/json").content(COVER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates[0].usageCount").value("52")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals(50, json.readTree(response).at("/data/candidates/0/usages").size());
        }
    }

    @Test void zeroCandidatesAndMissingPublicationTableNeverTurnIntoCompleteEvidence() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture(); var auth = new CatalogMvcTestSupport()) {
            fixture.jdbc.update("INSERT INTO music_track(id,title,artist,enabled,sort_order,version,created_at,updated_at) VALUES ('empty-song','empty','',true,0,0,now(),now())");
            fixture.base.schema.execute("DROP TABLE media_publication_pin");
            var mvc = mvc(service(fixture, fixture.references), auth); String token = auth.token(1);
            mvc.perform(post(URL).header("Authorization", token).contentType("application/json").content("{\"target\":{\"kind\":\"MUSIC_TRACK\",\"id\":\"empty-song\"}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates.length()").value(0)).andExpect(jsonPath("$.data.referenceCoverageComplete").value(false));
            mvc.perform(post(URL).header("Authorization", token).contentType("application/json").content(COVER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates[0].usageCount").value("1"))
                    .andExpect(jsonPath("$.data.candidates[0].sourceFailures[0]").value("PUBLICATION"));
        }
    }

    @Test void authenticationAndSanitizedUnavailableErrorsStillGuardTheReadOnlyEndpoint() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture(); var auth = new CatalogMvcTestSupport()) {
            var mvc = mvc(service(fixture, asset -> { throw new MediaReferenceQueryException(MediaReferenceQueryException.Reason.UNAVAILABLE); }), auth);
            mvc.perform(post(URL).contentType("application/json").content(COVER)).andExpect(status().isUnauthorized());
            mvc.perform(post(URL).header("Authorization", auth.token(2)).contentType("application/json").content(COVER)).andExpect(status().isForbidden());
            var response = mvc.perform(post(URL).header("Authorization", auth.token(1)).contentType("application/json").content(COVER))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(response.contains("SELECT")); assertFalse(response.contains("https://"));
        }
    }

    @Test void postgresCompositionUsesTheActualConfiguredOriginAndDoesNotActivateInLegacyMode() {
        var runner = new ApplicationContextRunner().withUserConfiguration(MediaReferenceConfiguration.class)
                .withBean(MediaReferenceMapper.class, () -> mock(MediaReferenceMapper.class))
                .withBean(MediaPublicationPinMapper.class, () -> mock(MediaPublicationPinMapper.class))
                .withPropertyValues("imgbed.base-url=https://img.example", "media.catalog.references.allowed-origins[0]=https://old.example");
        runner.withPropertyValues("media.catalog.storage=postgres").run(context -> {
            assertNullStartup(context.getStartupFailure());
            assertNotNull(context.getBean(MediaReferenceRepository.class)); assertNotNull(context.getBean(MediaPublicationPinRepository.class));
            var links = context.getBean(MediaReferenceLinkGateway.class);
            assertTrue(links.parseLink("https://old.example/file/blog/x.png").complete());
            assertEquals(1, links.parseLink("https://old.example/file/blog/x.png").objectKeys().size());
        });
        runner.withPropertyValues("media.catalog.storage=legacy-json").run(context -> assertFalse(context.containsBean("mediaReferenceRepository")));
    }

    private static void assertNullStartup(Throwable failure) {
        if (failure != null) throw new AssertionError("Reference configuration failed", failure);
    }
}

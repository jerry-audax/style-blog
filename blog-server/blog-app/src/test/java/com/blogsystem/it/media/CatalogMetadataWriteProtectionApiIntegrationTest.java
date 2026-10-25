package com.blogsystem.it.media;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.application.music.service.MusicApplicationService;
import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;
import com.blogsystem.application.surprise.service.PersistentSurpriseVideoApplicationService;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.trigger.http.music.MusicAdminController;
import com.blogsystem.trigger.http.music.MusicExceptionHandler;
import com.blogsystem.trigger.http.surprise.SurpriseAdminController;
import com.blogsystem.trigger.http.surprise.SurpriseExceptionHandler;
import java.net.URI;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.blogsystem.it.media.CatalogMetadataWriteProtectionFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

class CatalogMetadataWriteProtectionApiIntegrationTest {
    private static final String MUSIC_JSON =
            "{\"title\":\"新歌名\",\"artist\":\"新歌手\",\"sourceId\":null,\"enabled\":false,\"sortOrder\":9,\"expectedVersion\":7}";
    private static final String VIDEO_JSON =
            "{\"title\":\"新视频\",\"enabled\":false,\"sortOrder\":9,\"expectedVersion\":7}";

    @Test void ownerCanEditMusicAndVideoMetadataWithoutChangingResourceBindings() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var auth = new CatalogMvcTestSupport()) {
            var mvc = mvc(f, auth);
            var token = auth.token(1);
            mvc.perform(music(token, MUSIC_JSON)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.version").value(8));
            mvc.perform(video(token, VIDEO_JSON)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.version").value(8));
            mvc.perform(music(token, MUSIC_JSON)).andExpect(status().isConflict());
            mvc.perform(video(token, VIDEO_JSON)).andExpect(status().isConflict());

            var music = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            assertEquals(AUDIO, music.track().audioAssetId());
            assertEquals(COVER, music.track().coverAssetId());
            assertEquals(LYRICS, music.track().lyricsAssetId());
            assertEquals(8, music.version());
            var video = f.videos.findSnapshotById("video-1").orElseThrow();
            assertEquals(VIDEO, video.video().videoAssetId());
            assertEquals(8, video.version());
        }
    }

    @Test void inactiveRetainedAssetsReturnConflictForMusicAndVideoWithoutPartialWrites() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var auth = new CatalogMvcTestSupport()) {
            f.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", AUDIO);
            f.jdbc.update("UPDATE media_asset SET status='DELETE_FAILED' WHERE id=?", VIDEO);
            var mvc = mvc(f, auth);
            var musicResponse = mvc.perform(music(auth.token(1), MUSIC_JSON)).andReturn().getResponse();
            var videoResponse = mvc.perform(video(auth.token(1), VIDEO_JSON)).andReturn().getResponse();
            assertEquals(409, musicResponse.getStatus());
            assertEquals(409, videoResponse.getStatus());
            assertNoSensitiveFailure(musicResponse.getContentAsString());
            assertNoSensitiveFailure(videoResponse.getContentAsString());
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));
            assertEquals("历史歌", f.jdbc.queryForObject("SELECT title FROM music_track WHERE id=?", String.class, MUSIC_ID));
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM surprise_video WHERE id='video-1'", Long.class));
            assertEquals("历史视频", f.jdbc.queryForObject("SELECT title FROM surprise_video WHERE id='video-1'", String.class));
        }
    }

    @Test void unknownManagedCoverConflictsWhileRegisteredAndExternalCoversRemainEditable() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var auth = new CatalogMvcTestSupport()) {
            var mvc = mvc(f, auth);
            var token = auth.token(1);
            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?",
                    "https://img.example/file/blog/images/not-registered.png", MUSIC_ID);
            var unknown = mvc.perform(music(token, MUSIC_JSON)).andReturn().getResponse();
            assertEquals(409, unknown.getStatus());
            assertNoSensitiveFailure(unknown.getContentAsString());
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));

            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?",
                    "https://img.example/file/blog/images/cover.png", MUSIC_ID);
            mvc.perform(music(token, MUSIC_JSON)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.version").value(8));
            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?",
                    "https://outside.example/covers/cover.png", MUSIC_ID);
            mvc.perform(music(token, MUSIC_JSON.replace("\"expectedVersion\":7", "\"expectedVersion\":8")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(9));
        }
    }

    @Test void ownerAuthenticationAndInvalidInputKeepExistingHttpContract() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var auth = new CatalogMvcTestSupport()) {
            var mvc = mvc(f, auth);
            mvc.perform(music(null, MUSIC_JSON)).andExpect(status().isUnauthorized());
            mvc.perform(video(null, VIDEO_JSON)).andExpect(status().isUnauthorized());
            mvc.perform(music(auth.token(2), MUSIC_JSON)).andExpect(status().isForbidden());
            mvc.perform(video(auth.token(2), VIDEO_JSON)).andExpect(status().isForbidden());

            String invalidMusic = MUSIC_JSON.replace("\"expectedVersion\":7", "\"expectedVersion\":\"7\"");
            String invalidVideo = VIDEO_JSON.replace("\"expectedVersion\":7", "\"expectedVersion\":-1");
            var musicResponse = mvc.perform(music(auth.token(1), invalidMusic)).andReturn().getResponse();
            var videoResponse = mvc.perform(video(auth.token(1), invalidVideo)).andReturn().getResponse();
            assertEquals(422, musicResponse.getStatus());
            assertEquals(422, videoResponse.getStatus());
            assertNoSensitiveFailure(musicResponse.getContentAsString());
            assertNoSensitiveFailure(videoResponse.getContentAsString());
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM surprise_video WHERE id='video-1'", Long.class));
        }
    }

    @Test void databaseLockFailureReturnsSanitizedServiceUnavailable() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var auth = new CatalogMvcTestSupport()) {
            f.schema.execute("ALTER TABLE media_asset RENAME COLUMN ownership_note TO fixture_failure_probe");
            var mvc = mvc(f, auth);
            var token = auth.token(1);
            var musicResponse = mvc.perform(music(token, MUSIC_JSON)).andReturn().getResponse();
            var videoResponse = mvc.perform(video(token, VIDEO_JSON)).andReturn().getResponse();
            assertEquals(503, musicResponse.getStatus());
            assertEquals(503, videoResponse.getStatus());
            assertNoSensitiveFailure(musicResponse.getContentAsString());
            assertNoSensitiveFailure(videoResponse.getContentAsString());
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));
            assertEquals("历史歌", f.jdbc.queryForObject("SELECT title FROM music_track WHERE id=?", String.class, MUSIC_ID));
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM surprise_video WHERE id='video-1'", Long.class));
            assertEquals("历史视频", f.jdbc.queryForObject("SELECT title FROM surprise_video WHERE id='video-1'", String.class));
        }
    }

    private static MockMvc mvc(CatalogMetadataWriteProtectionFixture f, CatalogMvcTestSupport auth) {
        var assets = new MediaAssetQueryApplicationService(
                new MybatisMediaAssetRepository(f.session.getMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper.class),
                        new MediaAssetConverter()),
                new ImgBedMediaPublicUrlRepository(URI.create("https://img.example"), "blog"));
        var music = new PersistentMusicCatalogApplicationService(f.music, assets,
                MediaCatalogRuntimeApiIntegrationTest.snapshots(), ids -> java.util.Map.of(),
                new MusicPolicy("939817038"), Clock.fixed(NOW, ZoneOffset.UTC), f.tx);
        var videos = new PersistentSurpriseVideoApplicationService(f.videos, assets,
                Clock.fixed(NOW, ZoneOffset.UTC), f.tx);
        return auth.build(new Object[] {
                new MusicAdminController(mock(MusicApplicationService.class), music),
                new SurpriseAdminController(videos)
        }, new MusicExceptionHandler(), new SurpriseExceptionHandler());
    }

    private static MockHttpServletRequestBuilder music(String token, String json) {
        var request = put("/api/admin/music/tracks/{id}", MUSIC_ID).contentType("application/json").content(json);
        return token == null ? request : request.header("Authorization", token);
    }

    private static MockHttpServletRequestBuilder video(String token, String json) {
        var request = put("/api/admin/surprise/videos/video-1").contentType("application/json").content(json);
        return token == null ? request : request.header("Authorization", token);
    }

    private static void assertNoSensitiveFailure(String response) {
        assertFalse(response.contains("SELECT"));
        assertFalse(response.contains("img.example"));
        assertFalse(response.contains("blog/music/audio.mp3"));
        assertFalse(response.contains("fixture_failure_probe"));
    }
}

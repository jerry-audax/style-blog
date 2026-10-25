package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.test.RepositoryPaths;
import com.blogsystem.trigger.http.music.HostedMusicPublicController;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExistingCoverBindingMigrationTest {
    private static final String SCRIPT = "016_20261025_bind_verified_existing_music_cover_assets.sql";
    private static final String TRACK = "  Existing-视频-ID  ";
    private static final String AUDIO = "blog/music/中文+100%.mp3";
    private static final String COVER = "blog/images/cover.png";

    private MediaPostgresTestSupport.ScopedSchema fixture() throws Exception {
        var schema = MediaRuntimeRepositoryIntegrationTest.protectedFixture();
        schema.execute("UPDATE music_track SET cover_asset_id=NULL");
        return schema;
    }

    private void apply(MediaPostgresTestSupport.ScopedSchema schema, String audio, String cover) throws Exception {
        apply(schema, audio, cover, 1);
    }

    private void apply(MediaPostgresTestSupport.ScopedSchema schema, String audio, String cover, int expectedCount) throws Exception {
        String sql = Files.readString(RepositoryPaths.file("migration", SCRIPT)).replace(":'expected_count'", "'" + expectedCount + "'");
        try (var connection = schema.openConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE music_cover_repair_input(track_id VARCHAR(64) PRIMARY KEY,audio_key TEXT NOT NULL,cover_key TEXT NOT NULL) ON COMMIT DROP");
            }
            try (var input = connection.prepareStatement("INSERT INTO music_cover_repair_input VALUES(?,?,?)")) {
                input.setString(1, TRACK); input.setString(2, audio); input.setString(3, cover); input.executeUpdate();
            }
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        }
    }

    @Test void publicPlaylistChangesFromMissingToPersistedCoverWithoutFetchingNetease() throws Exception {
        try (var schema = fixture()) {
            var session = MediaRuntimeRepositoryIntegrationTest.springSession(schema.name());
            var repository = MediaRuntimeRepositoryIntegrationTest.music(session);
            var assets = new MediaAssetQueryApplicationService(new MybatisMediaAssetRepository(
                    session.getMapper(MediaAssetMapper.class), new MediaAssetConverter()),
                    new ImgBedMediaPublicUrlRepository(URI.create("https://media.example.test"), "blog"));
            var service = new PersistentMusicCatalogApplicationService(repository, assets,
                    MediaCatalogRuntimeApiIntegrationTest.snapshots(), ids -> { throw new AssertionError("GET must not fetch cover metadata"); },
                    new MusicPolicy("939817038"), Clock.fixed(MediaRuntimeRepositoryIntegrationTest.NOW, ZoneOffset.UTC),
                    MediaRuntimeRepositoryIntegrationTest.transactions(schema.name()));
            var mvc = MockMvcBuilders.standaloneSetup(new HostedMusicPublicController(service)).build();
            var before = repository.findSnapshotById(TRACK).orElseThrow();
            mvc.perform(get("/api/music/hosted-playlist")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.songs[0].cover").value(""));
            apply(schema, AUDIO, COVER);
            mvc.perform(get("/api/music/hosted-playlist")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.songs[0].cover").value("https://media.example.test/file/blog/images/cover.png"));
            var after = repository.findSnapshotById(TRACK).orElseThrow();
            assertEquals(before.version()+1, after.version());
            assertEquals(before.track().title(), after.track().title());
            assertEquals(before.track().artist(), after.track().artist());
            assertEquals(before.track().audioAssetId(), after.track().audioAssetId());
            assertEquals(before.track().sourceId(), after.track().sourceId());
            assertEquals(before.track().lyricsAssetId(), after.track().lyricsAssetId());
            assertEquals(before.track().sortOrder(), after.track().sortOrder());
            assertEquals(before.track().enabled(), after.track().enabled());
            assertEquals(before.createdAt(), after.createdAt());
        }
    }

    @Test void differentAudioIdentityNeverReceivesTheCover() throws Exception {
        try (var schema = fixture()) {
            assertThrows(ScriptStatementFailedException.class, () -> apply(schema, "blog/music/different.mp3", COVER));
            assertNull(MediaRuntimeRepositoryIntegrationTest.music(MediaRuntimeRepositoryIntegrationTest.springSession(schema.name())).findSnapshotById(TRACK).orElseThrow().track().coverAssetId());
        }
    }

    @Test void reviewedCountMustMatchTheWholeInputBeforeAnyBindingChanges() throws Exception {
        try (var schema = fixture()) {
            assertThrows(ScriptStatementFailedException.class, () -> apply(schema, AUDIO, COVER, 2));
            var snapshot = MediaRuntimeRepositoryIntegrationTest.music(MediaRuntimeRepositoryIntegrationTest.springSession(schema.name())).findSnapshotById(TRACK).orElseThrow();
            assertNull(snapshot.track().coverAssetId());
            assertEquals(7, snapshot.version());
        }
    }

    @Test void wrongResourceKindAndInactiveImageAreRejected() throws Exception {
        try (var schema = fixture()) {
            assertThrows(ScriptStatementFailedException.class, () -> apply(schema, AUDIO, "blog/surprise/a.mp4"));
            schema.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE media_kind='IMAGE'");
            assertThrows(ScriptStatementFailedException.class, () -> apply(schema, AUDIO, COVER));
            assertNull(MediaRuntimeRepositoryIntegrationTest.music(MediaRuntimeRepositoryIntegrationTest.springSession(schema.name())).findSnapshotById(TRACK).orElseThrow().track().coverAssetId());
        }
    }

    @Test void rerunCannotOverwriteAnAlreadyBoundCoverOrAdvanceItsVersion() throws Exception {
        try (var schema = fixture()) {
            apply(schema, AUDIO, COVER);
            var repository = MediaRuntimeRepositoryIntegrationTest.music(MediaRuntimeRepositoryIntegrationTest.springSession(schema.name()));
            var first = repository.findSnapshotById(TRACK).orElseThrow();
            assertThrows(ScriptStatementFailedException.class, () -> apply(schema, AUDIO, COVER));
            assertEquals(first, repository.findSnapshotById(TRACK).orElseThrow());
        }
    }
}

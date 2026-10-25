package com.blogsystem.it.media;

import static com.blogsystem.it.media.CatalogMetadataWriteProtectionFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.blogsystem.domain.music.model.*;
import com.blogsystem.domain.surprise.model.*;
import java.sql.Connection;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CatalogMetadataWriteProtectionIntegrationTest {
    private static final UUID REPLACEMENT_AUDIO = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_COVER = UUID.fromString("00000000-0000-0000-0000-000000000006");

    static Stream<Arguments> inactiveAssets() {
        return Stream.of(AUDIO, COVER, LYRICS, VIDEO).flatMap(id ->
                Stream.of("DELETE_PENDING", "DELETE_FAILED", "DELETED").map(status -> Arguments.of(id, status)));
    }

    @ParameterizedTest @MethodSource("inactiveAssets")
    void retainedInactiveAssetRejectsMetadataIncludingDisable(UUID id, String status) throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.jdbc.update("UPDATE media_asset SET status=? WHERE id=?", status, id);
            var beforeMusic = f.jdbc.queryForMap("SELECT * FROM music_track WHERE id=?", MUSIC_ID);
            var beforeVideo = f.jdbc.queryForMap("SELECT * FROM surprise_video WHERE id='video-1'");
            if (id.equals(VIDEO)) {
                var error = assertThrows(SurpriseCatalogException.class, () -> f.tx.execute(s ->
                        f.videos.updateMetadata("video-1", new SurpriseVideoMetadata("编辑", false, 2), 7, NOW)));
                assertEquals(SurpriseCatalogException.Reason.CONFLICT, error.reason());
            } else {
                var error = assertThrows(MusicCatalogException.class, () -> f.tx.execute(s ->
                        f.music.updateMetadata(MUSIC_ID, new MusicTrackMetadata("编辑", "新歌手", null, false, 2), 7, NOW)));
                assertEquals(MusicCatalogException.Reason.CONFLICT, error.reason());
            }
            assertEquals(beforeMusic, f.jdbc.queryForMap("SELECT * FROM music_track WHERE id=?", MUSIC_ID));
            assertEquals(beforeVideo, f.jdbc.queryForMap("SELECT * FROM surprise_video WHERE id='video-1'"));
        }
    }

    @Test void allActiveReferencesAllowExactlyOneMetadataVersionAdvance() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            var before = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            var saved = f.tx.execute(s -> f.music.updateMetadata(MUSIC_ID, new MusicTrackMetadata("新标题", "新歌手", null, false, 9), 7, NOW));
            assertEquals(8, saved.version());
            assertEquals(AUDIO, saved.track().audioAssetId());
            assertEquals(COVER, saved.track().coverAssetId());
            assertEquals(LYRICS, saved.track().lyricsAssetId());
            assertEquals(before.createdAt(), saved.createdAt());
            var video = f.tx.execute(s -> f.videos.updateMetadata("video-1", new SurpriseVideoMetadata("新视频", false, 9), 7, NOW));
            assertEquals(8, video.version());
            assertEquals(VIDEO, video.video().videoAssetId());
        }
    }

    @Test void outsideTransactionRejectsEvenEmptyBindings() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.jdbc.update("UPDATE music_track SET audio_asset_id=NULL,cover_asset_id=NULL,lyrics_asset_id=NULL");
            var before = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            assertEquals(MusicCatalogException.Reason.UNAVAILABLE, assertThrows(MusicCatalogException.class,
                    () -> f.music.updateMetadata(MUSIC_ID, new MusicTrackMetadata("编辑", "", null, false, 0), 7, NOW)).reason());
            assertEquals(before, f.music.findSnapshotById(MUSIC_ID).orElseThrow());
            assertEquals(SurpriseCatalogException.Reason.UNAVAILABLE, assertThrows(SurpriseCatalogException.class,
                    () -> f.videos.updateMetadata("video-1", new SurpriseVideoMetadata("编辑", false, 0), 7, NOW)).reason());
        }
    }

    @Test @Timeout(30)
    void metadataWaitsForAssetLockBeforeTakingBusinessLock() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var assetOwner = f.schema.openConnection()) {
            assetOwner.setAutoCommit(false);
            try (var statement = assetOwner.prepareStatement("SELECT id FROM media_asset WHERE id=? FOR UPDATE")) {
                statement.setObject(1, AUDIO);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            f.trace.statements.clear();
            var pool = Executors.newSingleThreadExecutor();
            var started = new CountDownLatch(1);
            var pid = new AtomicInteger();
            try {
                var edit = pool.submit(() -> f.tx.execute(status -> {
                    pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    started.countDown();
                    return f.music.updateMetadata(MUSIC_ID,
                            new MusicTrackMetadata("锁等待后更新", "歌手", null, true, 1), 7, NOW);
                }));
                assertTrue(started.await(10, TimeUnit.SECONDS));
                awaitBlocked(f, pid.get());
                assertFalse(edit.isDone());
                assertEquals(1, countLockQueries(f.trace.statements, "from media_asset where id in"));
                assertEquals(0, countLockQueries(f.trace.statements, "from music_track t where t.id"));

                try (Connection businessProbe = f.schema.openConnection()) {
                    businessProbe.setAutoCommit(false);
                    try (var statement = businessProbe.prepareStatement(
                            "SELECT id FROM music_track WHERE id=? FOR UPDATE NOWAIT")) {
                        statement.setString(1, MUSIC_ID);
                        try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                    } finally { businessProbe.rollback(); }
                }

                assetOwner.commit();
                assertEquals(8, edit.get(10, TimeUnit.SECONDS).version());
                int assetLock = firstLockQuery(f.trace.statements, "from media_asset where id in");
                int businessLock = firstLockQuery(f.trace.statements, "from music_track t where t.id");
                assertTrue(assetLock >= 0 && businessLock > assetLock,
                        "PostgreSQL lock trace must show asset rows before the music row");
            } finally {
                assetOwner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test @Timeout(30)
    void changedBindingAfterSnapshotRejectsWithoutLateAssetLocks() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var assetOwner = f.schema.openConnection()) {
            f.insertAsset(REPLACEMENT_AUDIO, "AUDIO", "blog/music/replacement.mp3");
            assetOwner.setAutoCommit(false);
            try (var statement = assetOwner.prepareStatement("SELECT id FROM media_asset WHERE id=? FOR UPDATE")) {
                statement.setObject(1, AUDIO);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            f.trace.statements.clear();
            var pool = Executors.newSingleThreadExecutor();
            var started = new CountDownLatch(1);
            var pid = new AtomicInteger();
            try {
                var edit = pool.submit(() -> {
                    try {
                        f.tx.execute(status -> {
                            pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            started.countDown();
                            return f.music.updateMetadata(MUSIC_ID,
                                    new MusicTrackMetadata("不得覆盖", "歌手", null, false, 9), 7, NOW);
                        });
                        return null;
                    } catch (MusicCatalogException failure) {
                        return failure.reason();
                    }
                });
                assertTrue(started.await(10, TimeUnit.SECONDS));
                awaitBlocked(f, pid.get());
                assertFalse(edit.isDone());

                assertEquals(1, f.jdbc.update("UPDATE music_track SET audio_asset_id=?,version=version+1,"
                                + "updated_at=updated_at+interval '1 microsecond' WHERE id=?",
                        REPLACEMENT_AUDIO, MUSIC_ID));
                assetOwner.commit();

                assertEquals(MusicCatalogException.Reason.CONFLICT, edit.get(10, TimeUnit.SECONDS));
                assertEquals(1, countLockQueries(f.trace.statements, "from media_asset where id in"));
                assertEquals("历史歌", f.jdbc.queryForObject("SELECT title FROM music_track WHERE id=?", String.class, MUSIC_ID));
                assertEquals(8, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));
                assertEquals(REPLACEMENT_AUDIO,
                        f.jdbc.queryForObject("SELECT audio_asset_id FROM music_track WHERE id=?", UUID.class, MUSIC_ID));
            } finally {
                assetOwner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test @Timeout(30)
    void changedExternalCoverAndVersionAfterSnapshotRejectsWithoutLateAssetLocks() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var assetOwner = f.schema.openConnection()) {
            f.insertAsset(SECOND_COVER, "IMAGE", "blog/images/second.png");
            assetOwner.setAutoCommit(false);
            try (var statement = assetOwner.prepareStatement("SELECT id FROM media_asset WHERE id=? FOR UPDATE")) {
                statement.setObject(1, AUDIO);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            f.trace.statements.clear();
            var pool = Executors.newSingleThreadExecutor();
            var started = new CountDownLatch(1);
            var pid = new AtomicInteger();
            try {
                var edit = pool.submit(() -> {
                    try {
                        f.tx.execute(status -> {
                            pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            started.countDown();
                            return f.music.updateMetadata(MUSIC_ID,
                                    new MusicTrackMetadata("不得覆盖封面变化", "歌手", null, false, 9), 7, NOW);
                        });
                        return null;
                    } catch (MusicCatalogException failure) {
                        return failure.reason();
                    }
                });
                assertTrue(started.await(10, TimeUnit.SECONDS));
                awaitBlocked(f, pid.get());
                assertFalse(edit.isDone());
                String changedCover = "https://img.example/file/blog/images/second.png";
                assertEquals(1, f.jdbc.update("UPDATE music_track SET external_cover_url=?,version=version+1,"
                                + "updated_at=updated_at+interval '1 microsecond' WHERE id=?",
                        changedCover, MUSIC_ID));
                assetOwner.commit();

                assertEquals(MusicCatalogException.Reason.CONFLICT, edit.get(10, TimeUnit.SECONDS));
                assertEquals(1, countLockQueries(f.trace.statements, "from media_asset where id in"));
                assertEquals("历史歌", f.jdbc.queryForObject("SELECT title FROM music_track WHERE id=?", String.class, MUSIC_ID));
                assertEquals(8, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));
                assertEquals(changedCover,
                        f.jdbc.queryForObject("SELECT external_cover_url FROM music_track WHERE id=?", String.class, MUSIC_ID));
            } finally {
                assetOwner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test @Timeout(30)
    void concurrentReverseCoverAndManagedLinkSetsCompleteWithoutDeadlock() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.insertAsset(SECOND_COVER, "IMAGE", "blog/images/second.png");
            String firstUrl = "https://img.example/file/blog/images/cover.png";
            String secondUrl = "https://img.example/file/blog/images/second.png";
            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?", secondUrl, MUSIC_ID);
            f.jdbc.update("INSERT INTO music_track(id,title,artist,audio_asset_id,cover_asset_id,lyrics_asset_id,"
                            + "external_cover_url,enabled,sort_order,version,created_at,updated_at) "
                            + "VALUES ('reverse-track','另一首','歌手',?,?,?,?,true,0,7,"
                            + "'2026-10-13T00:00:00Z','2026-10-13T00:00:00Z')",
                    AUDIO, SECOND_COVER, LYRICS, firstUrl);
            var pool = Executors.newFixedThreadPool(2);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try {
                var first = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return f.tx.execute(status -> f.music.updateMetadata(MUSIC_ID,
                            new MusicTrackMetadata("更新一", "歌手", null, true, 1), 7, NOW));
                });
                var second = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return f.tx.execute(status -> f.music.updateMetadata("reverse-track",
                            new MusicTrackMetadata("更新二", "歌手", null, true, 2), 7, NOW));
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                try {
                    assertEquals(8, first.get(15, TimeUnit.SECONDS).version());
                    assertEquals(8, second.get(15, TimeUnit.SECONDS).version());
                } catch (java.util.concurrent.ExecutionException failure) {
                    throw new AssertionError("Concurrent metadata update failed", failure);
                }
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void metadataUpdatesKeepNullAndZeroAssetMetadataAndUsePostgresMicros() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.jdbc.update("UPDATE media_asset SET file_size=0,duration_ms=0,mime_type=NULL,sha256=NULL WHERE id=?", AUDIO);
            f.tx.execute(status -> f.music.updateMetadata(MUSIC_ID,
                    new MusicTrackMetadata("微秒测试", "歌手", "0", false, 3), 7, NOW));

            var asset = f.jdbc.queryForMap("SELECT file_size,duration_ms,mime_type,sha256 FROM media_asset WHERE id=?", AUDIO);
            assertEquals(0L, ((Number) asset.get("file_size")).longValue());
            assertEquals(0L, ((Number) asset.get("duration_ms")).longValue());
            assertNull(asset.get("mime_type"));
            assertNull(asset.get("sha256"));
            assertEquals("0", f.jdbc.queryForObject("SELECT source_id FROM music_track WHERE id=?", String.class, MUSIC_ID));
            assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), f.jdbc.queryForObject(
                    "SELECT updated_at FROM music_track WHERE id=?", java.time.OffsetDateTime.class, MUSIC_ID).toInstant());
        }
    }

    @Test void managedExternalCoverMustExistButUnknownExternalDomainsRemainAllowed() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?",
                    "https://img.example/file/blog/images/missing.png", MUSIC_ID);
            var unknown = assertThrows(MusicCatalogException.class, () -> f.tx.execute(status ->
                    f.music.updateMetadata(MUSIC_ID, new MusicTrackMetadata("不得保存", "歌手", null, true, 0), 7, NOW)));
            assertEquals(MusicCatalogException.Reason.CONFLICT, unknown.reason());
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));

            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?",
                    "https://img.example/file/blog/images/cover.png", MUSIC_ID);
            assertEquals(8, f.tx.execute(status -> f.music.updateMetadata(MUSIC_ID,
                    new MusicTrackMetadata("已登记封面", "歌手", null, true, 0), 7, NOW)).version());

            f.jdbc.update("UPDATE music_track SET external_cover_url=? WHERE id=?",
                    "https://outside.example/covers/cover.png", MUSIC_ID);
            assertEquals(9, f.tx.execute(status -> f.music.updateMetadata(MUSIC_ID,
                    new MusicTrackMetadata("外域封面", "歌手", null, true, 0), 8, NOW)).version());
        }
    }

    @Test void maximumVersionDeletedRowsAndLockQueryFailureHaveStableOutcomes() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.jdbc.update("UPDATE music_track SET version=? WHERE id=?", Long.MAX_VALUE, MUSIC_ID);
            var max = assertThrows(MusicCatalogException.class, () -> f.tx.execute(status ->
                    f.music.updateMetadata(MUSIC_ID, new MusicTrackMetadata("不得溢出", "", null, true, 0), Long.MAX_VALUE, NOW)));
            assertEquals(MusicCatalogException.Reason.CONFLICT, max.reason());
            f.jdbc.update("UPDATE surprise_video SET version=? WHERE id='video-1'", Long.MAX_VALUE);
            var maxVideo = assertThrows(SurpriseCatalogException.class, () -> f.tx.execute(status ->
                    f.videos.updateMetadata("video-1", new SurpriseVideoMetadata("不得溢出", false, 0), Long.MAX_VALUE, NOW)));
            assertEquals(SurpriseCatalogException.Reason.CONFLICT, maxVideo.reason());
            f.jdbc.update("INSERT INTO music_track(id,title,artist,enabled,sort_order,version,deleted_at,created_at,updated_at) "
                    + "VALUES ('deleted-track','已删除','',true,0,7,now(),now(),now())");
            var deleted = assertThrows(MusicCatalogException.class, () -> f.tx.execute(status ->
                    f.music.updateMetadata("deleted-track", new MusicTrackMetadata("已删除", "", null, true, 0), 7, NOW)));
            assertEquals(MusicCatalogException.Reason.NOT_FOUND, deleted.reason());
            f.jdbc.update("INSERT INTO surprise_video(id,title,video_asset_id,enabled,sort_order,version,deleted_at,created_at,updated_at) "
                    + "VALUES ('deleted-video','已删除视频',?,true,0,7,now(),now(),now())", VIDEO);
            var deletedVideo = assertThrows(SurpriseCatalogException.class, () -> f.tx.execute(status ->
                    f.videos.updateMetadata("deleted-video", new SurpriseVideoMetadata("已删除视频", false, 0), 7, NOW)));
            assertEquals(SurpriseCatalogException.Reason.NOT_FOUND, deletedVideo.reason());

            f.jdbc.update("UPDATE music_track SET version=7 WHERE id=?", MUSIC_ID);
            f.jdbc.update("UPDATE surprise_video SET version=7 WHERE id='video-1'");
            f.schema.execute("ALTER TABLE media_asset RENAME COLUMN ownership_note TO fixture_failure_probe");
            var musicFailure = assertThrows(MusicCatalogException.class, () -> f.tx.execute(status ->
                    f.music.updateMetadata(MUSIC_ID, new MusicTrackMetadata("SQL不可用", "", null, false, 0), 7, NOW)));
            assertEquals(MusicCatalogException.Reason.UNAVAILABLE, musicFailure.reason());
            var videoFailure = assertThrows(SurpriseCatalogException.class, () -> f.tx.execute(status ->
                    f.videos.updateMetadata("video-1", new SurpriseVideoMetadata("SQL不可用", false, 0), 7, NOW)));
            assertEquals(SurpriseCatalogException.Reason.UNAVAILABLE, videoFailure.reason());
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?", Long.class, MUSIC_ID));
            assertEquals("历史视频", f.jdbc.queryForObject("SELECT title FROM surprise_video WHERE id='video-1'", String.class));
            assertEquals(7, f.jdbc.queryForObject("SELECT version FROM surprise_video WHERE id='video-1'", Long.class));
        }
    }

    private static int countLockQueries(List<String> statements, String fragment) {
        return (int) statements.stream().filter(sql -> sql.contains(fragment) && sql.contains("for update")).count();
    }

    private static int firstLockQuery(List<String> statements, String fragment) {
        return IntStream.range(0, statements.size())
                .filter(index -> statements.get(index).contains(fragment) && statements.get(index).contains("for update"))
                .findFirst().orElse(-1);
    }

    private static void awaitBlocked(CatalogMetadataWriteProtectionFixture fixture, int pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(fixture.jdbc.queryForObject(
                    "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected an actual PostgreSQL row-lock wait");
    }
}

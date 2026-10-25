package com.blogsystem.it.media;

import static com.blogsystem.it.media.CatalogMetadataWriteProtectionFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.domain.music.model.*;
import com.blogsystem.domain.surprise.model.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;

class UploadBindingWriteProtectionIntegrationTest {
    static final UUID NEW_ASSET = UUID.fromString("00000000-0000-0000-0000-000000000010");

    static Stream<Arguments> replacements() {
        return Stream.of(MusicMediaKind.values()).flatMap(kind ->
                Stream.of("DELETE_PENDING", "DELETE_FAILED", "DELETED")
                        .map(status -> Arguments.of(kind, status)));
    }

    @ParameterizedTest @MethodSource("replacements")
    void retainedInactiveResourceRejectsReplacement(MusicMediaKind kind, String status) throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            var before = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            f.insertAsset(NEW_ASSET, mediaKind(kind), "blog/uploads/replacement");
            UUID retained = kind == MusicMediaKind.AUDIO ? COVER : AUDIO;
            f.jdbc.update("UPDATE media_asset SET status=? WHERE id=?", status, retained);
            var failure = assertThrows(MusicCatalogException.class, () -> f.tx.execute(s ->
                    f.music.bindMedia(MUSIC_ID, kind, NEW_ASSET, 7, slot(before, kind), NOW)));
            assertEquals(MusicCatalogException.Reason.CONFLICT, failure.reason());
            assertEquals(before, f.music.findSnapshotById(MUSIC_ID).orElseThrow());
        }
    }

    @ParameterizedTest @EnumSource(MusicMediaKind.class)
    void replacingInactiveOldSlotAllowsActiveNewResource(MusicMediaKind kind) throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            var before = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            f.insertAsset(NEW_ASSET, mediaKind(kind), "blog/uploads/replacement");
            f.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", slot(before, kind));
            var saved = f.tx.execute(s -> f.music.bindMedia(MUSIC_ID, kind, NEW_ASSET, 7, slot(before, kind), NOW));
            assertEquals(8, saved.version());
            assertEquals(NEW_ASSET, slot(saved, kind));
            assertEquals(before.track().title(), saved.track().title());
            assertEquals(before.createdAt(), saved.createdAt());
        }
    }

    @Test void bindingOutsideTransactionFailsClosed() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            var before = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            assertEquals(MusicCatalogException.Reason.UNAVAILABLE, assertThrows(MusicCatalogException.class,
                    () -> f.music.bindMedia(MUSIC_ID, MusicMediaKind.AUDIO, AUDIO, 7, AUDIO, NOW)).reason());
            assertEquals(before, f.music.findSnapshotById(MUSIC_ID).orElseThrow());
        }
    }

    @Test void videoCreationOutsideTransactionFailsClosed() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            assertEquals(SurpriseCatalogException.Reason.UNAVAILABLE, assertThrows(SurpriseCatalogException.class,
                    () -> f.videos.insertNewVideo(new PersistedSurpriseVideo("new-video", "视频", VIDEO, true, 0), NOW)).reason());
            assertTrue(f.videos.findSnapshotById("new-video").isEmpty());
        }
    }

    @Test void unknownManagedExternalCoverRejectsBinding() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture()) {
            f.jdbc.update("UPDATE music_track SET external_cover_url='https://img.example/file/blog/missing.png' WHERE id=?", MUSIC_ID);
            var before = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
            assertEquals(MusicCatalogException.Reason.CONFLICT, assertThrows(MusicCatalogException.class,
                    () -> f.tx.execute(s -> f.music.bindMedia(MUSIC_ID, MusicMediaKind.AUDIO, AUDIO, 7, AUDIO, NOW))).reason());
            assertEquals(before, f.music.findSnapshotById(MUSIC_ID).orElseThrow());
        }
    }

    @Test @Timeout(30)
    void waitingBindingRejectsChangedReferenceSnapshotWithoutLateAssetLocks() throws Exception {
        try (var f = new CatalogMetadataWriteProtectionFixture(); var assetOwner = f.schema.openConnection()) {
            UUID lateCover = UUID.fromString("00000000-0000-0000-0000-000000000011");
            f.insertAsset(NEW_ASSET, "IMAGE", "blog/uploads/replacement");
            f.insertAsset(lateCover, "IMAGE", "blog/images/late-cover.png");
            assetOwner.setAutoCommit(false);
            int ownerPid;
            try (var statement = assetOwner.createStatement()) {
                try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); ownerPid = rows.getInt(1); }
                statement.executeQuery("SELECT id FROM media_asset WHERE id='" + AUDIO + "' FOR UPDATE").close();
            }
            f.trace.statements.clear();
            var started = new CountDownLatch(1);
            var pid = new AtomicInteger();
            var pool = Executors.newSingleThreadExecutor();
            try {
                var binding = pool.submit(() -> {
                    try {
                        f.tx.execute(status -> {
                            pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            started.countDown();
                            return f.music.bindMedia(MUSIC_ID, MusicMediaKind.COVER, NEW_ASSET, 7, COVER, NOW);
                        });
                        return null;
                    } catch (MusicCatalogException failure) {
                        return failure.reason();
                    }
                });
                assertTrue(started.await(10, TimeUnit.SECONDS));
                awaitResourceBlock(f, pid.get(), ownerPid);

                try (var probe = f.schema.openConnection(); var query = probe.createStatement()) {
                    probe.setAutoCommit(false);
                    query.execute("SET LOCAL lock_timeout='750ms'");
                    try (var rows = query.executeQuery("SELECT id FROM music_track WHERE id='" + MUSIC_ID + "' FOR UPDATE")) {
                        assertTrue(rows.next());
                    }
                    probe.rollback();
                }
                String changedCover = "https://img.example/file/blog/images/late-cover.png";
                assertEquals(1, f.jdbc.update("UPDATE music_track SET external_cover_url=?,version=version+1,"
                        + "updated_at=updated_at+interval '1 microsecond' WHERE id=?", changedCover, MUSIC_ID));
                assetOwner.commit();

                assertEquals(MusicCatalogException.Reason.CONFLICT, binding.get(10, TimeUnit.SECONDS));
                assertEquals(1, f.trace.statements.stream()
                        .filter(sql -> sql.contains("from media_asset where id in") && sql.contains("for update"))
                        .count());
                var current = f.music.findSnapshotById(MUSIC_ID).orElseThrow();
                assertEquals(8, current.version());
                assertEquals(COVER, current.track().coverAssetId());
                assertEquals(changedCover, current.track().externalCoverUrl());
            } finally {
                assetOwner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static void awaitResourceBlock(CatalogMetadataWriteProtectionFixture fixture, int pid, int ownerPid)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(fixture.jdbc.queryForObject(
                    "SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, ownerPid, pid))) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Binding never waited for the protected asset row");
    }

    static UUID slot(MusicTrackSnapshot row, MusicMediaKind kind) {
        return switch (kind) {
            case AUDIO -> row.track().audioAssetId();
            case COVER -> row.track().coverAssetId();
            case LYRICS -> row.track().lyricsAssetId();
        };
    }
    static String mediaKind(MusicMediaKind kind) { return kind == MusicMediaKind.COVER ? "IMAGE" : kind.name(); }
}

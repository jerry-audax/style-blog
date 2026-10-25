package com.blogsystem.it.media;

import com.blogsystem.it.media.MediaPostgresTestSupport.ScopedSchema;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaMetadataDdlIntegrationTest {
    private static final String CREATED_AT = "TIMESTAMPTZ '2026-10-10 12:00:00.123456+00'";

    @Test
    void ddlRejectsDuplicateLocationsAndInvalidMediaAssetValues() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();

            UUID original = UUID.fromString("00000000-0000-0000-0000-000000000001");
            schema.execute(assetInsert(original, "cloudflare_imgbed", "blog/music/宇多田/Blue%20LIVE.mp3",
                    "AUDIO", "NULL", "NULL", "NULL", "NULL", "NULL", "ACTIVE", "0"));
            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    "cloudflare_imgbed", "blog/music/宇多田/Blue%20LIVE.mp3", "AUDIO",
                    "NULL", "NULL", "NULL", "NULL", "NULL", "ACTIVE", "0"), "23505");
            assertDoesNotThrow(() -> schema.execute(assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000003"),
                    "another_provider", "blog/music/宇多田/Blue%20LIVE.mp3", "AUDIO",
                    "0", "NULL", "NULL", "0", "NULL", "ACTIVE", "0")));

            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                    "cloudflare_imgbed", "blog/music/negative-size.mp3", "AUDIO",
                    "-1", "NULL", "NULL", "NULL", "NULL", "ACTIVE", "0"), "23514");
            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000005"),
                    "cloudflare_imgbed", "blog/video/negative-duration.mp4", "VIDEO",
                    "NULL", "NULL", "NULL", "-1", "NULL", "ACTIVE", "0"), "23514");
            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000006"),
                    "cloudflare_imgbed", "blog/images/zero-width.png", "IMAGE",
                    "NULL", "0", "100", "NULL", "NULL", "ACTIVE", "0"), "23514");
            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000007"),
                    "cloudflare_imgbed", "blog/images/unknown.bin", "DOCUMENT",
                    "NULL", "NULL", "NULL", "NULL", "NULL", "ACTIVE", "0"), "23514");
            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000008"),
                    "cloudflare_imgbed", "blog/images/bad-status.png", "IMAGE",
                    "NULL", "NULL", "NULL", "NULL", "NULL", "UNKNOWN", "0"), "23514");
            assertSqlRejected(schema, assetInsert(UUID.fromString("00000000-0000-0000-0000-000000000009"),
                    "cloudflare_imgbed", "blog/images/negative-version.png", "IMAGE",
                    "NULL", "NULL", "NULL", "NULL", "NULL", "ACTIVE", "-1"), "23514");
        }
    }

    @Test
    void ddlProtectsBusinessReferencesAndEnforcesSortOrderBounds() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();

            UUID audio = UUID.fromString("00000000-0000-0000-0000-000000000010");
            UUID video = UUID.fromString("00000000-0000-0000-0000-000000000011");
            schema.execute(assetInsert(audio, "cloudflare_imgbed", "blog/music/track.mp3", "AUDIO",
                    "0", "NULL", "NULL", "0", "NULL", "ACTIVE", "0"));
            schema.execute(assetInsert(video, "cloudflare_imgbed", "blog/surprise/clip.mp4", "VIDEO",
                    "NULL", "NULL", "NULL", "0", "NULL", "ACTIVE", "0"));

            assertSqlRejected(schema, musicInsert("music-missing-asset", "missing", "-",
                    "00000000-0000-0000-0000-000000000099", "0"), "23503");
            assertSqlRejected(schema, surpriseInsert("surprise-missing-asset", "missing",
                    "00000000-0000-0000-0000-000000000099", "0"), "23503");

            schema.execute(musicInsert("music-min", "Minimum", "", audio.toString(), "0"));
            schema.execute(musicInsert("music-max", "Maximum", "", "NULL", "100000"));
            schema.execute(surpriseInsert("surprise-valid", "Valid surprise", video.toString(), "0"));
            assertSqlRejected(schema, musicInsert("music-negative-sort", "Negative", "", "NULL", "-1"), "23514");
            assertSqlRejected(schema, musicInsert("music-over-sort", "Over", "", "NULL", "100001"), "23514");
            assertSqlRejected(schema, surpriseInsert("surprise-over-sort", "Over", video.toString(), "100001"), "23514");

            assertSqlRejected(schema, "DELETE FROM media_asset WHERE id = '" + audio + "'", "23503");
            assertSqlRejected(schema, "DELETE FROM media_asset WHERE id = '" + video + "'", "23503");
        }
    }

    @Test
    void ddlDoesNotSilentlyMaskRepeatedExecution() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            assertThrows(RuntimeException.class, schema::executeDdl);
        }
    }

    private static String assetInsert(UUID id, String provider, String objectKey, String kind,
                                      String fileSize, String width, String height, String duration,
                                      String sha256, String status, String version) {
        return "INSERT INTO media_asset (id, provider, object_key, media_kind, file_name, mime_type, file_size, sha256, width, height, duration_ms, status, version, created_at, updated_at) VALUES ('"
                + id + "', '" + provider + "', '" + objectKey + "', '" + kind + "', 'fixture.bin', NULL, "
                + fileSize + ", " + sha256 + ", " + width + ", " + height + ", " + duration + ", '"
                + status + "', " + version + ", " + CREATED_AT + ", " + CREATED_AT + ")";
    }

    private static String musicInsert(String id, String title, String artist, String audioAssetId, String sortOrder) {
        return "INSERT INTO music_track (id, title, artist, audio_asset_id, enabled, sort_order, version, created_at, updated_at) VALUES ('"
                + id + "', '" + title + "', '" + artist + "', " + uuidOrNull(audioAssetId)
                + ", TRUE, " + sortOrder + ", 0, " + CREATED_AT + ", " + CREATED_AT + ")";
    }

    private static String surpriseInsert(String id, String title, String videoAssetId, String sortOrder) {
        return "INSERT INTO surprise_video (id, title, video_asset_id, enabled, sort_order, version, created_at, updated_at) VALUES ('"
                + id + "', '" + title + "', " + uuidOrNull(videoAssetId)
                + ", TRUE, " + sortOrder + ", 0, " + CREATED_AT + ", " + CREATED_AT + ")";
    }

    private static String uuidOrNull(String value) {
        return "NULL".equals(value) ? value : "'" + value + "'";
    }

    private static void assertSqlRejected(MediaPostgresTestSupport.ScopedSchema schema, String sql, String expectedSqlState) {
        SQLException error = assertThrows(SQLException.class, () -> schema.execute(sql));
        assertEquals(expectedSqlState, error.getSQLState());
    }
}

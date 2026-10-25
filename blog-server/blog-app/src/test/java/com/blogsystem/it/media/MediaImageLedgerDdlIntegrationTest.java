package com.blogsystem.it.media;

import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.metadata.model.valobj.*;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.*;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;
import com.blogsystem.infrastructure.mybatis.schema.MediaUploadSchemaVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaImageLedgerDdlIntegrationTest {
    static final Path DDL = com.blogsystem.test.RepositoryPaths.file("migration", "013_20261018_extend_media_upload_ledger_for_images.sql");
    static final Instant NOW = Instant.parse("2026-10-18T00:00:00.123456Z");

    @Test void migrationPreservesHistoricalMusicVideoRowsAndReceiptsAndRefusesRepeat() throws Exception {
        try (var schema = MediaUploadOperationRepositoryIntegrationTest.fixture();
             var session = MediaUploadOperationRepositoryIntegrationTest.factory(schema.name()).openSession(true)) {
            var repository = MediaUploadOperationRepositoryIntegrationTest.repo(session);
            var music = historical(MediaUploadTargetType.MUSIC_TRACK);
            var video = historical(MediaUploadTargetType.SURPRISE_CREATE);
            // Historical completed rows are fixture data, not new-intent commands.
            var converter = new com.blogsystem.infrastructure.mybatis.converter.asset.MediaUploadOperationConverter(
                    new MediaUploadSnapshotCodec(new ObjectMapper()));
            var mapper = session.getMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper.class);
            mapper.insert(converter.toRecord(music)); mapper.insert(converter.toRecord(video));
            String before = MediaUploadOperationRepositoryIntegrationTest.fingerprint(schema);
            String oldDefinition = digest(schema, "ck_media_upload_target");
            assertEquals("d0fee120e6f86aad28bd6ed406922a7a", oldDefinition);
            schema.executeDdl(DDL);
            assertEquals(before, MediaUploadOperationRepositoryIntegrationTest.fingerprint(schema));
            assertEquals(music, repository.findOwned(1, music.intent().id()).orElseThrow());
            assertEquals(video, repository.findOwned(1, video.intent().id()).orElseThrow());
            assertNotEquals(oldDefinition, digest(schema, "ck_media_upload_target_images"));
            assertThrows(RuntimeException.class, () -> schema.executeDdl(DDL));
            assertEquals(before, MediaUploadOperationRepositoryIntegrationTest.fingerprint(schema));
        }
    }

    @Test void acceptsImageSeedAndRejectsPurposeSlotLimitsAndSnapshotSchemaAtDatabaseBoundary() throws Exception {
        try (var schema = MediaUploadOperationRepositoryIntegrationTest.fixture()) {
            schema.executeDdl(DDL);
            try (var session = MediaUploadOperationRepositoryIntegrationTest.factory(schema.name()).openSession(true)) {
                var repository = MediaUploadOperationRepositoryIntegrationTest.repo(session);
                var ready = image(ImagePurpose.AVATAR, 2097152);
                repository.insert(ready);
                assertEquals(ready, repository.findOwned(1, ready.intent().id()).orElseThrow());
                String where = " WHERE id='" + ready.intent().id() + "'";
                for (String change : new String[]{"slot='IMAGE'", "file_size=2097153", "expected_target_version=0",
                        "target_id='not-the-asset'", "target_payload='{}'", "target_payload='{\"purpose\":\"OTHER\"}'",
                        "target_payload='{\"purpose\":\"AVATAR\",\"title\":\"unexpected\"}'", "mime_type='audio/mpeg'"}) {
                    assertEquals("23514", assertThrows(SQLException.class,
                            () -> schema.execute("UPDATE media_upload_operation SET " + change + where)).getSQLState(), change);
                }
                schema.execute("UPDATE media_upload_operation SET status='SUCCEEDED',stage='PERSIST',confirmed_chunks=1,"
                        + "confirmed_object_key=operation_folder||'/图片.png',result_snapshot='"
                        + new MediaUploadSnapshotCodec(new ObjectMapper()).encode(imageResult(ready)) + "'" + where);
                var complete = repository.findOwned(1, ready.intent().id()).orElseThrow();
                assertEquals(imageResult(ready), complete.outcome().result());
                for (String bad : new String[]{"1", "3"})
                    assertEquals("23514", assertThrows(SQLException.class, () -> schema.execute(
                            "UPDATE media_upload_operation SET result_snapshot=jsonb_set(result_snapshot,'{schemaVersion}','" + bad + "')" + where)).getSQLState());
            }
        }
    }

    @Test void readinessRequires013AndDetectsTamperedConstraintWithoutWrites() throws Exception {
        try (var schema = MediaUploadOperationRepositoryIntegrationTest.fixture()) {
            var verifier = new MediaUploadSchemaVerifier(dataSource(schema));
            assertThrows(IllegalStateException.class, verifier::verify);
            schema.executeDdl(DDL);
            for (String name : new String[]{"ck_media_upload_target_images", "ck_media_upload_file_images", "ck_media_upload_result_images"})
                System.out.println("013 constraint " + name + "=" + digest(schema, name));
            verifier.verify();
            String before = MediaUploadOperationRepositoryIntegrationTest.fingerprint(schema);
            verifier.verify();
            assertEquals(before, MediaUploadOperationRepositoryIntegrationTest.fingerprint(schema));
            schema.execute("ALTER TABLE media_upload_operation DROP CONSTRAINT ck_media_upload_target_images;"
                    + " ALTER TABLE media_upload_operation ADD CONSTRAINT ck_media_upload_target_images CHECK (TRUE)");
            assertThrows(IllegalStateException.class, verifier::verify);
        }
    }

    static DriverManagerDataSource dataSource(MediaPostgresTestSupport.ScopedSchema schema) {
        return new DriverManagerDataSource(System.getenv("MEDIA_PG_TEST_URL") + "?currentSchema=" + schema.name(),
                System.getenv("MEDIA_PG_TEST_USERNAME"), System.getenv("MEDIA_PG_TEST_PASSWORD"));
    }

    private static String digest(MediaPostgresTestSupport.ScopedSchema schema, String name) {
        return new JdbcTemplate(dataSource(schema)).queryForObject("SELECT md5(pg_get_constraintdef(oid)) FROM pg_constraint"
                + " WHERE conrelid='media_upload_operation'::regclass AND conname=?", String.class, name);
    }

    static MediaUploadOperation image(ImagePurpose purpose, long bytes) {
        UUID id = UUID.randomUUID(), asset = UUID.randomUUID();
        var seed = new MediaUploadImageSeed(purpose);
        var request = new MediaUploadRequest(id, MediaUploadTargetType.IMAGE_CREATE, null, seed.slot(), null,
                new MediaUploadFile("图片.png", "image/png", bytes, "a".repeat(64)), null, seed);
        return MediaUploadPolicy.ready(1, request, new MediaUploadTarget(MediaUploadTargetType.IMAGE_CREATE,
                asset.toString(), seed.slot(), null, null, null, seed), "cloudflare_imgbed", new MediaObjectKey("blog"), asset, NOW);
    }

    static ImageUploadResultSnapshot imageResult(MediaUploadOperation op) {
        return new ImageUploadResultSnapshot(resource(op), op.intent().target().image().purpose(), NOW, NOW);
    }

    static MediaUploadResourceSnapshot resource(MediaUploadOperation op) {
        var file = op.intent().file();
        return new MediaUploadResourceSnapshot(op.intent().candidateAssetId(), op.intent().provider(),
                new MediaObjectKey(op.intent().operationFolder().value() + "/图片.png"), op.intent().target().slot().kind(),
                MediaAssetStatus.ACTIVE, 0, new MediaMetadata(file.fileName(), file.mediaType(), file.size(), file.sourceSha256(), null, null, null));
    }

    private static MediaUploadOperation historical(MediaUploadTargetType type) {
        boolean music = type == MediaUploadTargetType.MUSIC_TRACK;
        var id = UUID.randomUUID();
        var request = new MediaUploadRequest(id, type, music ? "  Existing-视频-ID  " : null,
                music ? MediaUploadSlot.AUDIO : MediaUploadSlot.VIDEO, music ? 0L : null,
                new MediaUploadFile(music ? "歌曲.mp3" : "视频.mp4", music ? "audio/mpeg" : "video/mp4", 1, "a".repeat(64)),
                music ? null : new MediaUploadVideoSeed("视频", true, 0));
        var ready = MediaUploadPolicy.ready(1, request, new MediaUploadTarget(type, music ? request.targetId() : id.toString(),
                request.slot(), request.expectedVersion(), null, request.video()), "cloudflare_imgbed", new MediaObjectKey("blog"), UUID.randomUUID(), NOW);
        var resource = resource(ready);
        var result = new MediaUploadResultSnapshot(type, ready.intent().target().id(), music ? 1 : 0,
                music ? "歌曲" : "视频", music ? "" : null, null, true, 0, null,
                music ? resource : null, null, null, music ? null : resource, NOW, NOW);
        return new MediaUploadOperation(ready.intent(), new MediaUploadOperation.Progress(MediaUploadStatus.SUCCEEDED,
                MediaUploadStage.PERSIST, null, 1, null, false, 1, 0, 1), new MediaUploadOperation.Lease(null, null, 1),
                new MediaUploadOperation.Outcome(resource.objectKey(), null, result, null), 4, NOW, NOW);
    }
}

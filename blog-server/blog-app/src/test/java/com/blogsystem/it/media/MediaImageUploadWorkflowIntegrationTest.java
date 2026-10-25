package com.blogsystem.it.media;

import com.blogsystem.application.asset.image.service.ImageUploadBindingApplicationService;
import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaImageUploadWorkflowIntegrationTest {
    static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10,0,0,0,0};

    @Test void allPurposesCommitOnlyAssetAndReceiptAndReplayWithoutReupload() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var service = service(f);
            long before = count(f, "media_asset"), tracks = count(f, "music_track"), videos = count(f, "surprise_video");
            for (var purpose : ImagePurpose.values()) {
                var request = request(purpose, PNG);
                var created = service.create(1, request).operation();
                assertEquals(created.intent().candidateAssetId().toString(), created.intent().target().id());
                assertFalse(service.create(1, request).created());
                var done = service.content(1, request.operationId(), content(PNG, "image/png"));
                assertEquals(MediaUploadStatus.SUCCEEDED, done.progress().status());
                assertInstanceOf(ImageUploadResultSnapshot.class, done.outcome().result());
                assertEquals(purpose, ((ImageUploadResultSnapshot) done.outcome().result()).purpose());
                assertTrue(f.assets.findById(created.intent().candidateAssetId()).isPresent());
                assertEquals(done, service.recover(1, request.operationId(), 0));
                assertFalse(service.create(1, request).created());
                service.content(1, request.operationId(), content(PNG, "image/png"));
                assertEquals(MediaUploadException.Reason.NOT_FOUND,
                        assertThrows(MediaUploadException.class, () -> service.get(2, request.operationId())).reason());
            }
            assertEquals(List.of("direct", "direct", "direct"), f.storage.calls);
            assertEquals(before + 3, count(f, "media_asset"));
            assertEquals(tracks, count(f, "music_track")); assertEquals(videos, count(f, "surprise_video"));
        }
    }

    @Test void persistFailureRollsBackAssetAndSuccessThenRecoversOriginalReceipt() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var operations = spy(f.operations); var fail = new AtomicBoolean(true);
            doAnswer(a -> { if (fail.get()) throw new IllegalStateException("test-only persist failure"); return a.callRealMethod(); })
                    .when(operations).complete(any(), any());
            var service = f.service(operations, f.assets, ports(f), f.tx);
            var request = request(ImagePurpose.COVER, PNG); var original = service.create(1, request).operation();
            assertThrows(MediaUploadException.class, () -> service.content(1, request.operationId(), content(PNG, "image/png")));
            var retained = service.get(1, request.operationId());
            assertEquals(MediaUploadStatus.UPLOADED, retained.progress().status());
            assertNotNull(retained.outcome().confirmedObjectKey()); assertNull(retained.outcome().result());
            assertTrue(f.assets.findById(original.intent().candidateAssetId()).isEmpty());
            fail.set(false); MediaUploadRecoveryIntegrationTest.expire(f, retained);
            var recovered = service.recover(1, request.operationId(), retained.version());
            assertEquals(MediaUploadStatus.SUCCEEDED, recovered.progress().status());
            assertEquals(original.intent(), recovered.intent()); assertEquals(List.of("direct"), f.storage.calls);
        }
    }

    @Test void lostCommitAcknowledgementReadsDurableImageSuccessWithoutReupload() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var fail = new AtomicBoolean(true);
            TransactionOperations uncertain = new TransactionOperations() {
                public <T> T execute(TransactionCallback<T> callback) {
                    T result = f.tx.execute(callback);
                    if (result instanceof MediaUploadOperation op && op.progress().status() == MediaUploadStatus.SUCCEEDED && fail.getAndSet(false))
                        throw new IllegalStateException("test-only lost commit acknowledgement");
                    return result;
                }
            };
            var service = f.service(f.operations, f.assets, ports(f), uncertain);
            var request = request(ImagePurpose.ARTICLE, PNG); service.create(1, request);
            assertEquals(MediaUploadStatus.SUCCEEDED, service.content(1, request.operationId(), content(PNG, "image/png")).progress().status());
            assertEquals(List.of("direct"), f.storage.calls);
        }
    }

    @Test void sameOperationConcurrentCreateFreezesOneIdentityAndPurposeChangeConflicts() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var service = service(f); var request = request(ImagePurpose.ARTICLE, PNG);
            var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return service.create(1, request); });
                var b = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return service.create(1, request); });
                start.countDown(); var one = a.get(10, TimeUnit.SECONDS); var two = b.get(10, TimeUnit.SECONDS);
                assertNotEquals(one.created(), two.created()); assertEquals(one.operation().intent(), two.operation().intent());
                var changed = new MediaUploadRequest(request.operationId(), request.targetType(), null, request.slot(), null,
                        request.file(), null, new MediaUploadImageSeed(ImagePurpose.COVER));
                assertEquals(MediaUploadException.Reason.CONFLICT, assertThrows(MediaUploadException.class, () -> service.create(1, changed)).reason());
                assertEquals(1, count(f, "media_upload_operation")); assertTrue(f.storage.calls.isEmpty());
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }

    @Test void unknownDirectOutcomeNeverSendsAnotherUpload() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var service = service(f); var request = request(ImagePurpose.ARTICLE, PNG); service.create(1, request);
            f.storage.before = name -> { if (name.equals("direct")) throw new MediaUploadStorageException(
                    MediaUploadStorageException.Outcome.UNKNOWN, MediaUploadErrorCode.PROVIDER_UNKNOWN); };
            var unknown = service.content(1, request.operationId(), content(PNG, "image/png"));
            assertEquals(MediaUploadStatus.UNKNOWN, unknown.progress().status());
            var recovered = service.recover(1, request.operationId(), unknown.version());
            assertEquals(MediaUploadStatus.UNKNOWN, recovered.progress().status());
            assertEquals(List.of("direct", "probe"), f.storage.calls);
            assertTrue(f.assets.findById(unknown.intent().candidateAssetId()).isEmpty());
        }
    }

    @Test void expiredImageFenceCannotCommitLateProviderReceipt() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var service = service(f); var request = request(ImagePurpose.ARTICLE, PNG); service.create(1, request);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
            f.storage.before = name -> { if (name.equals("direct")) { entered.countDown(); try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } } };
            try {
                var sending = pool.submit(() -> service.content(1, request.operationId(), content(PNG, "image/png")));
                assertTrue(entered.await(5, TimeUnit.SECONDS)); var held = service.get(1, request.operationId());
                MediaUploadRecoveryIntegrationTest.expire(f, held);
                var unknown = service.recover(1, request.operationId(), held.version());
                assertEquals(MediaUploadStatus.UNKNOWN, unknown.progress().status()); release.countDown();
                assertInstanceOf(MediaUploadException.class, assertThrows(ExecutionException.class, () -> sending.get(10, TimeUnit.SECONDS)).getCause());
                assertTrue(f.assets.findById(held.intent().candidateAssetId()).isEmpty());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }

    @Test void sameSizeDifferentBytesAndForgedImageSignatureNeverReachProvider() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var service = service(f); var request = request(ImagePurpose.ARTICLE, PNG); service.create(1, request);
            byte[] changed = PNG.clone(); changed[11] = 1;
            assertEquals(MediaUploadErrorCode.HASH_MISMATCH, assertThrows(MediaUploadException.class,
                    () -> service.content(1, request.operationId(), content(changed, "image/png"))).code());
            byte[] forged = new byte[PNG.length]; var fake = request(ImagePurpose.ARTICLE, forged); service.create(1, fake);
            assertEquals(MediaUploadErrorCode.INVALID_CONTENT, assertThrows(MediaUploadException.class,
                    () -> service.content(1, fake.operationId(), content(forged, "image/png"))).code());
            assertTrue(f.storage.calls.isEmpty());
        }
    }

    static MediaUploadApplicationService service(MediaUploadWorkflowFixture f) { return f.service(f.operations, f.assets, ports(f), f.tx); }
    static List<UploadBindingApplicationPort> ports(MediaUploadWorkflowFixture f) {
        return Stream.concat(f.ports.stream(), Stream.of(new ImageUploadBindingApplicationService(f.assets, f.references))).toList();
    }
    static MediaUploadRequest request(ImagePurpose purpose, byte[] bytes) throws Exception {
        var seed = new MediaUploadImageSeed(purpose);
        return new MediaUploadRequest(UUID.randomUUID(), MediaUploadTargetType.IMAGE_CREATE, null, seed.slot(), null,
                new MediaUploadFile("图片+100%.png", "image/png", bytes.length,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))), null, seed);
    }
    static MediaUploadContent content(byte[] bytes, String mime) {
        return new MediaUploadContent() {
            public long size() { return bytes.length; } public String fileName() { return "图片+100%.png"; }
            public String mediaType() { return mime; } public ByteArrayInputStream openStream() { return new ByteArrayInputStream(bytes); }
        };
    }
    static long count(MediaUploadWorkflowFixture f, String table) {
        if (!Set.of("media_asset", "music_track", "surprise_video", "media_upload_operation").contains(table)) throw new IllegalArgumentException();
        return new JdbcTemplate(f.dataSource).queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}

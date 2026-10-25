package com.blogsystem.domain.asset.upload.model.entity;

import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.metadata.model.valobj.*;
import com.blogsystem.domain.asset.upload.model.valobj.*;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaImageUploadModelTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00.123456789Z");
    private static final String HASH = "a".repeat(64);

    @Test void exactAvatarAndArticleLimitsUseDirectTransport() {
        for (var purpose : ImagePurpose.values()) {
            long maximum = purpose == ImagePurpose.AVATAR ? 2097152 : 10485760;
            var ready = ready(purpose, maximum);
            assertEquals(MediaUploadTransport.DIRECT, ready.intent().transportMode());
            assertEquals(1, ready.intent().totalChunks());
            assertEquals(ASSET.toString(), ready.intent().target().id());
            assertEquals(purpose, ready.intent().target().image().purpose());
            assertEquals(MediaUploadException.Reason.TOO_LARGE,
                    assertThrows(MediaUploadException.class, () -> ready(purpose, maximum + 1)).reason());
        }
    }

    @Test void purposeSlotAndRequestVariantsCannotBeMixed() {
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadImageSeed(null));
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadRequest(ID,
                MediaUploadTargetType.IMAGE_CREATE, "client-id", MediaUploadSlot.IMAGE, null,
                file(1), null, new MediaUploadImageSeed(ImagePurpose.ARTICLE)));
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadRequest(ID,
                MediaUploadTargetType.IMAGE_CREATE, null, MediaUploadSlot.IMAGE, 0L,
                file(1), null, new MediaUploadImageSeed(ImagePurpose.ARTICLE)));
        assertThrows(IllegalArgumentException.class, () -> request(ImagePurpose.AVATAR, MediaUploadSlot.IMAGE, 1));
        assertThrows(IllegalArgumentException.class, () -> request(ImagePurpose.COVER, MediaUploadSlot.AVATAR, 1));
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadRequest(ID,
                MediaUploadTargetType.MUSIC_TRACK, "song", MediaUploadSlot.IMAGE, 0L, file(1), null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadRequest(ID,
                MediaUploadTargetType.SURPRISE_CREATE, null, MediaUploadSlot.VIDEO, null,
                new MediaUploadFile("video.mp4", "video/mp4", 1, HASH),
                new MediaUploadVideoSeed("video", true, 0), new MediaUploadImageSeed(ImagePurpose.COVER)));
        assertThrows(IllegalArgumentException.class, () -> MediaUploadPolicy.requireFile(MediaUploadSlot.IMAGE,
                new MediaUploadFile("fake.png", "audio/mpeg", 1, HASH)));
    }

    @Test void fingerprintsFreezeOldMusicAndVideoEncodingAndDistinguishImagePurposes() {
        var music = new MediaUploadRequest(ID, MediaUploadTargetType.MUSIC_TRACK,
                "  Existing-视频-ID  ", MediaUploadSlot.AUDIO, 0L,
                new MediaUploadFile("歌曲+100%.mp3", "audio/mpeg", 16000001, HASH), null);
        var video = new MediaUploadRequest(ID, MediaUploadTargetType.SURPRISE_CREATE,
                null, MediaUploadSlot.VIDEO, null,
                new MediaUploadFile("视频.mp4", "video/mp4", 200, "b".repeat(64)),
                new MediaUploadVideoSeed("惊喜", true, 7));
        // Captured by executing the old production bytecode before any implementation change.
        assertEquals("3bd13cec510385a47324bb1de239c23a2784f3e26957033a791b1ba117583811",
                MediaUploadFingerprint.forUpload(1, music));
        assertEquals("5f988c4f6ed6333f1168e1583dd0c0f64e288ebe0ad307bde7b73146c71e3838",
                MediaUploadFingerprint.forUpload(1, video));
        assertNotEquals(MediaUploadFingerprint.forUpload(1, request(ImagePurpose.ARTICLE, MediaUploadSlot.IMAGE, 1)),
                MediaUploadFingerprint.forUpload(1, request(ImagePurpose.COVER, MediaUploadSlot.IMAGE, 1)));
    }

    @Test void frozenImageIdentityAndPurposeMustMatchRequest() {
        var request = request(ImagePurpose.ARTICLE, MediaUploadSlot.IMAGE, 1);
        assertThrows(IllegalArgumentException.class, () -> MediaUploadPolicy.ready(1, request,
                target(ImagePurpose.ARTICLE, UUID.randomUUID()), "cloudflare_imgbed", new MediaObjectKey("blog"), ASSET, NOW));
        assertThrows(IllegalArgumentException.class, () -> MediaUploadPolicy.ready(1, request,
                target(ImagePurpose.COVER, ASSET), "cloudflare_imgbed", new MediaObjectKey("blog"), ASSET, NOW));
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadTarget(MediaUploadTargetType.IMAGE_CREATE,
                "not-a-uuid", MediaUploadSlot.IMAGE, null, null, null, new MediaUploadImageSeed(ImagePurpose.ARTICLE)));
    }

    @Test void imageReceiptDerivesIdentityAndRejectsInactiveOrWrongKindResources() {
        var resource = resource(ASSET, MediaKind.IMAGE, MediaAssetStatus.ACTIVE);
        MediaUploadResultFacts facts = new ImageUploadResultSnapshot(resource, ImagePurpose.ARTICLE, NOW, NOW);
        assertEquals(MediaUploadTargetType.IMAGE_CREATE, facts.targetType());
        assertEquals(ASSET.toString(), facts.targetId());
        assertEquals(0, facts.targetVersion());
        assertEquals(Instant.parse("2026-10-18T00:00:00.123456Z"), ((ImageUploadResultSnapshot) facts).createdAt());
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadResultSnapshot(
                resource(ASSET, MediaKind.AUDIO, MediaAssetStatus.ACTIVE), ImagePurpose.ARTICLE, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadResultSnapshot(
                resource(ASSET, MediaKind.IMAGE, MediaAssetStatus.DELETED), ImagePurpose.ARTICLE, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new MediaUploadResultSnapshot(MediaUploadTargetType.IMAGE_CREATE,
                ASSET.toString(), 0, "image", null, null, true, 0, null, null, resource, null, null, NOW, NOW));
    }

    @Test void successfulImageOperationRequiresMatchingAssetPurposeAndReceiptLocation() {
        var ready = ready(ImagePurpose.ARTICLE, 1);
        var resource = resource(ASSET, MediaKind.IMAGE, MediaAssetStatus.ACTIVE);
        assertDoesNotThrow(() -> succeeded(ready, new ImageUploadResultSnapshot(resource, ImagePurpose.ARTICLE, NOW, NOW)));
        assertThrows(IllegalArgumentException.class, () -> succeeded(ready,
                new ImageUploadResultSnapshot(resource, ImagePurpose.COVER, NOW, NOW)));
        assertThrows(IllegalArgumentException.class, () -> succeeded(ready,
                new ImageUploadResultSnapshot(resource(UUID.randomUUID(), MediaKind.IMAGE, MediaAssetStatus.ACTIVE), ImagePurpose.ARTICLE, NOW, NOW)));
        var otherLocation = new MediaUploadResourceSnapshot(ASSET, "cloudflare_imgbed", new MediaObjectKey("blog/elsewhere/image.png"),
                MediaKind.IMAGE, MediaAssetStatus.ACTIVE, 0, resource.metadata());
        assertThrows(IllegalArgumentException.class, () -> succeeded(ready,
                new ImageUploadResultSnapshot(otherLocation, ImagePurpose.ARTICLE, NOW, NOW)));
    }

    private static MediaUploadOperation succeeded(MediaUploadOperation ready, MediaUploadResultFacts facts) {
        return new MediaUploadOperation(ready.intent(), new MediaUploadOperation.Progress(MediaUploadStatus.SUCCEEDED,
                MediaUploadStage.PERSIST, null, 1, null, false, 1, 0, 1), new MediaUploadOperation.Lease(null, null, 1),
                new MediaUploadOperation.Outcome(resource(ASSET, MediaKind.IMAGE, MediaAssetStatus.ACTIVE).objectKey(), null, facts, null), 4, NOW, NOW);
    }

    private static MediaUploadResourceSnapshot resource(UUID id, MediaKind kind, MediaAssetStatus status) {
        return new MediaUploadResourceSnapshot(id, "cloudflare_imgbed", new MediaObjectKey("blog/managed-uploads/" + ID + "/图片+100%.png"),
                kind, status, 0, new MediaMetadata("图片+100%.png", "image/png", 1L, HASH, null, null, null));
    }

    private static MediaUploadOperation ready(ImagePurpose purpose, long size) {
        return MediaUploadPolicy.ready(1, request(purpose, slot(purpose), size), target(purpose, ASSET),
                "cloudflare_imgbed", new MediaObjectKey("blog"), ASSET, NOW);
    }

    private static MediaUploadRequest request(ImagePurpose purpose, MediaUploadSlot slot, long size) {
        return new MediaUploadRequest(ID, MediaUploadTargetType.IMAGE_CREATE, null, slot, null,
                file(size), null, new MediaUploadImageSeed(purpose));
    }

    private static MediaUploadTarget target(ImagePurpose purpose, UUID id) {
        return new MediaUploadTarget(MediaUploadTargetType.IMAGE_CREATE, id.toString(), slot(purpose), null, null, null,
                new MediaUploadImageSeed(purpose));
    }

    private static MediaUploadSlot slot(ImagePurpose purpose) {
        return purpose == ImagePurpose.AVATAR ? MediaUploadSlot.AVATAR : MediaUploadSlot.IMAGE;
    }

    private static MediaUploadFile file(long size) {
        return new MediaUploadFile("图片+100%.png", "image/png", size, HASH);
    }
}

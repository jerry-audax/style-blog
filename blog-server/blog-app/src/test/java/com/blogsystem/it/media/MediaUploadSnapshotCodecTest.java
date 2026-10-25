package com.blogsystem.it.media;

import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;

import org.junit.jupiter.api.Assertions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadSnapshotCodecTest {
    private final MediaUploadSnapshotCodec codec=new MediaUploadSnapshotCodec(new ObjectMapper());
    @Test void imageUsesSchema2AndRejectsMixedUnknownOrOversizedFacts() {
        var facts=MediaImageLedgerDdlIntegrationTest.imageResult(MediaImageLedgerDdlIntegrationTest.image(
                com.blogsystem.domain.asset.image.model.valobj.ImagePurpose.ARTICLE,1));
        var encoded=codec.encode(facts);
        assertTrue(encoded.startsWith("{\"schemaVersion\":2,"));
        assertEquals(facts,codec.decode(encoded));
        assertFalse(encoded.contains("\"targetType\""));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded.replace("\"schemaVersion\":2","\"schemaVersion\":1")));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded.replace("\"purpose\":\"ARTICLE\"","\"purpose\":\"ARTICLE\",\"music\":{}")));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded.replace("\"purpose\":\"ARTICLE\"","\"purpose\":\"ARTICLE\",\"purpose\":\"COVER\"")));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded.replace("\"schemaVersion\":2","\"schemaVersion\":2.0")));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded+" ".repeat(65536)));
    }
    @Test void preservesHistoricalIdNullZeroAndMicrosecondTime() {
        var value=snapshot();var json=codec.encode(value);
        assertEquals(value,codec.decode(json));
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertNull(((MediaUploadResultSnapshot)codec.decode(json)).audio().metadata().durationMs());
        assertEquals(0L,((MediaUploadResultSnapshot)codec.decode(json)).audio().metadata().fileSize());
        assertFalse(json.contains("https://"));
    }
    @Test void rejectsUnknownSchemaUnknownFieldsDuplicatesAndInvalidSnapshots() {
        var json=codec.encode(snapshot());
        assertThrows(IllegalArgumentException.class,()->codec.decode(json.replace("\"schemaVersion\":1","\"schemaVersion\":2")));
        assertThrows(IllegalArgumentException.class,()->codec.decode(json.replaceFirst("\\{","{\"token\":\"private\",")));
        assertThrows(IllegalArgumentException.class,()->codec.decode("{\"schemaVersion\":1,\"schemaVersion\":1,\"result\":{}}"));
        assertThrows(IllegalArgumentException.class,()->codec.decode("{}"));
    }
    static MediaUploadResultSnapshot snapshot() {
        var now=Instant.parse("2026-10-14T00:00:00.123456Z");
        var audio=new MediaUploadResourceSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000301"),"cloudflare_imgbed",
                new MediaObjectKey("blog/managed-uploads/fixture/中文+100%.mp3"),MediaKind.AUDIO,MediaAssetStatus.ACTIVE,0,
                new MediaMetadata("中文+100%.mp3","audio/mpeg",0L,null,null,null,null));
        return new MediaUploadResultSnapshot(MediaUploadTargetType.MUSIC_TRACK,"  Existing-视频-ID  ",0,"历史歌","",null,true,0,null,
                audio,null,null,null,now,now);
    }
}

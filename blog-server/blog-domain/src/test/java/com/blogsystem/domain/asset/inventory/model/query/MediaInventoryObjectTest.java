package com.blogsystem.domain.asset.inventory.model.query;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryPreview;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaInventoryObjectTest {
    @Test
    void keepsProviderPathAndUnknownMetadataWithoutInventingValues() {
        MediaInventoryObject item = new MediaInventoryObject(
                MediaKind.AUDIO,
                new MediaObjectKey("blog/music/宇多田/Blue%20LIVE.m4a"),
                new MediaMetadata("Blue LIVE.m4a", "audio/mp4", null, null, null, null, null));

        assertEquals("blog/music/宇多田/Blue%20LIVE.m4a", item.objectKey().value());
        assertEquals("audio/mp4", item.metadata().mimeType());
        assertEquals(null, item.metadata().fileSize());
    }

    @Test
    void rejectsMissingIdentityAndInvalidMetadata() {
        assertThrows(NullPointerException.class, () -> new MediaInventoryObject(
                null, new MediaObjectKey("blog/music/song.mp3"),
                new MediaMetadata("song.mp3", "audio/mpeg", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new MediaInventoryObject(
                MediaKind.IMAGE, new MediaObjectKey("blog/images/a.png"),
                new MediaMetadata("a.png", "image/png", -1L, null, null, null, null)));
    }

    @Test
    void previewRejectsMapTypeThatDoesNotMatchObjectType() {
        MediaInventoryObject object = new MediaInventoryObject(
                MediaKind.IMAGE,
                new MediaObjectKey("blog/images/a.png"),
                new MediaMetadata("a.png", "image/png", null, null, null, null, null));

        assertThrows(IllegalArgumentException.class,
                () -> new MediaInventoryPreview(Map.of(MediaKind.AUDIO, List.of(object))));
    }
}

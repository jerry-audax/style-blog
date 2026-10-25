package com.blogsystem.domain.asset.metadata.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MediaObjectKeyTest {

    @Test
    void keepsValidObjectKeyExactly() {
        MediaObjectKey key =
                new MediaObjectKey("blog/music/宇多田/Blue%20LIVE.mp3");

        assertEquals("blog/music/宇多田/Blue%20LIVE.mp3", key.value());
    }

    @Test
    void acceptsKeyInsideBlogNamespace() {
        MediaObjectKey key = new MediaObjectKey("blog/music/song.mp3");

        assertDoesNotThrow(() -> key.requireWithin("blog"));
    }

    @Test
    void rejectsKeyOutsideBlogNamespace() {
        MediaObjectKey key = new MediaObjectKey("blog-other/music/song.mp3");

        assertThrows(IllegalArgumentException.class,
                () -> key.requireWithin("blog"));
    }

    @Test
    void rejectsUnsafeKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("../secret.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog//song.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog/song.mp3/"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog/./song.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog/dir/../song.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog\\song.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog/song.mp3?download=1"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog/song.mp3#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("blog/song\u0000.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("https://example.com/song.mp3"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("/blog/song.mp3"));
    }

    @Test
    void rejectsBlankOrTooLongKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("  "));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaObjectKey("a".repeat(513)));
    }

    @Test
    void rejectsInvalidNamespaces() {
        MediaObjectKey key = new MediaObjectKey("blog/song.mp3");

        assertThrows(IllegalArgumentException.class, () -> key.requireWithin(null));
        assertThrows(IllegalArgumentException.class, () -> key.requireWithin(""));
        assertThrows(IllegalArgumentException.class, () -> key.requireWithin("blog/"));
    }
}

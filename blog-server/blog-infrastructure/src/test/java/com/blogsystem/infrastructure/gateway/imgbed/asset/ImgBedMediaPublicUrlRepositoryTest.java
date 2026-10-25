package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class ImgBedMediaPublicUrlRepositoryTest {
    @Test void encodesOriginalObjectKey() {
        var urls=new ImgBedMediaPublicUrlRepository(URI.create("https://example.test/"),"blog");
        assertEquals("https://example.test/file/blog/music/%E4%B8%AD%E6%96%87%2B100%25.mp3",urls.publicUrl("cloudflare_imgbed",new MediaObjectKey("blog/music/中文+100%.mp3")));
        assertEquals("https://example.test/file/blog/music/a%252F%20B.mp3",urls.publicUrl("cloudflare_imgbed",new MediaObjectKey("blog/music/a%2F B.mp3")));
    }
    @Test void rejectsUnsafeBasesKeysAndUnknownProvider() {
        for(String url:new String[]{"https://user:secret@example.test","https://example.test/?token=x","https://example.test/#x","https://example.test/prefix","file:///tmp/file"})
            assertThrows(IllegalArgumentException.class,()->new ImgBedMediaPublicUrlRepository(URI.create(url),"blog"));
        var urls=new ImgBedMediaPublicUrlRepository(URI.create("https://example.test"),"blog");
        assertThrows(IllegalArgumentException.class,()->urls.publicUrl("other",new MediaObjectKey("blog/a.mp3")));
        assertThrows(IllegalArgumentException.class,()->urls.publicUrl("cloudflare_imgbed",new MediaObjectKey("elsewhere/a.mp3")));
        assertThrows(IllegalArgumentException.class,()->new ImgBedMediaPublicUrlRepository(URI.create("https://example.test"),"../blog"));
    }
}

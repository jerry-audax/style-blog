package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.StreamingMediaUploadContentVerifier;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamingMediaUploadContentVerifierTest {
    private final StreamingMediaUploadContentVerifier verifier=new StreamingMediaUploadContentVerifier();
    @Test void hashAndCountBeforeProviderUse() throws Exception {
        byte[] bytes="sample audio".getBytes(StandardCharsets.UTF_8);
        MediaUploadFile expected=file(bytes,"audio.mp3","audio/mpeg");
        assertDoesNotThrow(()->verifier.verify(expected,MediaUploadSlot.AUDIO,source(bytes,bytes.length,"audio/mpeg")));
        assertThrows(MediaUploadException.class,()->verifier.verify(expected,MediaUploadSlot.AUDIO,source("other audio!".getBytes(),bytes.length,"audio/mpeg")));
        assertThrows(MediaUploadException.class,()->verifier.verify(expected,MediaUploadSlot.AUDIO,source(new byte[bytes.length-1],bytes.length,"audio/mpeg")));
        assertThrows(MediaUploadException.class,()->verifier.verify(expected,MediaUploadSlot.AUDIO,source(new byte[bytes.length+1],bytes.length,"audio/mpeg")));
        assertThrows(MediaUploadException.class,()->verifier.verify(expected,MediaUploadSlot.AUDIO,source(bytes,0,"audio/mpeg")));
        assertThrows(MediaUploadException.class,()->verifier.verify(expected,MediaUploadSlot.AUDIO,source(bytes,104857601,"audio/mpeg")));
    }
    @Test void rejectsWrongMimeMagicAndMalformedUtf8() throws Exception {
        byte[] png={(byte)137,80,78,71,13,10,26,10};
        assertDoesNotThrow(()->verifier.verify(file(png,"cover.png","image/png"),MediaUploadSlot.COVER,source(png,8,"image/png")));
        assertThrows(MediaUploadException.class,()->verifier.verify(file(png,"cover.jpg","image/jpeg"),MediaUploadSlot.COVER,source(png,8,"image/jpeg")));
        assertThrows(MediaUploadException.class,()->verifier.verify(file(png,"cover.png","image/png"),MediaUploadSlot.COVER,source(png,8,"image/jpeg")));
        for(byte[] lyrics:new byte[][]{{(byte)0xc3,0x28},"<html>not lyrics</html>".getBytes(StandardCharsets.UTF_8)}) {
            assertThrows(MediaUploadException.class,()->verifier.verify(file(lyrics,"lyrics.lrc","text/plain"),MediaUploadSlot.LYRICS,source(lyrics,lyrics.length,"text/plain")));
        }
        byte[] lyrics="[00:01.00]歌词\n".getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(()->verifier.verify(file(lyrics,"lyrics.lrc","text/plain"),MediaUploadSlot.LYRICS,source(lyrics,lyrics.length,"text/plain")));
    }
    @Test void largeSourceNeverMaterializesWholeFile() {
        long length=209715200L;
        var expected=new MediaUploadFile("video.mp4","video/mp4",length,"72abf2ca8f36943ebe2e49ca3a51d409ca5f0bfcffab6c9d25643c17c32889da");
        MediaUploadContent content=new MediaUploadContent(){
            public long size(){return length;} public String fileName(){return "video.mp4";} public String mediaType(){return "video/mp4";}
            public InputStream openStream(){return new InputStream(){
                long remaining=length;
                public int read(){if(remaining==0)return -1;remaining--;return 0;}
                public int read(byte[] b,int offset,int len){assertTrue(len<=65536);if(remaining==0)return -1;int n=(int)Math.min(len,remaining);java.util.Arrays.fill(b,offset,offset+n,(byte)0);remaining-=n;return n;}
                public byte[] readAllBytes(){throw new AssertionError("Must stream");}
            };}
        };
        assertDoesNotThrow(()->verifier.verify(expected,MediaUploadSlot.VIDEO,content));
    }
    static MediaUploadFile file(byte[] b,String name,String mime) throws Exception {
        return new MediaUploadFile(name,mime,b.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)));
    }
    static MediaUploadContent source(byte[] b,long size,String mime){return new MediaUploadContent(){
        public long size(){return size;} public String fileName(){return "unused.mp3";} public String mediaType(){return mime;}
        public InputStream openStream(){return new ByteArrayInputStream(b);}
    };}
}

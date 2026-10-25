package com.blogsystem.domain.asset.image.model.valobj;

import com.blogsystem.domain.asset.image.model.valobj.ImagePath;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;
import com.blogsystem.domain.asset.image.policy.ImageSignaturePolicy;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageUploadTest {
    @Test void allLegacySignaturesRemainValid() {
        byte[][] signatures={new byte[]{(byte)255,(byte)216,(byte)255},"GIF87a".getBytes(),"GIF89a".getBytes(),"RIFF0000WEBP".getBytes()};
        String[] mimes={"image/jpeg","image/gif","image/gif","image/webp"};
        for(int i=0;i<signatures.length;i++) {
            assertEquals(mimes[i],ImageSignaturePolicy.detect(signatures[i]));
            assertEquals(mimes[i],new ImageUpload(signatures[i],mimes[i],ImagePurpose.COVER).mediaType());
        }
        assertNull(ImageSignaturePolicy.detect("RIFF0000WEB".getBytes()));
        assertNull(ImageSignaturePolicy.detect(new byte[0]));
    }
    private final byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};

    @Test
    void acceptsSignatureAndDefensivelyCopiesBytes() {
        var image = new ImageUpload(png, "image/png", ImagePurpose.ARTICLE);
        png[0] = 0;
        assertEquals((byte) 137, image.bytes()[0]);
        image.bytes()[0] = 0;
        assertEquals((byte) 137, image.bytes()[0]);
        assertEquals("png", image.extension());
    }

    @Test
    void rejectsSvgEmptyAndWrongMime() {
        assertThrows(IllegalArgumentException.class, () -> new ImageUpload("<svg/>".getBytes(), "image/svg+xml", ImagePurpose.ARTICLE));
        assertThrows(IllegalArgumentException.class, () -> new ImageUpload(new byte[0], "image/png", ImagePurpose.AVATAR));
        assertThrows(IllegalArgumentException.class, () -> new ImageUpload(png, "image/jpeg", ImagePurpose.COVER));
    }

    @Test
    void avatarLimitIsStricterThanArticleLimit() {
        byte[] large = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png, 0, large, 0, png.length);
        assertThrows(IllegalArgumentException.class, () -> new ImageUpload(large, "image/png", ImagePurpose.AVATAR));
        assertDoesNotThrow(() -> new ImageUpload(large, "image/png", ImagePurpose.ARTICLE));
        assertThrows(IllegalArgumentException.class, () -> new ImageUpload(new byte[10 * 1024 * 1024 + 1], "image/png", ImagePurpose.ARTICLE));
    }

    @Test
    void pathsRejectTraversalUrlsEncodedConfusionAndFolders() {
        for (String path : new String[]{"../a.png", "blog/../a.png", "https://x/a.png", "blog/%2e%2e/a.png", "blog/a\\b.png", "blog/", "/blog/a.png", "blog/a.png?x=1"})
            assertThrows(IllegalArgumentException.class, () -> new ImagePath(path), path);
        assertEquals("blog/正文/图.png", new ImagePath("blog/正文/图.png").value());
        assertThrows(IllegalArgumentException.class, () -> new ImagePath("other/a.png").requireWithin("blog"));
        assertThrows(IllegalArgumentException.class, () -> new ImagePath("blog-else/a.png").requireWithin("blog"));
    }
}

package com.blogsystem.domain.asset.image.policy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Signature classifier shared by legacy and streaming uploads; null means unsupported. */
public final class ImageSignaturePolicy {
    private ImageSignaturePolicy() {}

    public static String detect(byte[] b) {
        if (b == null) return null;
        if (starts(b, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (starts(b, new byte[] {(byte) 255, (byte) 216, (byte) 255})) return "image/jpeg";
        if (starts(b, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || starts(b, "GIF89a".getBytes(StandardCharsets.US_ASCII))) return "image/gif";
        if (b.length >= 12
                && starts(b, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && Arrays.equals(
                        Arrays.copyOfRange(b, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII)))
            return "image/webp";
        return null;
    }

    private static boolean starts(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && Arrays.equals(Arrays.copyOf(b, prefix.length), prefix);
    }
}

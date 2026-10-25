package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.image.policy.ImageSignaturePolicy;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadContentVerifier;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Fixed-memory source verification; no remote request and no whole-file materialization. */
public final class StreamingMediaUploadContentVerifier implements MediaUploadContentVerifier {
    @Override
    public void verify(MediaUploadFile expected, MediaUploadSlot slot, MediaUploadContent source) {
        if (expected == null || slot == null || source == null)
            throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
        if (source.size() > slot.maximumBytes() || expected.size() > slot.maximumBytes())
            throw new MediaUploadException(MediaUploadException.Reason.TOO_LARGE);
        // A renamed local file is permitted on resume: bytes, size and canonical MIME define its
        // identity.
        if (source.size() <= 0
                || source.size() != expected.size()
                || source.mediaType() == null
                || !expected.mediaType().equals(source.mediaType().trim().toLowerCase(Locale.ROOT)))
            throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
        byte[] buffer = new byte[65536], prefix = new byte[12];
        int prefixLength = 0;
        long count = 0;
        ByteArrayOutputStream lyrics =
                slot == MediaUploadSlot.LYRICS ? new ByteArrayOutputStream() : null;
        try (InputStream input = source.openStream()) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (n == 0) throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
                count += n;
                if (count > expected.size() || count > slot.maximumBytes())
                    throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
                hash.update(buffer, 0, n);
                int copy = Math.min(n, 12 - prefixLength);
                if (copy > 0) {
                    System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                    prefixLength += copy;
                }
                if (lyrics != null) lyrics.write(buffer, 0, n);
            }
            if (count != expected.size()) throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
            if (!HexFormat.of().formatHex(hash.digest()).equals(expected.sourceSha256()))
                throw invalid(MediaUploadErrorCode.HASH_MISMATCH);
            if ((slot == MediaUploadSlot.COVER || slot == MediaUploadSlot.IMAGE || slot == MediaUploadSlot.AVATAR)
                    && !expected.mediaType()
                            .equals(
                                    ImageSignaturePolicy.detect(
                                            Arrays.copyOf(prefix, prefixLength))))
                throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
            if (lyrics != null) {
                String text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(lyrics.toByteArray()))
                                .toString();
                if (text.isBlank()
                        || text.indexOf('\0') >= 0
                        || text.stripLeading().startsWith("<"))
                    throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
            }
        } catch (MediaUploadException known) {
            throw known;
        } catch (Exception ignored) {
            throw invalid(MediaUploadErrorCode.INVALID_CONTENT);
        }
    }

    private static MediaUploadException invalid(MediaUploadErrorCode code) {
        return new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST, code);
    }
}

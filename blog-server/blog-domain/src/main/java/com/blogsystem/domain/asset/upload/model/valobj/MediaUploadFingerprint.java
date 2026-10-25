package com.blogsystem.domain.asset.upload.model.valobj;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Typed, named, length-prefixed fields distinguish NULL/0 and concatenation boundaries. */
public final class MediaUploadFingerprint {
    private MediaUploadFingerprint() {}

    public static String forUpload(long actorId, MediaUploadRequest r) {
        var encoding = new Encoding();
        encoding.add("type", "UPLOAD_AND_BIND");
        encoding.add("actor", actorId);
        encoding.add("id", r.operationId());
        encoding.add("targetType", r.targetType());
        encoding.add("targetId", r.targetId());
        encoding.add("slot", r.slot());
        encoding.add("expectedVersion", r.expectedVersion());
        encoding.add("fileName", r.file().fileName());
        encoding.add("mediaType", r.file().mediaType());
        encoding.add("size", r.file().size());
        encoding.add("sourceHash", r.file().sourceSha256());
        encoding.add("videoTitle", r.video() == null ? null : r.video().title());
        encoding.add("videoEnabled", r.video() == null ? null : r.video().enabled());
        encoding.add("videoSortOrder", r.video() == null ? null : r.video().sortOrder());
        encoding.add("sourceOperationId", null);
        if (r.targetType() == MediaUploadTargetType.IMAGE_CREATE)
            encoding.add("imagePurpose", r.image().purpose());
        return encoding.hash();
    }

    public static String forBind(
            long actorId, UUID sourceId, UUID newId, long expectedVersion, long sourceVersion) {
        if (actorId <= 0
                || expectedVersion < 0
                || sourceVersion < 0
                || sourceId == null
                || newId == null)
            throw new IllegalArgumentException("Invalid binding fingerprint input");
        var encoding = new Encoding();
        encoding.add("type", "BIND_UPLOADED");
        encoding.add("actor", actorId);
        encoding.add("sourceOperationId", sourceId);
        encoding.add("newId", newId);
        encoding.add("expectedVersion", expectedVersion);
        encoding.add("sourceVersion", sourceVersion);
        return encoding.hash();
    }

    private static final class Encoding {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream data = new DataOutputStream(bytes);

        void add(String name, Object value) {
            try {
                write(name);
                write(
                        value == null
                                ? "NULL"
                                : value instanceof Number
                                        ? "INTEGER"
                                        : value instanceof Boolean ? "BOOLEAN" : "TEXT");
                if (value != null) write(value.toString());
            } catch (IOException impossible) {
                throw new IllegalStateException("Fingerprint encoding unavailable");
            }
        }

        void write(String text) throws IOException {
            var encoded = text.getBytes(StandardCharsets.UTF_8);
            data.writeInt(encoded.length);
            data.write(encoded);
        }

        String hash() {
            try {
                return HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable");
            }
        }
    }
}

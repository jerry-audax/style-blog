package com.blogsystem.infrastructure.mybatis.codec.asset;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;
import com.blogsystem.domain.asset.upload.model.valobj.ImageUploadResultSnapshot;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Fixed versioned JSONB contract, never an ORM/provider object dump. */
public final class MediaUploadSnapshotCodec {
    private static final int MAX_BYTES = 65536;
    private final ObjectMapper json;

    public MediaUploadSnapshotCodec(ObjectMapper mapper) {
        json =
                mapper.copy()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public String encode(MediaUploadResultFacts facts) {
        if (facts == null) throw new IllegalArgumentException("Committed snapshot required");
        try {
            final String value;
            if (facts instanceof MediaUploadResultSnapshot snapshot)
                value = json.writeValueAsString(new Envelope(1, snapshot));
            else if (facts instanceof ImageUploadResultSnapshot image)
                value = json.writeValueAsString(new ImageEnvelope(2, image));
            else throw new IllegalArgumentException("Unsupported committed snapshot variant");
            requireSize(value);
            return value;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Cannot encode upload snapshot");
        }
    }

    public MediaUploadResultFacts decode(String value) {
        requireSize(value);
        try {
            var tree = json.readTree(value);
            if (tree == null || !tree.isObject() || tree.size() != 2 || !tree.has("result")
                    || !tree.path("schemaVersion").isInt())
                throw new IllegalArgumentException("Unsupported upload snapshot");
            return switch (tree.path("schemaVersion").intValue()) {
                case 1 -> {
                    var envelope = json.treeToValue(tree, Envelope.class);
                    if (envelope.result() == null) throw new IllegalArgumentException("Missing committed facts");
                    yield envelope.result();
                }
                case 2 -> {
                    var envelope = json.treeToValue(tree, ImageEnvelope.class);
                    if (envelope.result() == null) throw new IllegalArgumentException("Missing image facts");
                    yield envelope.result();
                }
                default -> throw new IllegalArgumentException("Unsupported upload snapshot");
            };
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid upload snapshot");
        }
    }

    private static void requireSize(String value) {
        if (value == null
                || value.isBlank()
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid upload snapshot size");
    }

    private record Envelope(int schemaVersion, MediaUploadResultSnapshot result) {}
    private record ImageEnvelope(int schemaVersion, ImageUploadResultSnapshot result) {}
}

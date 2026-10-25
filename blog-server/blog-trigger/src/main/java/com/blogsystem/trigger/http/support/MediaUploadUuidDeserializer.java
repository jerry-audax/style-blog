package com.blogsystem.trigger.http.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.UUID;

public final class MediaUploadUuidDeserializer extends JsonDeserializer<UUID> {
    public static UUID parse(String value) {
        if (value == null
                || !value.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid upload ID");
        return UUID.fromString(value);
    }

    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING)
            throw JsonMappingException.from(parser, "上传编号格式不正确");
        try {
            return parse(parser.getText());
        } catch (IllegalArgumentException invalid) {
            throw JsonMappingException.from(parser, "上传编号格式不正确");
        }
    }
}

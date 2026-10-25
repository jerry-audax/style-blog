package com.blogsystem.trigger.http.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

public final class MediaUploadSizeDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            throw JsonMappingException.from(parser, "资源大小必须为正整数");
        long value = parser.getLongValue();
        if (value <= 0) throw JsonMappingException.from(parser, "资源大小必须为正整数");
        return value;
    }
}

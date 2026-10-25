package com.blogsystem.trigger.http.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/** This field alone is strict; never changes unrelated Jackson coercion rules. */
public final class ExpectedVersionDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            throw JsonMappingException.from(parser, "版本须为非负整数");
        long value = parser.getLongValue();
        if (value < 0) throw JsonMappingException.from(parser, "版本须为非负整数");
        return value;
    }
}

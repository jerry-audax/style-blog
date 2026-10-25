package com.blogsystem.trigger.http.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/** Decimal string end to end: no JavaScript-number round trip. */
public final class OperationVersionDeserializer extends JsonDeserializer<String> {
    public static long parse(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Invalid operation version");
        return Long.parseLong(value);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING)
            throw JsonMappingException.from(parser, "操作版本必须为十进制字符串");
        String value = parser.getText();
        try {
            parse(value);
        } catch (IllegalArgumentException invalid) {
            throw JsonMappingException.from(parser, "操作版本格式不正确");
        }
        return value;
    }
}

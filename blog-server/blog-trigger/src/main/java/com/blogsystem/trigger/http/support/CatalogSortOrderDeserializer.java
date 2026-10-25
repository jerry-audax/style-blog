package com.blogsystem.trigger.http.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

public final class CatalogSortOrderDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            throw JsonMappingException.from(parser, "排序须为0至100000的整数");
        int value = parser.getIntValue();
        if (value < 0 || value > 100000) throw JsonMappingException.from(parser, "排序须为0至100000的整数");
        return value;
    }
}

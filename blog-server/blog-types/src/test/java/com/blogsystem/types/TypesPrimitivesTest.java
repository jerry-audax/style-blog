package com.blogsystem.types;

import com.blogsystem.types.exception.DomainException;
import com.blogsystem.types.model.Identifier;
import com.blogsystem.types.model.PageResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypesPrimitivesTest {
    @Test
    void pageBoundsAndDefensiveCopyRemainStable() {
        var records = new ArrayList<>(List.of("a"));
        var page = new PageResult<>(records, 1, 10, 1);
        records.clear();
        assertEquals(List.of("a"), page.records());
        assertThrows(UnsupportedOperationException.class, () -> page.records().add("b"));
        assertThrows(IllegalArgumentException.class, () -> new PageResult<>(List.of(), 0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageResult<>(List.of(), 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageResult<>(List.of(), 1, 10, -1));
    }

    @Test
    void identifiersRejectNullAndExceptionsPreserveTheirCause() {
        assertEquals("owner", Identifier.requireValue("owner"));
        assertThrows(NullPointerException.class, () -> Identifier.requireValue(null));
        var cause = new IllegalArgumentException("invalid");
        assertSame(cause, new DomainException("failure", cause).getCause());
    }
}

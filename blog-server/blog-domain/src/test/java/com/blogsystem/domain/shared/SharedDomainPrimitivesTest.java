package com.blogsystem.domain.shared;

import org.assertj.core.api.Assertions;

import com.blogsystem.types.model.Identifier;
import com.blogsystem.types.model.PageResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedDomainPrimitivesTest {

    @Test
    void identifierRejectsNullAndPageResultIsImmutable() {
        assertThatThrownBy(() -> new TestId(null))
                .isInstanceOf(NullPointerException.class);

        var result = new PageResult<>(List.of("a"), 1, 10, 1);
        assertThat(result.records()).containsExactly("a");
        assertThatThrownBy(() -> result.records().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private record TestId(Long value) implements Identifier<Long> {
        private TestId {
            Identifier.requireValue(value);
        }
    }
}

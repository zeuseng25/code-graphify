package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseCharsetCheckTest {

    @Test
    void acceptsAl32Utf8() {
        assertThatCode(() -> DatabaseCharsetCheck.requireUtf8("AL32UTF8")).doesNotThrowAnyException();
    }

    @Test
    void rejectsOtherCharacterSetsNamingTheActualOne() {
        assertThatThrownBy(() -> DatabaseCharsetCheck.requireUtf8("WE8ISO8859P9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WE8ISO8859P9")
                .hasMessageContaining("AL32UTF8");
    }
}

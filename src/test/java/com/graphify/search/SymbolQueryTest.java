package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SymbolQueryTest {

    @Test
    void parsesTheFormsPeopleType() {
        assertThat(SymbolQuery.parse("RestTemplate.exchange"))
                .isEqualTo(new SymbolQuery(null, "RESTTEMPLATE", "EXCHANGE", null));
        assertThat(SymbolQuery.parse("RestTemplate#exchange(java.lang.String, java.lang.Class)"))
                .isEqualTo(new SymbolQuery(null, "RESTTEMPLATE", "EXCHANGE", null));
        assertThat(SymbolQuery.parse("  resttemplate "))
                .isEqualTo(new SymbolQuery(null, null, null, "RESTTEMPLATE"));
        assertThat(SymbolQuery.parse("org.springframework.web.client.RestTemplate"))
                .isEqualTo(new SymbolQuery("org.springframework.web.client.RestTemplate", "RESTTEMPLATE", null, null));
        assertThat(SymbolQuery.parse("com.corp.Outer$Inner#run"))
                .isEqualTo(new SymbolQuery("com.corp.Outer.Inner", "INNER", "RUN", null));
        assertThat(SymbolQuery.parse("exchange")).isEqualTo(new SymbolQuery(null, null, null, "EXCHANGE"));
        assertThat(SymbolQuery.parse("PriceFormatter#<init>")).isEqualTo(new SymbolQuery(null, "PRICEFORMATTER", "<INIT>", null));
        assertThat(SymbolQuery.parse("RestTemplate")).isEqualTo(new SymbolQuery(null, null, null, "RESTTEMPLATE"));
    }

    @Test
    void rejectsTextWithNothingToMatch() {
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse(null));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse("#"));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse("(int)"));
    }
}

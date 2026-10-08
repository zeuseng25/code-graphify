package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GavTest {

    @Test
    void aCoordinateNeedsAllThreePartsResolved() {
        assertThat(Gav.of("com.acme", "lib", "1.0-SNAPSHOT")).hasToString("com.acme:lib:1.0-SNAPSHOT");
        assertThat(Gav.of(null, "lib", "1")).isNull();
        assertThat(Gav.of("com.acme", " ", "1")).isNull();
        assertThat(Gav.of("com.acme", "lib", "${revision}")).isNull();
    }
}

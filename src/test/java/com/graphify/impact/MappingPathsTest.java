package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MappingPathsTest {

    private static final String POST = "org.springframework.web.bind.annotation.PostMapping";
    private static final String REQUEST = "org.springframework.web.bind.annotation.RequestMapping";

    @Test
    void readsPositionalAndNamedPaths() {
        assertThat(MappingPaths.path("@RequestMapping(\"/orders\")")).contains("/orders");
        assertThat(MappingPaths.path("@PostMapping(value = \"/checkout\", produces = \"application/json\")"))
                .contains("/checkout");
        assertThat(MappingPaths.path("@GetMapping(produces = \"json\", path = {\"/a\", \"/b\"})")).contains("/a");
        assertThat(MappingPaths.path("@PostMapping")).contains("");
        assertThat(MappingPaths.path("@PostMapping()")).contains("");
        assertThat(MappingPaths.path("@GetMapping(produces = \"json\")")).contains("");
    }

    @Test
    void unresolvedPathsAreEmpty() {
        assertThat(MappingPaths.path("@GetMapping(Paths.ORDERS)")).isEmpty();
        assertThat(MappingPaths.path("@GetMapping(value = Paths.ORDERS)")).isEmpty();
        assertThat(MappingPaths.path("@GetMapping(\"/a\" + \"/b\")")).isEmpty();
        assertThat(MappingPaths.path("@GetMapping(value = \"/a\" + Paths.B)")).isEmpty();
    }

    @Test
    void derivesTheHttpMethod() {
        assertThat(MappingPaths.httpMethod(POST, "@PostMapping(\"/x\")")).isEqualTo("POST");
        assertThat(MappingPaths.httpMethod(REQUEST, "@RequestMapping(value = \"/x\", method = RequestMethod.PUT)"))
                .isEqualTo("PUT");
        assertThat(MappingPaths.httpMethod(REQUEST, "@RequestMapping(\"/x\")")).isNull();
        assertThat(MappingPaths.isMapping(POST)).isTrue();
        assertThat(MappingPaths.isMapping("org.springframework.scheduling.annotation.Scheduled")).isFalse();
    }

    @Test
    void joinsClassAndMethodPaths() {
        assertThat(MappingPaths.join("/orders", "/checkout")).isEqualTo("/orders/checkout");
        assertThat(MappingPaths.join("/orders/", "checkout")).isEqualTo("/orders/checkout");
        assertThat(MappingPaths.join(null, "/checkout")).isEqualTo("/checkout");
        assertThat(MappingPaths.join("/orders", null)).isEqualTo("/orders");
        assertThat(MappingPaths.join(null, null)).isEqualTo("/");
    }
}

package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Pins the facts about the shop fixture that the search and impact tests rely on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopFixtureTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private List<Map<String, Object>> edges(String from, String kind, String to) {
        return jdbc.queryForList("""
                SELECT u.confidence, u.snippet
                  FROM usage u JOIN symbol f ON f.id = u.from_symbol_id JOIN symbol t ON t.id = u.to_symbol_id
                 WHERE f.symbol_key = ? AND u.kind = ? AND t.symbol_key = ?
                """, from, kind, to);
    }

    @Test
    void containsTheEdgesTheImpactScenariosNeed() {
        assertThat(edges("com.shop.api.CheckoutService#label(int)", "CALL", "com.shop.lib.PriceFormatter#format(int)"))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "EXACT"));
        assertThat(edges("com.shop.legacy.LegacyReport#print()", "CALL", "com.shop.lib.PriceFormatter#format/1"))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "NAME_ONLY"));
        assertThat(edges("com.shop.legacy.LegacyReport#build(com.vendor.Client$Builder)", "CALL",
                "com.vendor.Client$Builder#build/0")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "NAME_ONLY"));
        assertThat(edges("com.shop.api.CardGateway#charge(int)", "OVERRIDES", "com.shop.lib.PaymentGateway#charge(int)"))
                .hasSize(1);
        assertThat(edges("com.shop.api.CheckoutService#checkout(int)", "CALL", "com.shop.lib.PaymentGateway#charge(int)"))
                .hasSize(1);
        assertThat(edges("com.shop.api.CardTerminal#pay()", "CALL", "com.shop.api.CardGateway#charge(int)"))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "EXACT"));
        assertThat(edges("com.shop.api.OrderController#checkout()", "ANNOTATION",
                "org.springframework.web.bind.annotation.PostMapping")).singleElement().satisfies(e -> assertThat(e)
                .containsEntry("SNIPPET", "@PostMapping(value = \"/checkout\", produces = \"application/json\")"));
        assertThat(edges("com.shop.api.OrdersApiController#get(java.lang.String)", "OVERRIDES",
                "com.shop.api.OrdersApi#get(java.lang.String)")).hasSize(1);
        assertThat(edges("com.shop.api.OrdersApi#get(java.lang.String)", "ANNOTATION",
                "org.springframework.web.bind.annotation.GetMapping")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("SNIPPET", "@GetMapping(\"/{id}\")"));
        assertThat(edges("com.shop.api.OrderController", "ANNOTATION",
                "org.springframework.web.bind.annotation.RequestMapping")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("SNIPPET", "@RequestMapping(\"/orders\")"));
    }

    @Test
    void sourceDeclarationsWinOverTheJarCopies() {
        assertThat(jdbc.queryForObject("SELECT origin FROM symbol WHERE symbol_key = 'com.shop.lib.PriceFormatter#format(int)'",
                String.class)).isEqualTo("SOURCE");
        assertThat(jdbc.queryForList("SELECT m.path || ':' || m.classpath_mode FROM maven_module m ORDER BY m.path",
                String.class)).containsExactly("shop-api:FULL", "shop-legacy:NONE", "shop-lib:FULL");
    }
}

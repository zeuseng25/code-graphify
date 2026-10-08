package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolSearchTest extends OracleIntegrationTest {

    private static final Paging FIRST_PAGE = new Paging(0, 50);

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    SymbolSearch search;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    @Test
    void classAndMemberFindOverloadsBeforeTheNameOnlyGuess() {
        Page<SymbolHit> page = search.search("PriceFormatter.format", null, null, FIRST_PAGE);

        assertThat(page.items()).extracting(SymbolHit::key, SymbolHit::usageCount, SymbolHit::repositoryCount)
                .containsExactly(
                        tuple("com.shop.lib.PriceFormatter#format(int)", 1L, 1),
                        tuple("com.shop.lib.PriceFormatter#format(java.lang.String)", 0L, 0),
                        tuple("com.shop.lib.PriceFormatter#format/1", 1L, 1));
        assertThat(page.items().getLast().nameOnly()).isTrue();
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void typeSearchIsCaseInsensitiveAndReturnsOnlyTypes() {
        assertThat(search.search("priceFORMATTER", null, null, FIRST_PAGE).items())
                .extracting(SymbolHit::key, SymbolHit::kind)
                .containsExactly(tuple("com.shop.lib.PriceFormatter", SymbolKind.CLASS));
    }

    @Test
    void qualifiedNamesAndSignaturesNarrowTheMatch() {
        assertThat(search.search("com.shop.lib.PriceFormatter#format(int)", null, null, FIRST_PAGE).total())
                .isEqualTo(3);
        assertThat(search.search("com.other.PriceFormatter#format", null, null, FIRST_PAGE).total()).isZero();
        assertThat(search.search("com.shop.lib.priceformatter#format", null, null, FIRST_PAGE).items())
                .extracting(SymbolHit::key).containsExactly("com.shop.lib.PriceFormatter#format(int)",
                        "com.shop.lib.PriceFormatter#format(java.lang.String)", "com.shop.lib.PriceFormatter#format/1");
    }

    @Test
    void memberSearchSpansClassesOrderedByUsage() {
        assertThat(search.search("charge", null, null, FIRST_PAGE).items()).extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PaymentGateway#charge(int)", "com.shop.api.CardGateway#charge(int)");
    }

    @Test
    void kindAndRepositoryFiltersApply() {
        assertThat(search.search("PriceFormatter", SymbolKind.CONSTRUCTOR, null, FIRST_PAGE).items())
                .extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PriceFormatter#<init>()", "com.shop.lib.PriceFormatter#<init>/0");
        assertThat(search.search("PriceFormatter.format", null,
                ShopFixture.repositoryId(jdbc, ShopFixture.LIB_REPO), FIRST_PAGE).items())
                .extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PriceFormatter#format(int)",
                        "com.shop.lib.PriceFormatter#format(java.lang.String)");
    }

    @Test
    void pagesThroughResults() {
        Page<SymbolHit> second = search.search("PriceFormatter.format", null, null, new Paging(1, 1));

        assertThat(second.items()).extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PriceFormatter#format(java.lang.String)");
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.page()).isEqualTo(1);
    }

    @Test
    void metacharactersAreLiteral() {
        assertThat(search.search("%", null, null, FIRST_PAGE).total()).isZero();
        assertThat(search.search("Price_ormatter", null, null, FIRST_PAGE).total()).isZero();
        assertThat(search.search("x' OR '1'='1", null, null, FIRST_PAGE).total()).isZero();
    }
}

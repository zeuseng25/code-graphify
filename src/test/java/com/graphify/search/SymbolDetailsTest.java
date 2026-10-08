package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolDetailsTest extends OracleIntegrationTest {

    private static final Paging FIRST_PAGE = new Paging(0, 50);

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    SymbolDetails details;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private long id(String key) {
        return ShopFixture.symbolId(jdbc, key);
    }

    @Test
    void methodDetailShowsParentDeclarationAndOverrides() {
        SymbolDetail charge = details.find(id("com.shop.lib.PaymentGateway#charge(int)")).orElseThrow();

        assertThat(charge.parent().key()).isEqualTo("com.shop.lib.PaymentGateway");
        assertThat(charge.declarations()).extracting(DeclarationView::repository, DeclarationView::modulePath,
                DeclarationView::filePath).containsExactly(tuple(
                new RepositoryRef(ShopFixture.repositoryId(jdbc, "shop-lib"), "TEST", "shop-lib"), "shop-lib",
                "shop-lib/src/main/java/com/shop/lib/PaymentGateway.java"));
        assertThat(charge.overriddenBy()).extracting(SymbolRef::key).containsExactly("com.shop.api.CardGateway#charge(int)");
        assertThat(charge.overrides()).isEmpty();
    }

    @Test
    void typeDetailShowsMembersAndHierarchy() {
        SymbolDetail gateway = details.find(id("com.shop.lib.PaymentGateway")).orElseThrow();
        SymbolDetail card = details.find(id("com.shop.api.CardGateway")).orElseThrow();

        assertThat(gateway.members()).extracting(SymbolRef::key).containsExactly("com.shop.lib.PaymentGateway#charge(int)");
        assertThat(gateway.subtypes()).extracting(SymbolRef::key).containsExactly("com.shop.api.CardGateway");
        assertThat(card.supertypes()).extracting(SymbolRef::key).containsExactly("com.shop.lib.PaymentGateway");
        assertThat(details.find(-1L)).isEmpty();
        assertThat(details.exists(id("com.shop.lib.PaymentGateway"))).isTrue();
        assertThat(details.exists(-1L)).isFalse();
    }

    @Test
    void usagesAreFilteredAndPaged() {
        long format = id("com.shop.lib.PriceFormatter#format(int)");

        Page<UsageView> all = details.usages(format, Set.of(), Set.of(), null, FIRST_PAGE);
        assertThat(all.items()).singleElement().satisfies(u -> {
            assertThat(u.from().key()).isEqualTo("com.shop.api.CheckoutService#label(int)");
            assertThat(u.kind()).isEqualTo(UsageKind.CALL);
            assertThat(u.confidence()).isEqualTo(Confidence.EXACT);
            assertThat(u.repository())
                    .isEqualTo(new RepositoryRef(ShopFixture.repositoryId(jdbc, "shop-api"), "TEST", "shop-api"));
            assertThat(u.snippet()).isEqualTo("return formatter.format(cents);");
        });
        assertThat(details.usages(format, EnumSet.of(Confidence.NAME_ONLY), Set.of(), null, FIRST_PAGE).total()).isZero();
        assertThat(details.usages(format, Set.of(), Set.of(), ShopFixture.repositoryId(jdbc, "shop-lib"),
                FIRST_PAGE).total()).isZero();
        long charge = id("com.shop.lib.PaymentGateway#charge(int)");
        assertThat(details.usages(charge, Set.of(), EnumSet.of(UsageKind.OVERRIDES), null, FIRST_PAGE).items())
                .extracting(u -> u.from().key()).containsExactly("com.shop.api.CardGateway#charge(int)");
    }

    @Test
    void repositoriesWithTheSameSlugInDifferentProjectsStayApart() {
        long format = id("com.shop.lib.PriceFormatter#format(int)");
        long apiRepo = ShopFixture.repositoryId(jdbc, "shop-api");
        jdbc.update("""
                INSERT INTO scm_repository (connection_id, project_key, slug, clone_url)
                SELECT connection_id, 'OTHER', slug, clone_url || '.other' FROM scm_repository WHERE id = ?
                """, apiRepo);
        long twinRepo = jdbc.queryForObject("SELECT id FROM scm_repository WHERE project_key = 'OTHER'", Long.class);
        try {
            jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, 'shop-api', 'FULL')",
                    twinRepo);
            long twinModule = jdbc.queryForObject("SELECT id FROM maven_module WHERE repo_id = ?", Long.class,
                    twinRepo);
            jdbc.update("""
                    INSERT INTO usage (from_symbol_id, to_symbol_id, module_id, kind, confidence, file_path, line_no,
                                       column_no, snippet)
                    SELECT from_symbol_id, to_symbol_id, ?, kind, confidence, file_path, line_no, column_no, snippet
                      FROM usage WHERE to_symbol_id = ?
                    """, twinModule, format);

            UsageSummary summary = details.summary(format);

            assertThat(summary.repositories()).isEqualTo(2);
            assertThat(summary.byRepository()).extracting(UsageSummary.RepositoryUsage::repository).containsExactly(
                    new RepositoryRef(twinRepo, "OTHER", "shop-api"), new RepositoryRef(apiRepo, "TEST", "shop-api"));
            assertThat(details.usages(format, Set.of(), Set.of(), twinRepo, FIRST_PAGE).items())
                    .extracting(u -> u.repository().id()).containsExactly(twinRepo);
            assertThat(details.usages(format, Set.of(), Set.of(), null, FIRST_PAGE).items())
                    .extracting(u -> u.repository().projectKey()).containsExactly("OTHER", "TEST");
        } finally {
            jdbc.update("DELETE FROM usage WHERE module_id IN (SELECT id FROM maven_module WHERE repo_id = ?)",
                    twinRepo);
            jdbc.update("DELETE FROM maven_module WHERE repo_id = ?", twinRepo);
            jdbc.update("DELETE FROM scm_repository WHERE id = ?", twinRepo);
        }
    }

    @Test
    void summaryGroupsUsagesByRepositoryModuleAndClass() {
        UsageSummary summary = details.summary(id("com.shop.lib.PaymentGateway#charge(int)"));

        assertThat(summary.usages()).isEqualTo(2);
        assertThat(summary.repositories()).isEqualTo(1);
        assertThat(summary.byRepository()).singleElement().satisfies(repo -> {
            assertThat(repo.repository().slug()).isEqualTo("shop-api");
            assertThat(repo.modules()).singleElement().satisfies(module -> {
                assertThat(module.modulePath()).isEqualTo("shop-api");
                assertThat(module.classes()).extracting(UsageSummary.ClassUsage::classFqn, UsageSummary.ClassUsage::usages)
                        .containsExactly(tuple("com.shop.api.CardGateway", 1L), tuple("com.shop.api.CheckoutService", 1L));
            });
        });
    }
}

package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.Confidence;
import com.graphify.repository.RepositoryRef;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** The spec §5 scenarios end to end on the shop fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpactServiceTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    ImpactService impact;

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
    void behaviorChangeOfALibraryMethodReachesEndpointsAcrossRepositories() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.lib.PriceFormatter#format(int)")), null, 3, null, null));

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::level).contains(
                tuple("com.shop.api.CheckoutService#label(int)", 1),
                tuple("com.shop.legacy.LegacyReport#print()", 1),
                tuple("com.shop.api.CheckoutService#checkout(int)", 2),
                tuple("com.shop.api.OrderController#checkout()", 3),
                tuple("com.shop.api.NightlyJob#run()", 3),
                tuple("com.shop.api.OrdersApiController#get(java.lang.String)", 3));
        assertThat(result.entryPoints())
                .extracting(EntryPoint::key, EntryPoint::label, EntryPoint::httpMethod, EntryPoint::httpPath)
                .containsExactly(
                        tuple("com.shop.api.NightlyJob#run()", "Zamanlanmış görev", null, null),
                        tuple("com.shop.api.OrderController#checkout()", "HTTP", "POST", "/orders/checkout"),
                        tuple("com.shop.api.OrdersApiController#get(java.lang.String)", "HTTP", "GET",
                                "/v2/orders/{id}"));
        assertThat(result.entryPoints()).last().satisfies(e -> {
            assertThat(e.repository().slug()).isEqualTo(ShopFixture.API_REPO);
            assertThat(e.modulePath()).isEqualTo("shop-api");
            assertThat(e.symbolId()).isEqualTo(id("com.shop.api.OrdersApiController#get(java.lang.String)"));
        });
        assertThat(result.summary().repositories()).isEqualTo(1);
        assertThat(result.summary().partialClasspathRepositories()).extracting(RepositoryRef::id, RepositoryRef::slug)
                .containsExactly(tuple(ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO), ShopFixture.API_REPO));
        assertThat(result.entryPoints()).extracting(e -> e.repository().projectKey()).containsOnly("TEST");
        assertThat(result.staleness()).extracting(RepoState::lastIndexedCommit).containsOnly("api-1");
        assertThat(result.versionWarnings()).isEmpty();
        assertThat(result.entryPoints()).extracting(EntryPoint::key, EntryPoint::http).containsExactly(
                tuple("com.shop.api.NightlyJob#run()", false), tuple("com.shop.api.OrderController#checkout()", true),
                tuple("com.shop.api.OrdersApiController#get(java.lang.String)", true));
    }

    @Test
    void exactOnlyDropsTheNameOnlyGuess() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.lib.PriceFormatter#format(int)")), null, 1, Set.of(Confidence.EXACT), null));

        assertThat(result.nodes()).extracting(ImpactNode::key).doesNotContain("com.shop.legacy.LegacyReport#print()");
        assertThat(result.summary().partialClasspathRepositories()).isEmpty();
    }

    @Test
    void implementationChangeReachesCallersThroughDispatch() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.api.CardGateway#charge(int)")), null, 2, null, null));

        assertThat(result.edges()).filteredOn(ImpactEdge::viaDispatch).extracting(ImpactEdge::level)
                .containsExactly(1);
        assertThat(result.nodes()).extracting(ImpactNode::key)
                .contains("com.shop.api.CheckoutService#checkout(int)", "com.shop.api.OrderController#checkout()");
    }

    @Test
    void signatureChangeOfAnInterfaceMethodListsImplementationsAndDirectCallers() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.lib.PaymentGateway#charge(int)")), ChangeType.SIGNATURE, null, null, null));

        assertThat(result.edges()).extracting(e -> e.kind().name(), ImpactEdge::fromSymbolId, ImpactEdge::level)
                .containsExactlyInAnyOrder(
                        tuple("CALL", id("com.shop.api.CheckoutService#checkout(int)"), 1),
                        tuple("OVERRIDES", id("com.shop.api.CardGateway#charge(int)"), 1),
                        tuple("CALL", id("com.shop.api.CardTerminal#pay()"), 1));
    }

    @Test
    void rulesAndEntryPointLabelsAreReadFromTheDatabase() {
        long format = id("com.shop.lib.PriceFormatter#format(int)");
        try {
            jdbc.update("UPDATE impact_relation_rule SET propagates = 0 WHERE usage_kind = 'CALL'");
            jdbc.update("UPDATE entry_point_annotation SET enabled = 0 "
                    + "WHERE annotation_fqn = 'org.springframework.scheduling.annotation.Scheduled'");

            ImpactResult noPropagation = impact.analyze(new ImpactRequest(List.of(format), null, 3, null, null));
            assertThat(noPropagation.nodes()).extracting(ImpactNode::level).containsOnly(0, 1);

            jdbc.update("UPDATE impact_relation_rule SET propagates = 1 WHERE usage_kind = 'CALL'");
            ImpactResult noJob = impact.analyze(new ImpactRequest(List.of(format), null, 3, null, null));
            assertThat(noJob.entryPoints()).extracting(EntryPoint::key)
                    .containsExactly("com.shop.api.OrderController#checkout()",
                            "com.shop.api.OrdersApiController#get(java.lang.String)");
        } finally {
            jdbc.update("UPDATE impact_relation_rule SET propagates = 1 WHERE usage_kind = 'CALL'");
            jdbc.update("UPDATE entry_point_annotation SET enabled = 1");
        }
    }
}

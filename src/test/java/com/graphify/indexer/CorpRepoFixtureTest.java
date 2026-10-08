package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The cases graphify missed or merged in the spike (spec §2) must all resolve exactly. */
class CorpRepoFixtureTest {

    private static final Path REPO = fixture();
    private static final String SERVICE = "com.corp.order.OrderService";
    private static final String FORMAT_STRING = "com.corp.common.MoneyUtil#format(java.lang.String)";
    private static final String FORMAT_INT = "com.corp.common.MoneyUtil#format(int)";

    @Test
    void resolvesEveryCaseGraphifyMissed() {
        IndexResult result = index(50);

        assertExact(usage(result, SERVICE + "#a()", UsageKind.CALL, FORMAT_STRING), 17);
        assertExact(usage(result, SERVICE + "#b()", UsageKind.CALL, FORMAT_INT), 18);
        assertExact(usage(result, SERVICE + "#c()", UsageKind.CALL, FORMAT_INT), 19);
        assertExact(usage(result, SERVICE + "#d()", UsageKind.CALL, "com.corp.common.MoneyUtil#instance()"), 20);
        assertExact(usage(result, SERVICE + "#d()", UsageKind.CALL, FORMAT_STRING), 20);
        assertExact(usage(result, SERVICE + "#e(java.util.List)", UsageKind.CALL, FORMAT_STRING), 21);
        assertExact(usage(result, SERVICE + "#f()", UsageKind.CALL, "com.corp.common.DateUtil#format(java.lang.String)"), 22);
        assertExact(usage(result, SERVICE + "#g()", UsageKind.CALL, "com.corp.common.PaymentGateway#pay(int)"), 23);
    }

    @Test
    void recordsInterfaceDispatchFieldWritesAndClassLevelInstantiation() {
        IndexResult result = index(50);

        usage(result, "com.corp.order.CardPaymentGateway", UsageKind.IMPLEMENTS, "com.corp.common.PaymentGateway");
        usage(result, "com.corp.order.CardPaymentGateway#pay(int)", UsageKind.OVERRIDES,
                "com.corp.common.PaymentGateway#pay(int)");
        usage(result, "com.corp.order.CardPaymentGateway#pay(int)", UsageKind.ANNOTATION, "java.lang.Override");
        usage(result, SERVICE + "#<init>(com.corp.common.PaymentGateway)", UsageKind.FIELD_WRITE,
                SERVICE + ".gateway");
        assertExact(usage(result, SERVICE, UsageKind.INSTANTIATION, "com.corp.common.MoneyUtil#<init>()"), 9);
        assertThat(result.declarations()).anySatisfy(d -> {
            assertThat(d.symbolKey()).isEqualTo(FORMAT_INT);
            assertThat(d.modulePath()).isEqualTo("common-lib");
            assertThat(d.filePath()).isEqualTo("common-lib/src/main/java/com/corp/common/MoneyUtil.java");
            assertThat(d.line()).isEqualTo(5);
        });
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void resultsDoNotDependOnParseBatchSize() {
        IndexResult oneFilePerBatch = index(1);
        IndexResult allInOneBatch = index(50);

        assertThat(oneFilePerBatch.usages()).containsExactlyInAnyOrderElementsOf(allInOneBatch.usages());
        assertThat(oneFilePerBatch.symbols()).containsExactlyInAnyOrderElementsOf(allInOneBatch.symbols());
        assertThat(oneFilePerBatch.declarations()).containsExactlyInAnyOrderElementsOf(allInOneBatch.declarations());
    }

    private static void assertExact(Usage usage, int line) {
        assertThat(usage.confidence()).isEqualTo(Confidence.EXACT);
        assertThat(usage.line()).isEqualTo(line);
        assertThat(usage.modulePath()).isEqualTo("order-service");
    }

    private static IndexResult index(int parseBatchSize) {
        List<ModuleSource> modules = List.of(module("common-lib"), module("order-service"));
        return new JavaRepositoryIndexer().index(
                new IndexRequest(REPO, modules, new IndexerOptions(parseBatchSize, 300)));
    }

    private static ModuleSource module(String name) {
        return new ModuleSource(name, List.of(REPO.resolve(name).resolve("src/main/java")), List.of());
    }

    private static Path fixture() {
        try {
            return Path.of(CorpRepoFixtureTest.class.getResource("/fixtures/corp-repo").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}

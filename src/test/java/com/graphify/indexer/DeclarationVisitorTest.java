package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.symbol;
import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarationVisitorTest {

    @TempDir
    Path dir;

    @Test
    void declaresTypesMembersAndFieldsWithTheLineOfTheirName() throws Exception {
        IndexResult result = TempRepo.at(dir).java("lib", "com/acme/Money.java", """
                package com.acme;

                public class Money {
                    public static final String EUR = "EUR";
                    private int cents;

                    public Money(int cents) { this.cents = cents; }

                    public int cents() { return cents; }

                    public enum Currency { TRY, USD }
                }
                """).index();

        String file = "lib/src/main/java/com/acme/Money.java";
        assertThat(result.declarations())
                .extracting(Declaration::symbolKey, Declaration::modulePath, Declaration::filePath, Declaration::line)
                .contains(
                        tuple("com.acme.Money", "lib", file, 3),
                        tuple("com.acme.Money.EUR", "lib", file, 4),
                        tuple("com.acme.Money.cents", "lib", file, 5),
                        tuple("com.acme.Money#<init>(int)", "lib", file, 7),
                        tuple("com.acme.Money#cents()", "lib", file, 9),
                        tuple("com.acme.Money$Currency", "lib", file, 11),
                        tuple("com.acme.Money$Currency.TRY", "lib", file, 11),
                        tuple("com.acme.Money$Currency.USD", "lib", file, 11));
        assertThat(symbol(result, "com.acme.Money$Currency"))
                .extracting(Symbol::kind, Symbol::parentKey)
                .containsExactly(SymbolKind.ENUM, "com.acme.Money");
    }

    @Test
    void recordsExtendsImplementsAndOverridesAcrossTheSupertypeGraph() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("lib", "com/acme/Repo.java", """
                        package com.acme;
                        public interface Repo<T> extends AutoCloseable { void save(T item); }
                        """)
                .java("lib", "com/acme/BaseRepo.java", """
                        package com.acme;
                        public abstract class BaseRepo { public abstract void flush(); }
                        """)
                .java("lib", "com/acme/OrderRepo.java", """
                        package com.acme;
                        public class OrderRepo extends BaseRepo implements Repo<String>, Comparable<OrderRepo> {
                            public void save(String item) {}
                            public void flush() {}
                            public int compareTo(OrderRepo other) { return 0; }
                            public void close() {}
                        }
                        """)
                .index();

        assertThat(usage(result, "com.acme.OrderRepo", UsageKind.EXTENDS, "com.acme.BaseRepo").confidence())
                .isEqualTo(Confidence.EXACT);
        usage(result, "com.acme.OrderRepo", UsageKind.IMPLEMENTS, "com.acme.Repo");
        usage(result, "com.acme.OrderRepo", UsageKind.IMPLEMENTS, "java.lang.Comparable");
        usage(result, "com.acme.Repo", UsageKind.EXTENDS, "java.lang.AutoCloseable");
        usage(result, "com.acme.OrderRepo#save(java.lang.String)", UsageKind.OVERRIDES,
                "com.acme.Repo#save(java.lang.Object)");
        usage(result, "com.acme.OrderRepo#flush()", UsageKind.OVERRIDES, "com.acme.BaseRepo#flush()");
        usage(result, "com.acme.OrderRepo#compareTo(com.acme.OrderRepo)", UsageKind.OVERRIDES,
                "java.lang.Comparable#compareTo(java.lang.Object)");
        usage(result, "com.acme.OrderRepo#close()", UsageKind.OVERRIDES, "java.lang.AutoCloseable#close()");
    }

    @Test
    void anonymousClassesAreAttributedToTheEnclosingMethod() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/acme/Jobs.java", """
                package com.acme;
                class Jobs {
                    Runnable job() {
                        return new Runnable() { public void run() {} };
                    }
                }
                """).index();

        usage(result, "com.acme.Jobs#job()", UsageKind.IMPLEMENTS, "java.lang.Runnable");
        usage(result, "com.acme.Jobs#job()", UsageKind.OVERRIDES, "java.lang.Runnable#run()");
        assertThat(result.symbols()).extracting(Symbol::key).noneMatch(key -> key.contains("$1"));
    }

    @Test
    void sameClassInTwoModulesIsOneSymbolWithTwoDeclarations() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("a", "com/acme/Dup.java", "package com.acme; public class Dup {}")
                .java("b", "com/acme/Dup.java", "package com.acme; public class Dup {}")
                .index();

        assertThat(result.symbols()).extracting(Symbol::key).containsOnlyOnce("com.acme.Dup");
        assertThat(result.declarations())
                .filteredOn(d -> d.symbolKey().equals("com.acme.Dup"))
                .extracting(Declaration::modulePath)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void missingSuperclassFallsBackToTheImportedName() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/acme/Controller.java", """
                package com.acme;
                import org.vendor.web.BaseController;
                public class Controller extends BaseController {}
                """).index();

        assertThat(usage(result, "com.acme.Controller", UsageKind.EXTENDS, "org.vendor.web.BaseController")
                .confidence()).isEqualTo(Confidence.NAME_ONLY);
    }

    @Test
    void rejectsNonPositiveOptions() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new IndexerOptions(0, 10));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new IndexerOptions(10, 0));
    }
}

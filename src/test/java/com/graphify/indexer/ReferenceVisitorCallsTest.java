package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static com.graphify.indexer.IndexResults.usages;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceVisitorCallsTest {

    private static final String MONEY_UTIL = """
            package com.corp.common;
            public class MoneyUtil {
                public String format(String amount) { return amount; }
                public String format(int amount) { return String.valueOf(amount); }
                public static MoneyUtil instance() { return new MoneyUtil(); }
            }
            """;

    private static final Map<String, String> VENDOR_LIBRARY = Map.of(
            "com/vendor/http/RestClient.java", """
                    package com.vendor.http;
                    public class RestClient {
                        public <T> T exchange(String url, Class<T> type) { return null; }
                        public <T> T exchange(String url, TypeRef<T> type) { return null; }
                    }
                    """,
            "com/vendor/http/TypeRef.java", """
                    package com.vendor.http;
                    public abstract class TypeRef<T> {}
                    """);

    private static final String API = """
            package com.corp.order;
            import com.vendor.http.RestClient;
            class Api {
                String get() {
                    RestClient client = new RestClient();
                    return client.exchange("u", String.class);
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void resolvesOverloadsVarChainedStaticImportedCallsAndLambdas() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("lib", "com/corp/common/MoneyUtil.java", MONEY_UTIL)
                .java("app", "com/corp/order/Calls.java", """
                        package com.corp.order;
                        import static com.corp.common.MoneyUtil.instance;
                        import com.corp.common.MoneyUtil;
                        import java.util.List;
                        class Calls {
                            private final MoneyUtil money = new MoneyUtil();
                            void a() { money.format("10"); }
                            void b() { money.format(5); }
                            void c() { var m = new MoneyUtil(); m.format(1); }
                            void d() { MoneyUtil.instance().format("x"); }
                            void e(List<String> xs) { xs.forEach(s -> money.format(s)); }
                            void h() { instance(); }
                        }
                        """)
                .index();

        String formatString = "com.corp.common.MoneyUtil#format(java.lang.String)";
        String formatInt = "com.corp.common.MoneyUtil#format(int)";
        assertThat(usage(result, "com.corp.order.Calls#a()", UsageKind.CALL, formatString))
                .extracting(Usage::confidence, Usage::line, Usage::snippet)
                .containsExactly(Confidence.EXACT, 7, "void a() { money.format(\"10\"); }");
        usage(result, "com.corp.order.Calls#h()", UsageKind.CALL, "com.corp.common.MoneyUtil#instance()");
        usage(result, "com.corp.order.Calls#b()", UsageKind.CALL, formatInt);
        usage(result, "com.corp.order.Calls#c()", UsageKind.CALL, formatInt);
        usage(result, "com.corp.order.Calls#c()", UsageKind.INSTANTIATION, "com.corp.common.MoneyUtil#<init>()");
        usage(result, "com.corp.order.Calls#d()", UsageKind.CALL, "com.corp.common.MoneyUtil#instance()");
        usage(result, "com.corp.order.Calls#d()", UsageKind.CALL, formatString);
        usage(result, "com.corp.order.Calls#e(java.util.List)", UsageKind.CALL, formatString);
        usage(result, "com.corp.order.Calls", UsageKind.INSTANTIATION, "com.corp.common.MoneyUtil#<init>()");
    }

    @Test
    void usagesAreAttributedToNearestNamedMemberOrClass() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Scopes.java", """
                package p;
                class Scopes {
                    static final int SIZE = compute();
                    static { compute(); }
                    static int compute() { return 1; }
                    void run() {
                        Runnable r = new Runnable() { public void run() { compute(); } };
                        java.util.function.IntSupplier s = () -> compute();
                    }
                }
                """).index();

        assertThat(usages(result, "p.Scopes", UsageKind.CALL, "p.Scopes#compute()"))
                .extracting(Usage::line).containsExactlyInAnyOrder(3, 4);
        assertThat(usages(result, "p.Scopes#run()", UsageKind.CALL, "p.Scopes#compute()"))
                .extracting(Usage::line).containsExactlyInAnyOrder(7, 8);
    }

    @Test
    void recordsMethodReferencesAndConstructorChaining() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("lib", "com/corp/common/MoneyUtil.java", MONEY_UTIL)
                .java("app", "com/corp/order/Refs.java", """
                        package com.corp.order;
                        import com.corp.common.MoneyUtil;
                        import java.util.function.Function;
                        import java.util.function.Supplier;
                        class Refs {
                            Refs() { this(1); }
                            Refs(int x) { super(); }
                            void refs(MoneyUtil money) {
                                Function<String, String> f = money::format;
                                Supplier<MoneyUtil> s = MoneyUtil::new;
                            }
                        }
                        """)
                .index();

        String refs = "com.corp.order.Refs#refs(com.corp.common.MoneyUtil)";
        usage(result, refs, UsageKind.METHOD_REF, "com.corp.common.MoneyUtil#format(java.lang.String)");
        usage(result, refs, UsageKind.METHOD_REF, "com.corp.common.MoneyUtil#<init>()");
        usage(result, "com.corp.order.Refs#<init>()", UsageKind.CALL, "com.corp.order.Refs#<init>(int)");
        usage(result, "com.corp.order.Refs#<init>(int)", UsageKind.CALL, "java.lang.Object#<init>()");
    }

    @Test
    void thirdPartyCallIsExactWhenTheJarIsOnTheClasspath() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "vendor", VENDOR_LIBRARY, Set.of());

        IndexResult result = TempRepo.at(dir.resolve("repo"))
                .java("app", "com/corp/order/Api.java", API)
                .classpath("app", jar)
                .index();

        assertThat(usage(result, "com.corp.order.Api#get()", UsageKind.CALL,
                "com.vendor.http.RestClient#exchange(java.lang.String,java.lang.Class)").confidence())
                .isEqualTo(Confidence.EXACT);
    }

    @Test
    void thirdPartyCallIsRecoveredWhenTheJarIsIncomplete() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "vendor", VENDOR_LIBRARY,
                Set.of("com/vendor/http/TypeRef.class"));

        IndexResult result = TempRepo.at(dir.resolve("repo"))
                .java("app", "com/corp/order/Api.java", API)
                .classpath("app", jar)
                .index();

        assertThat(result.usages())
                .filteredOn(u -> u.kind() == UsageKind.CALL && u.toKey().startsWith("com.vendor.http.RestClient#exchange("))
                .singleElement()
                .extracting(Usage::confidence)
                .isEqualTo(Confidence.RECOVERED);
    }

    @Test
    void thirdPartyCallIsNameOnlyThroughImportsWhenTheJarIsMissing() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/order/Api.java", API).index();

        assertThat(usage(result, "com.corp.order.Api#get()", UsageKind.CALL,
                "com.vendor.http.RestClient#exchange/2").confidence()).isEqualTo(Confidence.NAME_ONLY);
        assertThat(usage(result, "com.corp.order.Api#get()", UsageKind.INSTANTIATION,
                "com.vendor.http.RestClient#<init>/0").confidence()).isEqualTo(Confidence.NAME_ONLY);
        assertThat(result.usages()).noneMatch(u -> u.toKey().startsWith("com.corp.order.RestClient"));
    }

    @Test
    void anonymousSubclassInstantiatesTheMatchingSuperConstructor() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Base.java", """
                        package p;
                        public abstract class Base {
                            protected Base() {}
                            protected Base(String name) {}
                            protected Base(int size) {}
                        }
                        """)
                .java("app", "p/Anon.java", """
                        package p;
                        class Anon {
                            Object make() { return new Base("x") { }; }
                            Runnable task() { return new Runnable() { public void run() {} }; }
                        }
                        """)
                .index();

        assertThat(usage(result, "p.Anon#make()", UsageKind.INSTANTIATION, "p.Base#<init>(java.lang.String)").confidence())
                .isEqualTo(Confidence.EXACT);
        assertThat(result.usages()).filteredOn(u -> u.kind() == UsageKind.INSTANTIATION)
                .extracting(Usage::toKey)
                .containsExactly("p.Base#<init>(java.lang.String)");
    }

    @Test
    void anonymousSubclassOfMissingBaseIsNameOnlyConstructor() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Anon.java", """
                package p;
                import org.vendor.Base;
                class Anon { Object make() { return new Base("x") { }; } }
                """).index();

        assertThat(usage(result, "p.Anon#make()", UsageKind.INSTANTIATION, "org.vendor.Base#<init>/1").confidence())
                .isEqualTo(Confidence.NAME_ONLY);
    }

    @Test
    void unresolvedStaticImportCallIsNameOnlyOnTheImportedClass() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/order/Fmt.java", """
                package com.corp.order;
                import static com.corp.common.MoneyUtil.format;
                class Fmt { String f() { return format(5); } }
                """).index();

        assertThat(usage(result, "com.corp.order.Fmt#f()", UsageKind.CALL, "com.corp.common.MoneyUtil#format/1")
                .confidence()).isEqualTo(Confidence.NAME_ONLY);
    }

    @Test
    void unresolvedInheritedCallIsNameOnlyOnTheMissingSuperclass() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/order/Child.java", """
                package com.corp.order;
                import org.vendor.Base;
                class Child extends Base { void run() { helper(); } }
                """).index();

        assertThat(usage(result, "com.corp.order.Child#run()", UsageKind.CALL, "org.vendor.Base#helper/0")
                .confidence()).isEqualTo(Confidence.NAME_ONLY);
    }

    @Test
    void enumConstantsInstantiateTheirConstructor() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/E.java", """
                package p;
                enum E {
                    A(1), B;
                    E(int x) {}
                    E() {}
                }
                """).index();

        assertThat(usage(result, "p.E", UsageKind.INSTANTIATION, "p.E#<init>(int)").confidence())
                .isEqualTo(Confidence.EXACT);
        assertThat(usage(result, "p.E", UsageKind.INSTANTIATION, "p.E#<init>()").line()).isEqualTo(3);
    }
}

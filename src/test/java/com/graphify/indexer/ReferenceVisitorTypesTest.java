package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static com.graphify.indexer.IndexResults.usages;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceVisitorTypesTest {

    @TempDir
    Path dir;

    @Test
    void distinguishesFieldReadsFromWrites() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Counter.java", """
                package p;
                class Counter {
                    static final int LIMIT = 10;
                    int count;
                    Counter other;
                    void touch() {
                        count++;
                        this.count = LIMIT;
                        other.count += 1;
                        int seen = other.count;
                        int max = Counter.LIMIT;
                    }
                }
                """).index();

        String touch = "p.Counter#touch()";
        assertThat(usages(result, touch, UsageKind.FIELD_WRITE, "p.Counter.count"))
                .extracting(Usage::line).containsExactlyInAnyOrder(7, 8, 9);
        assertThat(usages(result, touch, UsageKind.FIELD_READ, "p.Counter.count"))
                .extracting(Usage::line).containsExactly(10);
        assertThat(usages(result, touch, UsageKind.FIELD_READ, "p.Counter.LIMIT"))
                .extracting(Usage::line).containsExactlyInAnyOrder(8, 11);
        assertThat(usages(result, touch, UsageKind.FIELD_READ, "p.Counter.other"))
                .extracting(Usage::line).containsExactlyInAnyOrder(9, 10);
    }

    @Test
    void typeReferencesSkipVarConstructedTypesAndSupertypes() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Types.java", """
                package p;
                import java.util.ArrayList;
                import java.util.List;
                class Types extends Base implements Marker {
                    List<Item> items = new ArrayList<Item>();
                    Item first(List<Item> in) { var copy = new ArrayList<>(in); return (Item) copy.get(0); }
                }
                class Base {}
                interface Marker {}
                class Item {}
                """).index();

        assertThat(usages(result, "p.Types", UsageKind.TYPE_REF, "java.util.List")).extracting(Usage::line)
                .containsExactly(5);
        assertThat(usages(result, "p.Types", UsageKind.TYPE_REF, "p.Item")).hasSize(2);
        String first = "p.Types#first(java.util.List)";
        assertThat(usages(result, first, UsageKind.TYPE_REF, "p.Item")).hasSize(3);
        assertThat(usages(result, first, UsageKind.TYPE_REF, "java.util.List")).hasSize(1);
        assertThat(result.usages()).filteredOn(u -> u.kind() == UsageKind.TYPE_REF)
                .extracting(Usage::toKey)
                .doesNotContain("java.util.ArrayList", "p.Base", "p.Marker");
    }

    @Test
    void annotationsKeepTheirSourceTextAsSnippet() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Route.java", """
                        package p;
                        public @interface Route { String value(); }
                        """)
                .java("app", "p/C.java", """
                        package p;
                        class C {
                            @Route("/orders")
                            void list() {}
                        }
                        """)
                .index();

        assertThat(usage(result, "p.C#list()", UsageKind.ANNOTATION, "p.Route"))
                .extracting(Usage::confidence, Usage::snippet)
                .containsExactly(Confidence.EXACT, "@Route(\"/orders\")");
    }

    @Test
    void annotationSnippetIsTheSourceTextOnOneLine() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Mapping.java", """
                        package p;
                        public @interface Mapping { String value(); String produces(); }
                        """)
                .java("app", "p/E.java", """
                        package p;
                        class E {
                            @Mapping(value = "/orders",
                                     produces = "json")
                            void list() {}
                        }
                        """)
                .index();

        assertThat(usage(result, "p.E#list()", UsageKind.ANNOTATION, "p.Mapping").snippet())
                .isEqualTo("@Mapping(value = \"/orders\", produces = \"json\")");
    }

    @Test
    void annotationSnippetIsCorrectInFileWithUtf8Bom() throws Exception {
        byte[] source = """
                package p;
                class B {
                    @Route("/bom")
                    void list() {}
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withBom = new byte[source.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(source, 0, withBom, 3, source.length);
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Route.java", """
                        package p;
                        public @interface Route { String value(); }
                        """)
                .rawJava("app", "p/B.java", withBom)
                .index();

        Usage annotation = usage(result, "p.B#list()", UsageKind.ANNOTATION, "p.Route");
        assertThat(annotation.snippet()).isEqualTo("@Route(\"/bom\")");
        assertThat(annotation.line()).isEqualTo(3);
    }

    @Test
    void missingAnnotationTypeIsNameOnlyThroughImports() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/D.java", """
                package p;
                import org.springframework.web.bind.annotation.PostMapping;
                class D {
                    @PostMapping("/orders")
                    void create() {}
                }
                """).index();

        Usage annotation = usage(result, "p.D#create()", UsageKind.ANNOTATION,
                "org.springframework.web.bind.annotation.PostMapping");
        assertThat(annotation.confidence()).isEqualTo(Confidence.NAME_ONLY);
        assertThat(annotation.snippet()).contains("@PostMapping").contains("/orders");
    }

    @Test
    void fieldsOfMissingTypesAreNotKeyedUnderTheGuessedPackage() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/order/Child.java", """
                package com.corp.order;
                import org.vendor.Base;
                import org.vendor.Config;
                class Child extends Base {
                    int a(Config c) { return c.timeout; }
                    int b() { return Config.LIMIT; }
                    int c() { return count; }
                }
                """).index();

        assertThat(result.usages())
                .filteredOn(u -> u.kind() == UsageKind.FIELD_READ)
                .extracting(Usage::toKey)
                .noneMatch(key -> key.startsWith("com.corp.order.Config") || key.startsWith("com.corp.order.Base")
                        || key.startsWith("Config") || key.startsWith("Base"));
    }
}

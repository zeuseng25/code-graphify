package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportResolverTest {

    @TempDir
    Path dir;

    private ImportResolver resolverFor(String source) throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", source), List.of()).get("p/A.java");
        return new ImportResolver(unit);
    }

    @Test
    void resolvesSingleTypeImportsIncludingNestedNames() throws Exception {
        ImportResolver resolver = resolverFor("""
                package p;
                import org.springframework.web.client.RestTemplate;
                import java.util.Map;
                class A {}
                """);

        assertThat(resolver.resolve("RestTemplate")).contains("org.springframework.web.client.RestTemplate");
        assertThat(resolver.resolve("Map.Entry")).contains("java.util.Map$Entry");
    }

    @Test
    void guessesFromTheOnlyWildcardImport() throws Exception {
        assertThat(resolverFor("package p; import org.vendor.*; class A {}").resolve("Client"))
                .contains("org.vendor.Client");
    }

    @Test
    void refusesToGuessBetweenSeveralWildcardImports() throws Exception {
        assertThat(resolverFor("package p; import org.a.*; import org.b.*; class A {}").resolve("Client")).isEmpty();
    }

    @Test
    void keepsNamesThatAreAlreadyQualified() throws Exception {
        assertThat(resolverFor("package p; class A {}").resolve("org.vendor.Client")).contains("org.vendor.Client");
    }

    @Test
    void ignoresStaticImports() throws Exception {
        ImportResolver resolver = resolverFor("package p; import static org.x.Util.helper; class A {}");

        assertThat(resolver.resolve("Util")).isEmpty();
        assertThat(resolver.resolve("helper")).isEmpty();
    }

    @Test
    void mapsStaticMembersToTheirClass() throws Exception {
        ImportResolver single = resolverFor("package p; import static org.x.Util.helper; class A {}");
        ImportResolver oneWildcard = resolverFor("package p; import static org.x.Util.*; class A {}");
        ImportResolver twoWildcards = resolverFor("package p; import static org.x.Util.*; import static org.y.More.*; class A {}");

        assertThat(single.staticImportClass("helper")).contains("org.x.Util");
        assertThat(single.staticImportClass("other")).isEmpty();
        assertThat(oneWildcard.staticImportClass("anything")).contains("org.x.Util");
        assertThat(twoWildcards.staticImportClass("anything")).isEmpty();
    }

    @Test
    void lowercaseNamesResolveOnlyWhenTheyLookQualified() throws Exception {
        ImportResolver resolver = resolverFor("package p; import org.vendor.*; class A {}");

        assertThat(resolver.resolve("log")).isEmpty();
        assertThat(resolver.resolve("foo.bar")).isEmpty();
        assertThat(resolver.resolve("org.vendor.Client")).contains("org.vendor.Client");
    }

    @Test
    void qualifiedNestedNamesBecomeBinaryNames() throws Exception {
        ImportResolver resolver = resolverFor("package p; class A {}");

        assertThat(resolver.resolve("com.x.Outer.Inner")).contains("com.x.Outer$Inner");
        assertThat(resolver.resolve("com.x.Outer.Inner.Deep")).contains("com.x.Outer$Inner$Deep");
        assertThat(resolver.resolve("org.vendor.Client")).contains("org.vendor.Client");
    }
}

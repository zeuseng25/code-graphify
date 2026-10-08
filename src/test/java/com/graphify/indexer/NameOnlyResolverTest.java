package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameOnlyResolverTest {

    @TempDir
    Path dir;

    private CompilationUnit parse(String source) throws Exception {
        return ParsedSources.parse(dir, Map.of("com/corp/order/Api.java", source), List.of())
                .get("com/corp/order/Api.java");
    }

    @Test
    void receiverOfMissingTypeResolvesThroughImportsNotTheCurrentPackage() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.springframework.web.client.RestTemplate;
                class Api {
                    RestTemplate rest;
                    void call() { rest.exchange("u"); }
                }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(call.resolveMethodBinding()).isNull();
        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("org.springframework.web.client.RestTemplate");
    }

    @Test
    void staticCallOnMissingTypeResolvesThroughImports() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.vendor.Helper;
                class Api { void call() { Helper.run(); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("org.vendor.Helper");
    }

    @Test
    void receiverOfKnownTypeUsesItsBinding() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                class Api { int call() { return "x".length(); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("java.lang.String");
    }

    @Test
    void missingParameterizedTypeResolvesThroughImports() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.vendor.Client;
                class Api { Client<String> client; }
                """);
        FieldDeclaration field = ParsedSources.find(unit, FieldDeclaration.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).typeClass(field.getType()))
                .contains("org.vendor.Client");
    }

    @Test
    void lowercaseSimpleNameReceiverIsNotATypeName() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.vendor.*;
                class Api extends Base { void call() { log.info("x"); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression())).isEmpty();
    }

    @Test
    void nestedReceiverOfMissingTypeKeepsItsSpelledOuterType() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import com.vendor.Client;
                class Api { Object build(Client.Builder builder) { return builder.build(); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("com.vendor.Client$Builder");
    }
}

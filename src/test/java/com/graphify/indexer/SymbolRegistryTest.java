package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolRegistryTest {

    @TempDir
    Path dir;

    @Test
    void registersMembersWithTheirOwningTypesAsParents() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("com/acme/Outer.java", """
                package com.acme;
                public class Outer {
                    public static class Inner { public void run() {} }
                }
                """), List.of()).get("com/acme/Outer.java");
        MethodDeclaration run = ParsedSources.find(unit, MethodDeclaration.class).getFirst();
        SymbolRegistry registry = new SymbolRegistry();

        assertThat(registry.method(run.resolveBinding())).isEqualTo("com.acme.Outer$Inner#run()");
        assertThat(registry.all())
                .extracting(Symbol::key, Symbol::kind, Symbol::parentKey, Symbol::origin, Symbol::displaySignature)
                .containsExactlyInAnyOrder(
                        tuple("com.acme.Outer", SymbolKind.CLASS, null, SymbolOrigin.SOURCE, "Outer"),
                        tuple("com.acme.Outer$Inner", SymbolKind.CLASS, "com.acme.Outer", SymbolOrigin.SOURCE, "Inner"),
                        tuple("com.acme.Outer$Inner#run()", SymbolKind.METHOD, "com.acme.Outer$Inner",
                                SymbolOrigin.SOURCE, "Inner.run()"));
    }

    @Test
    void jdkMembersAreBinary() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", """
                package p;
                class A { int f() { return "x".length(); } }
                """), List.of()).get("p/A.java");
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();
        SymbolRegistry registry = new SymbolRegistry();

        String key = registry.method(call.resolveMethodBinding());

        assertThat(key).isEqualTo("java.lang.String#length()");
        assertThat(registry.all()).extracting(Symbol::key, Symbol::origin).containsExactlyInAnyOrder(
                tuple("java.lang.String", SymbolOrigin.BINARY),
                tuple("java.lang.String#length()", SymbolOrigin.BINARY));
    }

    @Test
    void classifiesTypeKinds() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/Kinds.java", """
                package p;
                class Kinds {
                    interface I {}
                    enum E { X }
                    record R(int a) {}
                    @interface A {}
                }
                """), List.of()).get("p/Kinds.java");
        SymbolRegistry registry = new SymbolRegistry();
        ParsedSources.find(unit, AbstractTypeDeclaration.class).forEach(t -> registry.type(t.resolveBinding()));

        assertThat(registry.all()).extracting(Symbol::key, Symbol::kind).containsExactlyInAnyOrder(
                tuple("p.Kinds", SymbolKind.CLASS),
                tuple("p.Kinds$I", SymbolKind.INTERFACE),
                tuple("p.Kinds$E", SymbolKind.ENUM),
                tuple("p.Kinds$R", SymbolKind.RECORD),
                tuple("p.Kinds$A", SymbolKind.ANNOTATION_TYPE));
    }

    @Test
    void bindingBasedSymbolReplacesNameOnlySymbol() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("com/acme/Api.java", """
                package com.acme;
                public interface Api {}
                """), List.of()).get("com/acme/Api.java");
        SymbolRegistry registry = new SymbolRegistry();

        registry.nameOnlyType("com.acme.Api");
        registry.type(ParsedSources.find(unit, AbstractTypeDeclaration.class).getFirst().resolveBinding());

        assertThat(registry.all()).extracting(Symbol::key, Symbol::kind, Symbol::origin)
                .containsExactly(tuple("com.acme.Api", SymbolKind.INTERFACE, SymbolOrigin.SOURCE));
    }

    @Test
    void nameOnlyMethodRegistersItsClassAsParent() {
        SymbolRegistry registry = new SymbolRegistry();

        assertThat(registry.nameOnlyMethod("org.x.Rest", "exchange", 2)).isEqualTo("org.x.Rest#exchange/2");
        assertThat(registry.nameOnlyConstructor("org.x.Rest", 0)).isEqualTo("org.x.Rest#<init>/0");
        assertThat(registry.all())
                .extracting(Symbol::key, Symbol::kind, Symbol::memberName, Symbol::parentKey, Symbol::displaySignature)
                .containsExactlyInAnyOrder(
                        tuple("org.x.Rest", SymbolKind.CLASS, null, null, "Rest"),
                        tuple("org.x.Rest#exchange/2", SymbolKind.METHOD, "exchange", "org.x.Rest", "Rest.exchange(2 args)"),
                        tuple("org.x.Rest#<init>/0", SymbolKind.CONSTRUCTOR, "<init>", "org.x.Rest", "Rest.Rest(0 args)"));
    }

    @Test
    void arrayLengthIsNotAFieldAndPrimitivesAreNotTypes() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", """
                package p;
                class A { int f(int[] a) { return a.length; } }
                """), List.of()).get("p/A.java");
        SimpleName length = ParsedSources.find(unit, SimpleName.class).stream()
                .filter(n -> n.getIdentifier().equals("length")).findFirst().orElseThrow();
        SingleVariableDeclaration parameter = ParsedSources.find(unit, SingleVariableDeclaration.class).getFirst();
        SymbolRegistry registry = new SymbolRegistry();

        assertThat(registry.field((IVariableBinding) length.resolveBinding())).isNull();
        assertThat(registry.type(((ArrayType) parameter.getType()).getElementType().resolveBinding())).isNull();
        assertThat(registry.type(unit.getAST().resolveWellKnownType("int"))).isNull();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void sourceSymbolReplacesBinarySymbolWithTheSameKeyButNotTheReverse() throws Exception {
        String api = """
                package com.acme;
                public class Api {}
                """;
        Path jar = TestJars.jar(dir.resolve("jars"), "api", Map.of("com/acme/Api.java", api), Set.of());
        ITypeBinding binary = ParsedSources.find(ParsedSources.parse(dir.resolve("user"), Map.of("p/User.java", """
                package p;
                class User { com.acme.Api api; }
                """), List.of(jar)).get("p/User.java"), FieldDeclaration.class).getFirst().getType().resolveBinding();
        ITypeBinding source = ParsedSources.find(ParsedSources.parse(dir.resolve("lib"),
                Map.of("com/acme/Api.java", api), List.of()).get("com/acme/Api.java"), AbstractTypeDeclaration.class)
                .getFirst().resolveBinding();

        SymbolRegistry binaryFirst = new SymbolRegistry();
        binaryFirst.type(binary);
        binaryFirst.type(source);
        SymbolRegistry sourceFirst = new SymbolRegistry();
        sourceFirst.type(source);
        sourceFirst.type(binary);

        assertThat(binary.isFromSource()).isFalse();
        assertThat(binaryFirst.all()).extracting(Symbol::key, Symbol::origin)
                .containsExactly(tuple("com.acme.Api", SymbolOrigin.SOURCE));
        assertThat(sourceFirst.all()).extracting(Symbol::key, Symbol::origin)
                .containsExactly(tuple("com.acme.Api", SymbolOrigin.SOURCE));
    }

    @Test
    void nameOnlyFlagMarksGuessesUntilABindingReplacesThem() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("com/acme/Api.java", """
                package com.acme;
                public interface Api {}
                """), List.of()).get("com/acme/Api.java");
        SymbolRegistry registry = new SymbolRegistry();

        registry.nameOnlyMethod("org.x.Rest", "exchange", 2);
        registry.nameOnlyType("com.acme.Api");
        registry.type(ParsedSources.find(unit, AbstractTypeDeclaration.class).getFirst().resolveBinding());

        assertThat(registry.all()).extracting(Symbol::key, Symbol::nameOnly).containsExactlyInAnyOrder(
                tuple("org.x.Rest", true),
                tuple("org.x.Rest#exchange/2", true),
                tuple("com.acme.Api", false));
    }
}

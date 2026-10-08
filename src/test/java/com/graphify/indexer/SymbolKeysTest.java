package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolKeysTest {

    @TempDir
    Path dir;

    private CompilationUnit money() throws Exception {
        return ParsedSources.parse(dir, Map.of("com/acme/Money.java", """
                package com.acme;
                import java.util.List;
                import java.util.Map;
                public class Money {
                    public Money(String currency) {}
                    public String format(int amount) { return ""; }
                    public String format(String amount) { return ""; }
                    public <T extends Number> void sum(List<T> values, Map.Entry<String, T> pair, int[][] grid, Object... rest) {}
                    public static class Rounding { public int scale; }
                }
                """), List.of()).get("com/acme/Money.java");
    }

    @Test
    void methodKeysDistinguishOverloadsAndUseErasedQualifiedParameterTypes() throws Exception {
        List<String> keys = ParsedSources.find(money(), MethodDeclaration.class).stream()
                .map(m -> SymbolKeys.methodKey(m.resolveBinding()))
                .toList();

        assertThat(keys).containsExactly(
                "com.acme.Money#<init>(java.lang.String)",
                "com.acme.Money#format(int)",
                "com.acme.Money#format(java.lang.String)",
                "com.acme.Money#sum(java.util.List,java.util.Map$Entry,int[][],java.lang.Object[])");
    }

    @Test
    void nestedTypesUseBinaryNamesAndFieldsHangOffTheirType() throws Exception {
        CompilationUnit unit = money();
        TypeDeclaration rounding = ParsedSources.find(unit, TypeDeclaration.class).get(1);
        VariableDeclarationFragment scale = ParsedSources.find(unit, VariableDeclarationFragment.class).getFirst();

        assertThat(SymbolKeys.typeKey(rounding.resolveBinding())).isEqualTo("com.acme.Money$Rounding");
        assertThat(SymbolKeys.fieldKey(scale.resolveBinding())).isEqualTo("com.acme.Money$Rounding.scale");
    }

    @Test
    void nameOnlyKeysCarryNameAndArgumentCount() {
        assertThat(SymbolKeys.nameOnlyMethodKey("org.x.Rest", "exchange", 4)).isEqualTo("org.x.Rest#exchange/4");
        assertThat(SymbolKeys.nameOnlyConstructorKey("org.x.Rest", 0)).isEqualTo("org.x.Rest#<init>/0");
    }

    @Test
    void typeVariableParameterKeysAsItsErasure() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/G.java", """
                package p;
                class G { <T extends Number> void f(T t) {} }
                """), List.of()).get("p/G.java");

        assertThat(SymbolKeys.methodKey(ParsedSources.find(unit, MethodDeclaration.class).getFirst().resolveBinding()))
                .isEqualTo("p.G#f(java.lang.Number)");
    }
}

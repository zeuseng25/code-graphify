package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfidenceClassifierTest {

    @TempDir
    Path dir;

    @Test
    void exactOnlyWhenNoCompileErrorOverlapsTheNode() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", """
                package p;
                class A {
                    int ok() { return "x".length(); }
                    void broken() { int n = "not a number"; }
                }
                """), List.of()).get("p/A.java");
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();
        List<MethodDeclaration> methods = ParsedSources.find(unit, MethodDeclaration.class);
        ConfidenceClassifier classifier = new ConfidenceClassifier(unit);

        assertThat(classifier.hasErrorWithin(methods.get(0))).isFalse();
        assertThat(classifier.hasErrorWithin(methods.get(1))).isTrue();
        assertThat(classifier.of(call.resolveMethodBinding(), call)).isEqualTo(Confidence.EXACT);
        assertThat(classifier.of(call.resolveMethodBinding(), methods.get(1))).isEqualTo(Confidence.RECOVERED);
    }

    @Test
    void recoveredWhenTheBindingIsRecovered() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/B.java", """
                package p;
                class B { Missing field; }
                """), List.of()).get("p/B.java");
        SimpleType missing = ParsedSources.find(unit, SimpleType.class).getFirst();

        assertThat(missing.resolveBinding().isRecovered()).isTrue();
        assertThat(new ConfidenceClassifier(unit).of(missing.resolveBinding(), missing))
                .isEqualTo(Confidence.RECOVERED);
    }
}

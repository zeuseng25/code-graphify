package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;

/**
 * Decides EXACT vs RECOVERED for a resolved binding. With an incomplete classpath JDT can bind a call to the
 * wrong overload without flagging the binding as recovered, but it always reports an error at that call, so any
 * error overlapping the node downgrades the usage. A method with a recovered parameter type is never EXACT.
 */
final class ConfidenceClassifier {

    private final List<int[]> errorRanges = new ArrayList<>();

    ConfidenceClassifier(CompilationUnit unit) {
        for (IProblem problem : unit.getProblems()) {
            if (problem.isError()) {
                errorRanges.add(new int[] {problem.getSourceStart(), problem.getSourceEnd()});
            }
        }
    }

    Confidence of(IBinding binding, ASTNode node) {
        boolean recovered = binding.isRecovered()
                || (binding instanceof IMethodBinding method && MethodKeys.hasRecoveredParameter(method));
        return recovered || hasErrorWithin(node) ? Confidence.RECOVERED : Confidence.EXACT;
    }

    boolean hasErrorWithin(ASTNode node) {
        int start = node.getStartPosition();
        int end = start + node.getLength() - 1;
        for (int[] range : errorRanges) {
            if (range[0] <= end && range[1] >= start) {
                return true;
            }
        }
        return false;
    }
}

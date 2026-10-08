package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexWarning;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Everything the visitors need about the file being indexed. */
final class FileContext {

    final String modulePath;
    final String filePath;
    final CompilationUnit unit;
    final SymbolRegistry symbols;
    final ConfidenceClassifier confidence;
    final NameOnlyResolver nameOnly;

    private final SourceLines lines;
    private final IndexCollector out;
    private final int snippetMaxLength;

    FileContext(String modulePath, String filePath, CompilationUnit unit, SourceLines lines, SymbolRegistry symbols,
            ConfidenceClassifier confidence, NameOnlyResolver nameOnly, IndexCollector out, int snippetMaxLength) {
        this.modulePath = modulePath;
        this.filePath = filePath;
        this.unit = unit;
        this.lines = lines;
        this.symbols = symbols;
        this.confidence = confidence;
        this.nameOnly = nameOnly;
        this.out = out;
        this.snippetMaxLength = snippetMaxLength;
    }

    void usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, ASTNode at) {
        usage(fromKey, toKey, kind, confidence, at, lines.snippet(line(at), snippetMaxLength));
    }

    void usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, ASTNode at, String snippet) {
        if (fromKey == null || toKey == null) {
            return;
        }
        out.usage(new Usage(fromKey, toKey, kind, confidence, modulePath, filePath, line(at), column(at),
                SourceLines.truncate(snippet, snippetMaxLength)));
    }

    void declaration(String symbolKey, ASTNode name) {
        if (symbolKey == null) {
            return;
        }
        out.declaration(new Declaration(symbolKey, modulePath, filePath, line(name)));
    }

    void warning(ASTNode at, String message) {
        out.warning(new IndexWarning(modulePath, filePath, line(at), message));
    }

    /** The node's exact source text with every whitespace run collapsed to a single space. */
    String sourceText(ASTNode node) {
        return lines.text(node.getStartPosition(), node.getLength()).replaceAll("\\s+", " ").strip();
    }

    private int line(ASTNode node) {
        return unit.getLineNumber(node.getStartPosition());
    }

    private int column(ASTNode node) {
        return unit.getColumnNumber(node.getStartPosition()) + 1;
    }
}

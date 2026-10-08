package com.graphify.impact;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** One CSV row per impact edge (RFC 4180 quoting, formulas neutralised), for spreadsheets. */
final class ImpactCsv {

    private static final List<String> HEADER = List.of("level", "repository", "module", "file", "line", "from",
            "kind", "to", "confidence", "via_dispatch", "snippet");

    private ImpactCsv() {
    }

    static String write(ImpactResult result) {
        Map<Long, String> display = new HashMap<>();
        result.nodes().forEach(node -> display.put(node.symbolId(), node.display()));
        StringBuilder csv = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (ImpactEdge edge : result.edges()) {
            StringJoiner row = new StringJoiner(",");
            row.add(Integer.toString(edge.level()));
            row.add(field(edge.repository() == null ? null
                    : edge.repository().projectKey() + "/" + edge.repository().slug()));
            row.add(field(edge.modulePath()));
            row.add(field(edge.filePath()));
            row.add(Integer.toString(edge.line()));
            row.add(field(display.getOrDefault(edge.fromSymbolId(), Long.toString(edge.fromSymbolId()))));
            row.add(edge.kind().name());
            row.add(field(display.getOrDefault(edge.toSymbolId(), Long.toString(edge.toSymbolId()))));
            row.add(edge.confidence().name());
            row.add(Boolean.toString(edge.viaDispatch()));
            row.add(field(edge.snippet()));
            csv.append(row).append("\r\n");
        }
        return csv.toString();
    }

    /** First characters that make a spreadsheet read a cell as a formula (CSV injection). */
    private static final String FORMULA_STARTS = "=+-@\t\r";

    /**
     * A free-text cell: a leading {@code '} if it would otherwise start a formula, then RFC 4180 quoting. Numeric
     * and enum columns are written directly and never pass through here.
     */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && FORMULA_STARTS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}

package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImpactCsvTest {

    @Test
    void writesOneRowPerEdgeAndEscapesFields() {
        ImpactNode seed = new ImpactNode(1, "lib.F#format(int)", SymbolKind.METHOD, "F.format(int)", 0, NodeRole.SEED,
                Confidence.EXACT, false);
        ImpactNode caller = new ImpactNode(2, "api.S#label(int)", SymbolKind.METHOD, "S.label(int)", 1,
                NodeRole.AFFECTED, Confidence.EXACT, false);
        ImpactEdge edge = new ImpactEdge(2, 1, UsageKind.CALL, Confidence.EXACT, 1, false, 7,
                new RepositoryRef(3, "SHOP", "shop-api"), "shop-api",
                "S.java", 12, 5, "return f.format(\"a,b\");");
        ImpactResult result = new ImpactResult(new ImpactSummary(1, 1, 1, 1, 1, Map.of(), Map.of(), Map.of(), List.of()),
                List.of(seed, caller), List.of(edge), List.of(), List.of(), List.of(), false);

        String csv = ImpactCsv.write(result);

        assertThat(csv.lines().toList()).containsExactly(
                "level,repository,module,file,line,from,kind,to,confidence,via_dispatch,snippet",
                "1,SHOP/shop-api,shop-api,S.java,12,S.label(int),CALL,F.format(int),EXACT,false,"
                        + "\"return f.format(\"\"a,b\"\");\"");
    }

    @Test
    void neutralisesSpreadsheetFormulasInFreeText() {
        ImpactNode seed = new ImpactNode(1, "lib.F#f()", SymbolKind.METHOD, "=HYPERLINK(\"http://x\",\"y\")", 0,
                NodeRole.SEED, Confidence.EXACT, false);
        ImpactNode caller = new ImpactNode(2, "api.S#s()", SymbolKind.METHOD, "+S.s()", 1, NodeRole.AFFECTED,
                Confidence.EXACT, false);
        ImpactEdge edge = new ImpactEdge(2, 1, UsageKind.ANNOTATION, Confidence.EXACT, 1, false, 7,
                new RepositoryRef(3, "SHOP", "shop-api"), "-module", "\tS.java", 12, 5, "@Override");
        ImpactResult result = new ImpactResult(new ImpactSummary(1, 1, 1, 1, 1, Map.of(), Map.of(), Map.of(), List.of()),
                List.of(seed, caller), List.of(edge), List.of(), List.of(), List.of(), false);

        assertThat(ImpactCsv.write(result).lines().toList()).last().isEqualTo(
                "1,SHOP/shop-api,'-module,'\tS.java,12,'+S.s(),ANNOTATION,"
                        + "\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\",EXACT,false,'@Override");
    }

    @Test
    void twinTargetsShowTheirDisplayInsteadOfAnId() {
        ImpactNode twin = new ImpactNode(5, "lib.F#format/1", SymbolKind.METHOD, "F.format(1 args)", 0, NodeRole.TWIN,
                Confidence.EXACT, true);
        ImpactNode caller = new ImpactNode(6, "old.R#print()", SymbolKind.METHOD, "R.print()", 1, NodeRole.AFFECTED,
                Confidence.NAME_ONLY, false);
        ImpactEdge edge = new ImpactEdge(6, 5, UsageKind.CALL, Confidence.NAME_ONLY, 1, false, 7, null, "legacy",
                "R.java", 3, 1, "f.format(5);");
        ImpactResult result = new ImpactResult(new ImpactSummary(1, 1, 1, 1, 1, Map.of(), Map.of(), Map.of(), List.of()), List.of(twin, caller), List.of(edge),
                List.of(), List.of(), List.of(), false);

        assertThat(ImpactCsv.write(result)).contains(",R.print(),CALL,F.format(1 args),NAME_ONLY,");
    }
}

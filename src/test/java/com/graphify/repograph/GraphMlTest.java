package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class GraphMlTest {

    @Test
    void writesNodesEdgesAndTheirDataAsGraphMl() throws Exception {
        RepoGraph graph = new GraphBuilder(SampleGraphs.sample(),
                Map.of(2L, new ClassMetrics(2, 0, 5, false, 1, "com.g.a"))).build(GraphLevel.CLASS, "com.g.a", null, 500);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(GraphMl.write(graph)));

        assertThat(xml.getDocumentElement().getLocalName()).isEqualTo("graphml");
        assertThat(xml.getDocumentElement().getNamespaceURI()).isEqualTo("http://graphml.graphdrawing.org/xmlns");
        assertThat(xml.getElementsByTagNameNS("*", "node").getLength()).isEqualTo(3);
        assertThat(xml.getElementsByTagNameNS("*", "edge").getLength()).isEqualTo(2);
        assertThat(new String(GraphMl.write(graph), StandardCharsets.UTF_8))
                .contains("class:com.g.a.AlphaHelper").contains(">5<").contains(">CALL=1<");
        assertThat(xml.getDocumentElement().getElementsByTagNameNS("*", "graph").item(0).getAttributes()
                .getNamedItem("edgedefault").getNodeValue()).isEqualTo("directed");
        Set<String> keys = new HashSet<>();
        NodeList keyElements = xml.getElementsByTagNameNS("*", "key");
        for (int i = 0; i < keyElements.getLength(); i++) {
            keys.add(((Element) keyElements.item(i)).getAttribute("id"));
        }
        assertThat(keys).containsExactlyInAnyOrder("label", "type", "size", "community", "dependents", "weight",
                "kinds", "requestedLevel", "level", "truncated");
        String text = new String(GraphMl.write(graph), StandardCharsets.UTF_8);
        assertThat(text).contains(">CLASS<").contains("key=\"truncated\">false<");
    }

    @Test
    void replacesCharactersXmlCannotCarry() throws Exception {
        RepoGraph graph = new RepoGraph(1, GraphLevel.CLASS, GraphLevel.CLASS, null, false, null,
                List.of(new GraphNode("class:a\u0001b", "bad\u0001label\uD800end\uD83D\uDE00", NodeType.CLASS, 1,
                        null)), List.of());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(GraphMl.write(graph)));

        String label = xml.getElementsByTagNameNS("*", "data").item(3).getTextContent();
        assertThat(label).isEqualTo("bad\uFFFDlabel\uFFFDend\uD83D\uDE00");
    }
}

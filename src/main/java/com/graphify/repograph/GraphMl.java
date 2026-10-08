package com.graphify.repograph;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** Writes a repository graph as GraphML (for Gephi, yEd and similar tools). */
final class GraphMl {

    /** The GraphML namespace (graphml.graphdrawing.org), a format fact. */
    private static final String NAMESPACE = "http://graphml.graphdrawing.org/xmlns";

    private GraphMl() {
    }

    static byte[] write(RepoGraph graph) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out,
                    StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("graphml");
            xml.writeDefaultNamespace(NAMESPACE);
            key(xml, "label", "node", "string");
            key(xml, "type", "node", "string");
            key(xml, "size", "node", "int");
            key(xml, "community", "node", "int");
            key(xml, "dependents", "node", "int");
            key(xml, "weight", "edge", "long");
            key(xml, "kinds", "edge", "string");
            key(xml, "requestedLevel", "graph", "string");
            key(xml, "level", "graph", "string");
            key(xml, "truncated", "graph", "boolean");
            xml.writeStartElement("graph");
            xml.writeAttribute("id", "repository-" + graph.repositoryId());
            xml.writeAttribute("edgedefault", "directed");
            data(xml, "requestedLevel", graph.requestedLevel().name());
            data(xml, "level", graph.level().name());
            data(xml, "truncated", Boolean.toString(graph.truncated()));
            for (GraphNode node : graph.nodes()) {
                xml.writeStartElement("node");
                xml.writeAttribute("id", xmlSafe(node.id()));
                data(xml, "label", node.label());
                data(xml, "type", node.type().name());
                data(xml, "size", Integer.toString(node.size()));
                if (node.metrics() != null) {
                    if (node.metrics().communityId() != null) {
                        data(xml, "community", node.metrics().communityId().toString());
                    }
                    data(xml, "dependents", Integer.toString(node.metrics().dependents()));
                }
                xml.writeEndElement();
            }
            int index = 0;
            for (GraphEdge edge : graph.edges()) {
                xml.writeStartElement("edge");
                xml.writeAttribute("id", "e" + index++);
                xml.writeAttribute("source", xmlSafe(edge.from()));
                xml.writeAttribute("target", xmlSafe(edge.to()));
                data(xml, "weight", Long.toString(edge.weight()));
                data(xml, "kinds", edge.kinds().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(",")));
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("The graph could not be written as GraphML", e);
        }
        return out.toByteArray();
    }

    /** Replaces the code points XML 1.0 cannot carry (control characters, U+FFFE/U+FFFF, lone surrogates) with U+FFFD. */
    private static String xmlSafe(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> safe.appendCodePoint(legal(cp) ? cp : 0xFFFD));
        return safe.toString();
    }

    private static boolean legal(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static void key(XMLStreamWriter xml, String id, String target, String type) throws XMLStreamException {
        xml.writeEmptyElement("key");
        xml.writeAttribute("id", id);
        xml.writeAttribute("for", target);
        xml.writeAttribute("attr.name", id);
        xml.writeAttribute("attr.type", type);
    }

    private static void data(XMLStreamWriter xml, String key, String value) throws XMLStreamException {
        xml.writeStartElement("data");
        xml.writeAttribute("key", key);
        xml.writeCharacters(xmlSafe(value));
        xml.writeEndElement();
    }
}

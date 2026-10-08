package com.graphify.maven;

import java.io.StringWriter;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Builds the Maven settings.xml for one resolution from artifact_repository rows (spec §6.4). */
final class SettingsXmlWriter {

    /** Profile that carries repositories without mirror_of; always active. */
    private static final String PROFILE = "graphify";

    private SettingsXmlWriter() {
    }

    static String write(List<ArtifactRepository> repositories, String runToken) {
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element settings = document.createElement("settings");
            document.appendChild(settings);
            Element servers = append(document, settings, "servers");
            Element mirrors = append(document, settings, "mirrors");
            Element profile = append(document, append(document, settings, "profiles"), "profile");
            text(document, profile, "id", PROFILE);
            Element repos = append(document, profile, "repositories");
            Element pluginRepos = append(document, profile, "pluginRepositories");
            for (ArtifactRepository repository : repositories) {
                String id = "graphify-" + runToken + "-" + repository.id();
                if (repository.username() != null || repository.secret() != null) {
                    Element server = append(document, servers, "server");
                    text(document, server, "id", id);
                    if (repository.username() != null) {
                        text(document, server, "username", repository.username());
                    }
                    if (repository.secret() != null) {
                        text(document, server, "password", repository.secret());
                    }
                }
                if (repository.mirrorOf() != null && !repository.mirrorOf().isBlank()) {
                    Element mirror = append(document, mirrors, "mirror");
                    text(document, mirror, "id", id);
                    text(document, mirror, "mirrorOf", repository.mirrorOf());
                    text(document, mirror, "url", repository.url());
                } else {
                    for (Element parent : new Element[] {repos, pluginRepos}) {
                        Element repo = append(document, parent,
                                parent == repos ? "repository" : "pluginRepository");
                        text(document, repo, "id", id);
                        text(document, repo, "url", repository.url());
                        text(document, append(document, repo, "releases"), "enabled", "true");
                        text(document, append(document, repo, "snapshots"), "enabled", "true");
                    }
                }
            }
            text(document, append(document, settings, "activeProfiles"), "activeProfile", PROFILE);
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build settings.xml", e);
        }
    }

    private static Element append(Document document, Element parent, String name) {
        Element element = document.createElement(name);
        parent.appendChild(element);
        return element;
    }

    private static void text(Document document, Element parent, String name, String value) {
        append(document, parent, name).setTextContent(value);
    }
}

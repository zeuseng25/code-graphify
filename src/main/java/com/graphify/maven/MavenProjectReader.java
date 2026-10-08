package com.graphify.maven;

import com.graphify.store.DependencyRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reads a checkout's pom tree without running Maven (spec §3.2 step 4). */
@Component
public class MavenProjectReader {

    private static final String POM = "pom.xml";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    /** Enough rounds for nested property references; a cycle stops here instead of looping. */
    private static final int MAX_INTERPOLATION_ROUNDS = 10;

    private record Dependency(String groupId, String artifactId, String version, String scope, String type) {
    }

    private record Pom(Path file, String groupId, String artifactId, String version, String parentGroupId,
            String parentArtifactId, String parentVersion, String parentRelativePath, Map<String, String> properties,
            List<String> modules, List<Dependency> dependencies, List<Dependency> managed, String sourceDirectory,
            String testSourceDirectory, String packaging) {
    }

    /** Folders the nested-project walk never enters: VCS data, build output, dependencies (Plan 16 Ruling 9). */
    static final Set<String> SKIPPED_FOLDERS = Set.of(".git", "target", "node_modules", "build");

    public MavenProject read(Path checkout, List<String> sourceRootPatterns) {
        return read(checkout, sourceRootPatterns, 0);
    }

    /**
     * Reads the root pom's module tree, or, without a root pom, every independent project found up to
     * {@code pomSearchDepth} folders deep (0 = do not search). A pom inside an earlier project's module tree is not
     * a project of its own.
     */
    public MavenProject read(Path checkout, List<String> sourceRootPatterns, int pomSearchDepth) {
        Path root = checkout.toAbsolutePath().normalize();
        List<String> warnings = new ArrayList<>();
        Map<Path, Optional<Pom>> cache = new HashMap<>();
        List<MavenModule> modules = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        List<String> roots = new ArrayList<>();
        if (Files.isRegularFile(root.resolve(POM))) {
            if (load(root.resolve(POM), cache, warnings).isEmpty()) {
                return noPom(root, warnings);
            }
            collect(root, root, ".", sourceRootPatterns, cache, warnings, modules, visited);
            return new MavenProject(modules, warnings, List.of("."));
        }
        for (Path projectDir : pomFolders(root, pomSearchDepth, warnings)) {
            if (visited.contains(projectDir) || load(projectDir.resolve(POM), cache, warnings).isEmpty()) {
                continue;
            }
            String relative = relativeName(root, projectDir);
            roots.add(relative);
            collect(root, projectDir, relative, sourceRootPatterns, cache, warnings, modules, visited);
        }
        if (roots.isEmpty()) {
            return noPom(root, warnings);
        }
        long outside = javaFilesOutside(root, modules);
        if (outside > 0) {
            warnings.add(outside + " .java files outside the discovered Maven projects were not indexed");
        }
        return new MavenProject(modules, warnings, roots);
    }

    /** Counts .java files outside every module's source roots; same skipped folders as the walk, no links. */
    private static long javaFilesOutside(Path root, List<MavenModule> modules) {
        List<Path> sourceRoots = modules.stream().flatMap(m -> m.sourceRoots().stream()).toList();
        long[] count = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (!dir.equals(root) && (name.startsWith(".") || SKIPPED_FOLDERS.contains(name))
                            || sourceRoots.contains(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return count[0];
        }
        return count[0];
    }

    /** Folders holding a pom.xml, shallowest first then by path; links and SKIPPED_FOLDERS are never entered. */
    private static List<Path> pomFolders(Path root, int maxDepth, List<String> warnings) {
        List<Path> found = new ArrayList<>();
        List<Path> level = List.of(root);
        for (int depth = 1; depth <= maxDepth && !level.isEmpty(); depth++) {
            List<Path> next = new ArrayList<>();
            for (Path folder : level) {
                List<Path> children;
                try (Stream<Path> listing = Files.list(folder)) {
                    children = listing.sorted().toList();
                } catch (IOException e) {
                    warnings.add("Could not list " + relativeName(root, folder) + ": " + e.getClass().getSimpleName());
                    continue;
                }
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || name.startsWith(".")
                            || SKIPPED_FOLDERS.contains(name)) {
                        continue;
                    }
                    if (Files.isRegularFile(child.resolve(POM), LinkOption.NOFOLLOW_LINKS)) {
                        found.add(child);
                    }
                    next.add(child);
                }
            }
            level = next;
        }
        return found;
    }

    private static MavenProject noPom(Path root, List<String> warnings) {
        return new MavenProject(List.of(new MavenModule(".", null, null, null, List.of(root), List.of(), false)),
                warnings, List.of());
    }

    private void collect(Path root, Path moduleDir, String projectRoot, List<String> patterns, Map<Path, Optional<Pom>> cache,
            List<String> warnings, List<MavenModule> out, Set<Path> visited) {
        if (!visited.add(moduleDir)) {
            return;
        }
        Optional<Pom> loaded = load(moduleDir.resolve(POM), cache, warnings);
        if (loaded.isEmpty()) {
            return;
        }
        Pom pom = loaded.get();
        List<Pom> chain = chain(pom, root, cache, warnings);
        Map<String, String> properties = properties(chain);
        String groupId = interpolate(firstNonNull(pom.groupId(), pom.parentGroupId()), properties);
        String version = interpolate(firstNonNull(pom.version(), pom.parentVersion()), properties);
        String relative = root.relativize(moduleDir).toString().replace('\\', '/');
        String packaging = pom.packaging() == null ? "jar" : interpolate(pom.packaging(), properties);
        Gav parent = pom.parentArtifactId() == null ? null : Gav.of(interpolate(pom.parentGroupId(), properties),
                interpolate(pom.parentArtifactId(), properties), interpolate(pom.parentVersion(), properties));
        out.add(new MavenModule(relative.isEmpty() ? "." : relative, groupId,
                interpolate(pom.artifactId(), properties), unresolvedToNull(version),
                sourceRoots(root, moduleDir, patterns, pom, properties), dependencies(pom, chain, properties), true,
                packaging, parent, imports(pom, properties), projectRoot));
        for (String module : pom.modules()) {
            Path child = moduleDir.resolve(module.strip()).normalize();
            if (!child.startsWith(root)) {
                warnings.add("Module '" + module + "' of " + relativeName(root, pom.file()) + " is outside the checkout");
                continue;
            }
            if (!Files.isRegularFile(child.resolve(POM))) {
                warnings.add("Module '" + module + "' of " + relativeName(root, pom.file()) + " has no pom.xml");
                continue;
            }
            collect(root, child, projectRoot, patterns, cache, warnings, out, visited);
        }
    }

    /** The BOMs this pom imports in its own dependencyManagement (scope import). */
    private static List<Gav> imports(Pom pom, Map<String, String> properties) {
        List<Gav> imports = new ArrayList<>();
        for (Dependency managed : pom.managed()) {
            if ("import".equals(interpolate(managed.scope(), properties))) {
                Gav gav = Gav.of(interpolate(managed.groupId(), properties), interpolate(managed.artifactId(), properties),
                        interpolate(managed.version(), properties));
                if (gav != null) {
                    imports.add(gav);
                }
            }
        }
        return imports;
    }

    /** The pom followed by its parents found inside the checkout. */
    private List<Pom> chain(Pom pom, Path root, Map<Path, Optional<Pom>> cache, List<String> warnings) {
        List<Pom> chain = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        Pom current = pom;
        while (current != null && seen.add(current.file())) {
            chain.add(current);
            if (current.parentArtifactId() == null) {
                break;
            }
            Path parentPath = current.file().getParent()
                    .resolve(current.parentRelativePath() == null ? "../pom.xml" : current.parentRelativePath())
                    .normalize();
            if (Files.isDirectory(parentPath)) {
                parentPath = parentPath.resolve(POM);
            }
            if (!parentPath.startsWith(root) || !Files.isRegularFile(parentPath)) {
                break;
            }
            Optional<Pom> parent = load(parentPath, cache, warnings);
            String expectedArtifactId = current.parentArtifactId();
            current = parent.filter(p -> expectedArtifactId.equals(p.artifactId())).orElse(null);
        }
        return chain;
    }

    private static Map<String, String> properties(List<Pom> chain) {
        Map<String, String> properties = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            properties.putAll(chain.get(i).properties());
        }
        Pom pom = chain.getFirst();
        String version = firstNonNull(pom.version(), pom.parentVersion());
        String groupId = firstNonNull(pom.groupId(), pom.parentGroupId());
        if (version != null) {
            properties.put("project.version", version);
            properties.put("pom.version", version);
            properties.put("version", version);
        }
        if (groupId != null) {
            properties.put("project.groupId", groupId);
        }
        if (pom.artifactId() != null) {
            properties.put("project.artifactId", pom.artifactId());
        }
        if (pom.parentVersion() != null) {
            properties.put("project.parent.version", pom.parentVersion());
        }
        return properties;
    }

    /** Declared source directories first, then the configured patterns; only existing directories inside the checkout. */
    private static List<Path> sourceRoots(Path root, Path moduleDir, List<String> patterns, Pom pom,
            Map<String, String> properties) {
        Set<Path> roots = new LinkedHashSet<>();
        for (String declared : new String[] {pom.sourceDirectory(), pom.testSourceDirectory()}) {
            if (declared != null) {
                roots.add(moduleDir.resolve(interpolate(declared, properties)).normalize());
            }
        }
        patterns.forEach(pattern -> roots.add(moduleDir.resolve(pattern).normalize()));
        return roots.stream().filter(path -> path.startsWith(root)).filter(Files::isDirectory).toList();
    }

    private static List<DependencyRecord> dependencies(Pom pom, List<Pom> chain, Map<String, String> properties) {
        Map<String, String> managed = new LinkedHashMap<>();
        for (Pom link : chain) {
            for (Dependency dependency : link.managed()) {
                managed.putIfAbsent(dependency.groupId() + ":" + dependency.artifactId(), dependency.version());
            }
        }
        List<DependencyRecord> records = new ArrayList<>();
        for (Dependency dependency : pom.dependencies()) {
            String groupId = interpolate(dependency.groupId(), properties);
            String artifactId = interpolate(dependency.artifactId(), properties);
            String version = dependency.version() != null ? dependency.version()
                    : managed.get(dependency.groupId() + ":" + dependency.artifactId());
            String scope = dependency.scope() == null ? "compile" : interpolate(dependency.scope(), properties);
            records.add(new DependencyRecord(groupId, artifactId, unresolvedToNull(interpolate(version, properties)),
                    scope));
        }
        return records;
    }

    private static String interpolate(String text, Map<String, String> properties) {
        if (text == null) {
            return null;
        }
        String result = text.strip();
        for (int round = 0; round < MAX_INTERPOLATION_ROUNDS && result.contains("${"); round++) {
            Matcher matcher = PLACEHOLDER.matcher(result);
            StringBuilder next = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String value = properties.get(matcher.group(1));
                if (value != null) {
                    changed = true;
                }
                matcher.appendReplacement(next, Matcher.quoteReplacement(value != null ? value : matcher.group()));
            }
            matcher.appendTail(next);
            result = next.toString();
            if (!changed) {
                break;
            }
        }
        return result;
    }

    private static String unresolvedToNull(String value) {
        return value == null || value.contains("${") ? null : value;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private Optional<Pom> load(Path file, Map<Path, Optional<Pom>> cache, List<String> warnings) {
        Path key = file.normalize();
        Optional<Pom> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Pom> parsed;
        try {
            parsed = Optional.of(parse(key));
        } catch (Exception e) {
            warnings.add("Could not read " + key.getFileName() + " in " + key.getParent().getFileName() + ": "
                    + e.getClass().getSimpleName());
            parsed = Optional.empty();
        }
        cache.put(key, parsed);
        return parsed;
    }

    private static Pom parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element project = builder.parse(file.toFile()).getDocumentElement();
        Element parent = child(project, "parent");
        Map<String, String> properties = new LinkedHashMap<>();
        Element props = child(project, "properties");
        if (props != null) {
            for (Element property : children(props)) {
                properties.put(property.getTagName(), property.getTextContent().strip());
            }
        }
        List<String> modules = new ArrayList<>();
        Element modulesElement = child(project, "modules");
        if (modulesElement != null) {
            for (Element module : children(modulesElement)) {
                if (module.getTagName().equals("module")) {
                    modules.add(module.getTextContent().strip());
                }
            }
        }
        Element management = child(project, "dependencyManagement");
        Element build = child(project, "build");
        return new Pom(file, text(project, "groupId"), text(project, "artifactId"), text(project, "version"),
                parent == null ? null : text(parent, "groupId"), parent == null ? null : text(parent, "artifactId"),
                parent == null ? null : text(parent, "version"), parent == null ? null : text(parent, "relativePath"),
                properties, modules, dependencyList(child(project, "dependencies")),
                dependencyList(management == null ? null : child(management, "dependencies")),
                build == null ? null : text(build, "sourceDirectory"),
                build == null ? null : text(build, "testSourceDirectory"), text(project, "packaging"));
    }

    private static List<Dependency> dependencyList(Element dependencies) {
        List<Dependency> list = new ArrayList<>();
        if (dependencies == null) {
            return list;
        }
        for (Element dependency : children(dependencies)) {
            if (dependency.getTagName().equals("dependency") && text(dependency, "groupId") != null
                    && text(dependency, "artifactId") != null) {
                list.add(new Dependency(text(dependency, "groupId"), text(dependency, "artifactId"),
                        text(dependency, "version"), text(dependency, "scope"), text(dependency, "type")));
            }
        }
        return list;
    }

    private static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent().strip();
        return value.isEmpty() ? null : value;
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}

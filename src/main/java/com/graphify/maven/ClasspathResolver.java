package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves each module's classpath with one Maven run per checkout (spec §3.2 step 5). The compile phase is entered
 * with compilation skipped, so sibling modules resolve to their target/classes without an install and broken sources
 * do not matter; -fae lets every resolvable module produce its file even if another module fails.
 */
@Component
public class ClasspathResolver {

    /** File the maven-dependency-plugin writes per module (relative to the module's basedir). */
    static final String OUTPUT_FILE = "target/graphify-classpath.txt";

    /**
     * Fixed Maven arguments: batch and quiet output, fail at end so every resolvable module writes its file, the
     * compile phase without compiling, copying resources or touching tests, and the classpath written to OUTPUT_FILE.
     */
    static final List<String> MAVEN_FLAGS = List.of("-B", "-q", "-fae", "-Dmaven.main.skip=true",
            "-Dmaven.resources.skip=true", "-Dmaven.test.skip=true", "-Dmdep.outputFile=" + OUTPUT_FILE);

    /** Goals that give sibling modules their target/classes path and print each module's classpath. */
    static final List<String> MAVEN_GOALS = List.of("compile", "dependency:build-classpath");

    /**
     * Environment variables Maven inherits; everything else is dropped. The service keeps its secrets in the
     * environment (APP_MASTER_KEY, DB_PASSWORD; spec §6.1) and a checkout's pom can read any ${env.*}, so Maven gets
     * only what it needs to find java, its home, a locale and a temp directory. MAVEN_OPTS is dropped on purpose.
     */
    static final List<String> INHERITED_ENVIRONMENT = List.of("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "TMPDIR");

    /** How long to wait for a force-killed Maven to exit before giving up on it. */
    static final Duration KILL_WAIT = Duration.ofSeconds(5);

    private final MavenInvocation maven;

    public ClasspathResolver(AppSettings settings, ArtifactRepositories repositories) {
        this.maven = new MavenInvocation(settings, repositories);
    }

    public ClasspathResult resolve(Path checkout, List<MavenModule> modules) {
        Map<String, List<MavenModule>> byRoot = new LinkedHashMap<>();
        modules.stream().filter(MavenModule::hasPom).forEach(m -> byRoot
                .computeIfAbsent(m.projectRoot() == null ? "." : m.projectRoot(), r -> new ArrayList<>()).add(m));
        if (byRoot.isEmpty()) {
            return new ClasspathResult(Map.of(), null);
        }
        Map<String, List<Path>> classpaths = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, List<MavenModule>> entry : byRoot.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                if (errors.stream().noneMatch(e -> e.contains("interrupted"))) {
                    errors.add("Maven classpath resolution was interrupted");
                }
                break;
            }
            ClasspathResult one = resolveRoot(checkout, entry.getKey(), entry.getValue());
            classpaths.putAll(one.classpaths());
            if (one.error() != null) {
                errors.add(byRoot.size() == 1 ? one.error() : "[" + entry.getKey() + "] " + one.error());
            }
        }
        return new ClasspathResult(classpaths, errors.isEmpty() ? null : String.join("\n", errors));
    }

    /** One reactor run in {@code projectRoot}; module paths stay checkout-relative. */
    private ClasspathResult resolveRoot(Path checkout, String projectRoot, List<MavenModule> modules) {
        Path directory = projectRoot.equals(".") ? checkout : checkout.resolve(projectRoot);
        try {
            for (MavenModule module : modules) {
                Files.deleteIfExists(moduleDir(checkout, module).resolve(OUTPUT_FILE));
            }
            List<String> arguments = new ArrayList<>(MAVEN_FLAGS);
            arguments.addAll(MAVEN_GOALS);
            String error = maven.run(directory, arguments);
            Map<String, List<Path>> classpaths = new LinkedHashMap<>();
            for (MavenModule module : modules) {
                Path output = moduleDir(checkout, module).resolve(OUTPUT_FILE);
                if (Files.isRegularFile(output)) {
                    classpaths.put(module.path(), entries(readLenient(output)));
                }
            }
            return new ClasspathResult(classpaths, error);
        } catch (IOException e) {
            return new ClasspathResult(Map.of(), "Maven classpath resolution failed: " + UrlMasking.mask(e.getMessage()));
        }
    }

    /** The parent environment reduced to INHERITED_ENVIRONMENT. */
    static Map<String, String> childEnvironment(Map<String, String> parent) {
        Map<String, String> child = new LinkedHashMap<>();
        for (String key : INHERITED_ENVIRONMENT) {
            String value = parent.get(key);
            if (value != null) {
                child.put(key, value);
            }
        }
        return child;
    }

    /** Reads Maven output as UTF-8, replacing malformed bytes (a non-UTF-8 platform encoding must not lose the file). */
    static String readLenient(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** The last {@code keep} lines of the text, URL credentials masked. */
    static String tail(String text, int keep) {
        List<String> lines = text.lines().toList();
        return UrlMasking.mask(String.join("\n", lines.subList(Math.max(0, lines.size() - keep), lines.size())));
    }

    static List<Path> entries(String classpath) {
        List<Path> entries = new ArrayList<>();
        Arrays.stream(classpath.strip().split(File.pathSeparator)).map(String::strip).filter(s -> !s.isEmpty())
                .map(Path::of).filter(Files::exists).forEach(entries::add);
        return entries;
    }

    private static Path moduleDir(Path root, MavenModule module) {
        return module.path().equals(".") ? root : root.resolve(module.path());
    }

    static Path privateTempFile(String prefix, String suffix) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(prefix, suffix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(prefix, suffix);
    }

    static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a leftover temp file is harmless; the settings file is owner-only
            }
        }
    }
}

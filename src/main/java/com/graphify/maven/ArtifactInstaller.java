package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Installs a scanned repository's artifacts into the local Maven repository so other scanned repositories resolve
 * them (spec §3.2): `install` without tests in each given project root, and a presence check by file layout.
 */
@Component
public class ArtifactInstaller {

    /** Batch, quiet, fail at end, no test compilation or run; the install phase builds and installs the reactor. */
    static final List<String> INSTALL_ARGUMENTS = List.of("-B", "-q", "-fae", "-Dmaven.test.skip=true", "install");

    private final AppSettings settings;
    private final MavenInvocation maven;

    public ArtifactInstaller(AppSettings settings, ArtifactRepositories repositories) {
        this.settings = settings;
        this.maven = new MavenInvocation(settings, repositories);
    }

    /**
     * Installs the roots in the given order (later roots may depend on earlier ones) and stops at the first failing
     * root. Null when every root installed; otherwise the error, prefixed with its root when there are several roots.
     * Once the thread is interrupted no further Maven is launched and the interruption is reported once.
     */
    public String install(Path checkout, Collection<String> projectRoots) {
        List<String> roots = List.copyOf(projectRoots);
        for (String root : roots) {
            if (Thread.currentThread().isInterrupted()) {
                return "Maven install was interrupted";
            }
            Path directory = root.equals(".") ? checkout : checkout.resolve(root);
            String error;
            try {
                error = maven.run(directory, INSTALL_ARGUMENTS);
            } catch (IOException e) {
                error = "Maven install failed: " + UrlMasking.mask(e.getMessage());
            }
            if (error != null) {
                return roots.size() == 1 ? error : "[" + root + "] " + error;
            }
        }
        return null;
    }

    /**
     * Whether the local repository holds the coordinate's files for its packaging: pom packaging, an unresolved
     * (null or containing "${") packaging needs only the .pom; war needs .pom and .war; any other value .pom and .jar.
     */
    public boolean present(Gav gav, String packaging) {
        List<String> segments = new ArrayList<>(List.of(gav.groupId().split("\\.", -1)));
        segments.add(gav.artifactId());
        segments.add(gav.version());
        // the coordinates come from scanned POMs: no empty, dot or separator-bearing segment may build a path
        if (segments.stream().anyMatch(ArtifactInstaller::unsafeSegment)) {
            return false;
        }
        Path local = Path.of(settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY)).toAbsolutePath().normalize();
        Path folder = local;
        for (String segment : segments) {
            folder = folder.resolve(segment);
        }
        folder = folder.normalize();
        if (!folder.startsWith(local)) {
            return false;
        }
        String base = gav.artifactId() + "-" + gav.version();
        if (!Files.isRegularFile(folder.resolve(base + ".pom"))) {
            return false;
        }
        if (packaging == null || packaging.contains("${") || packaging.equals("pom")) {
            return true;
        }
        String extension = packaging.equals("war") ? ".war" : ".jar";
        return Files.isRegularFile(folder.resolve(base + extension));
    }

    private static boolean unsafeSegment(String segment) {
        return segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.contains("/") || segment.contains("\\");
    }
}

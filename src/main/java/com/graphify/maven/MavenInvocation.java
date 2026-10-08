package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs the configured Maven with the generated settings.xml and local repository, without a shell and with the
 * environment reduced to {@link ClasspathResolver#INHERITED_ENVIRONMENT}; the settings file and the output log are
 * owner-only temp files deleted afterwards. Returns null on success, else a masked error with the output's tail.
 */
final class MavenInvocation {

    private final AppSettings settings;
    private final ArtifactRepositories repositories;

    MavenInvocation(AppSettings settings, ArtifactRepositories repositories) {
        this.settings = settings;
        this.repositories = repositories;
    }

    String run(Path directory, List<String> arguments) throws IOException {
        Path settingsFile = null;
        Path log = null;
        try {
            String token = UUID.randomUUID().toString().replace("-", "");
            settingsFile = ClasspathResolver.privateTempFile("graphify-settings", ".xml");
            Files.writeString(settingsFile, SettingsXmlWriter.write(repositories.enabled(), token), StandardCharsets.UTF_8);
            log = ClasspathResolver.privateTempFile("graphify-maven", ".log");
            return execute(directory, arguments, settingsFile, log);
        } finally {
            ClasspathResolver.deleteQuietly(settingsFile);
            ClasspathResolver.deleteQuietly(log);
        }
    }

    private String execute(Path directory, List<String> arguments, Path settingsFile, Path log) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE));
        command.addAll(List.of("-s", settingsFile.toString(),
                "-Dmaven.repo.local=" + settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY)));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().putAll(ClasspathResolver.childEnvironment(System.getenv()));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return UrlMasking.mask("Maven could not start (" + command.getFirst() + "): " + e.getMessage());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Maven never reads stdin; closing it only stops a prompt from waiting for input
        }
        Duration timeout = settings.getDuration(SettingKeys.INDEX_MAVEN_TIMEOUT);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(ClasspathResolver.KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                return "Maven timed out after " + timeout;
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "Maven was interrupted";
        }
        if (process.exitValue() == 0) {
            return null;
        }
        try {
            return "Maven exited with " + process.exitValue() + ":\n"
                    + ClasspathResolver.tail(ClasspathResolver.readLenient(log),
                            settings.getInt(SettingKeys.INDEX_MAVEN_OUTPUT_TAIL_LINES));
        } catch (IOException e) {
            return "Maven exited with " + process.exitValue() + " (output unreadable: "
                    + UrlMasking.mask(e.getMessage()) + ")";
        }
    }
}

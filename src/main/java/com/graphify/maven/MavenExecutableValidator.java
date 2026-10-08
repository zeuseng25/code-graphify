package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.settings.SettingValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Maven command must start and answer {@code -v} within {@code index.maven_check_timeout}. It runs without a
 * shell and with the same reduced environment as indexing (ClasspathResolver.INHERITED_ENVIRONMENT), so it never sees
 * the service's own secrets; its output goes to an owner-only temp file and is never logged.
 */
@Component
public class MavenExecutableValidator implements SettingValidator {

    static final String VERSION_FLAG = "-v";

    private final Supplier<Duration> timeout;

    @Autowired
    public MavenExecutableValidator(AppSettings settings) {
        this(() -> settings.getDuration(SettingKeys.INDEX_MAVEN_CHECK_TIMEOUT));
    }

    MavenExecutableValidator(Supplier<Duration> timeout) {
        this.timeout = timeout;
    }

    @Override
    public Set<String> keys() {
        return Set.of(SettingKeys.INDEX_MAVEN_EXECUTABLE);
    }

    @Override
    public Optional<String> problem(String key, String value) {
        Duration limit = timeout.get(); // before any process starts: a bad setting must not leave one running
        Path output = null;
        try {
            output = ClasspathResolver.privateTempFile("graphify-mvn-check", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of(value, VERSION_FLAG)).redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(ClasspathResolver.childEnvironment(System.getenv()));
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                return Optional.of(UrlMasking.mask("could not be started: " + e.getMessage()));
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // nothing reads stdin; closing it only stops a prompt from waiting
            }
            try {
                if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                    kill(process);
                    return Optional.of("did not answer " + VERSION_FLAG + " within " + limit);
                }
            } catch (InterruptedException e) {
                kill(process);
                Thread.currentThread().interrupt();
                return Optional.of("the check was interrupted");
            }
            if (process.exitValue() == 0) {
                return Optional.empty();
            }
            return Optional.of(UrlMasking.mask("exited with " + process.exitValue() + ": "
                    + lastLine(ClasspathResolver.readLenient(output))));
        } catch (IOException e) {
            return Optional.of(UrlMasking.mask("could not be checked: " + e.getMessage()));
        } finally {
            ClasspathResolver.deleteQuietly(output);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(ClasspathResolver.KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String lastLine(String text) {
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        return lines.isEmpty() ? "(no output)" : lines.getLast();
    }
}

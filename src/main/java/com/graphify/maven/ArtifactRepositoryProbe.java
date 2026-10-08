package com.graphify.maven;

import com.graphify.common.exception.ExternalSystemException;
import com.graphify.scm.AuthorizationHeader;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Checks that a Maven repository answers with the stored credentials: one GET for http(s), a directory check for
 * file: URLs. HTTP 401/403 (rejected credentials), 404 (path not found) and 5xx fail; any other answer means the server and path are reachable.
 */
@Component
public class ArtifactRepositoryProbe {

    /** The credentials were rejected (HTTP 401/403). */
    public static class ArtifactAuthenticationException extends RuntimeException {

        ArtifactAuthenticationException(String message) {
            super(message);
        }
    }

    private final AppSettings settings;

    public ArtifactRepositoryProbe(AppSettings settings) {
        this.settings = settings;
    }

    public void test(ArtifactRepository repository) {
        URI uri = URI.create(repository.url());
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            if (!Files.isDirectory(Path.of(uri))) {
                throw new ExternalSystemException("Repository " + repository.name() + " is not a readable directory");
            }
            return;
        }
        Duration timeout = settings.getDuration(SettingKeys.ARTIFACT_TEST_TIMEOUT);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        AuthorizationHeader.of(repository.username(), repository.secret())
                .ifPresent(header -> request.header("Authorization", header));
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 401 || status == 403) {
                throw new ArtifactAuthenticationException("Repository " + repository.name()
                        + " rejected the credentials (HTTP " + status + ")");
            }
            if (status == 404) {
                throw new ExternalSystemException("Repository " + repository.name() + ": path not found (HTTP 404)");
            }
            if (status >= 500) {
                throw new ExternalSystemException("Repository " + repository.name() + " answered HTTP " + status);
            }
        } catch (IOException e) {
            throw new ExternalSystemException("Repository " + repository.name() + " could not be reached: "
                    + UrlMasking.mask(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalSystemException("Repository test of " + repository.name() + " was interrupted");
        }
    }
}

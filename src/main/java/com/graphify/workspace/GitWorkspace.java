package com.graphify.workspace;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TransportHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shallow clones and updates of repositories under index.workspace_dir (spec §3.2 steps 2–3, §8 clone rows). */
@Component
public class GitWorkspace {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspace.class);
    private static final String HEADS = "refs/heads/";
    /** 4 bytes = 8 hex characters of the SHA-256 of a raw name. */
    private static final int HASH_BYTES = 4;

    static {
        // JGit would send the Authorization header on to whatever host an HTTP redirect names
        HttpTransport.setConnectionFactory(new OriginBoundHttpConnectionFactory());
    }

    private final AppSettings settings;

    public GitWorkspace(AppSettings settings) {
        this.settings = settings;
    }

    public Path directoryFor(long connectionId, String projectKey, String slug) {
        return Path.of(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR))
                .resolve(Long.toString(connectionId)).resolve(segment(projectKey)).resolve(segment(slug));
    }

    public RemoteHead remoteHead(String cloneUrl, Optional<String> authorization) {
        try (AutoCloseable bound = OriginBoundHttpConnectionFactory.bindTo(cloneUrl)) {
            Map<String, Ref> refs = configure(Git.lsRemoteRepository().setRemote(cloneUrl), authorization)
                    .callAsMap();
            Ref head = refs.get("HEAD");
            if (head == null) {
                throw new GitException("Remote " + cloneUrl + " advertises no HEAD");
            }
            if (head.isSymbolic()) {
                Ref target = refs.getOrDefault(head.getTarget().getName(), head.getTarget());
                return new RemoteHead(shortName(head.getTarget().getName()), id(target));
            }
            ObjectId commit = head.getObjectId();
            Optional<Ref> branch = Stream.of("main", "master").map(name -> refs.get(HEADS + name))
                    .filter(ref -> ref != null && commit.equals(ref.getObjectId())).findFirst();
            Ref chosen = branch.orElseGet(() -> refs.values().stream()
                    .filter(ref -> ref.getName().startsWith(HEADS) && commit.equals(ref.getObjectId()))
                    .min(Comparator.comparing(Ref::getName))
                    .orElseThrow(() -> new GitException("Remote " + cloneUrl + " has no branch at HEAD")));
            return new RemoteHead(shortName(chosen.getName()), commit.name());
        } catch (GitException e) {
            throw e;
        } catch (Exception e) {
            String message = "ls-remote " + cloneUrl + " failed: " + e.getMessage();
            if (isAuthenticationFailure(e)) {
                throw new GitAuthenticationException(message);
            }
            throw new GitException(message);
        }
    }

    /** JGit reports a 401 as "not authorized" and a missing credentials provider as "no CredentialsProvider". */
    private static boolean isAuthenticationFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TransportException && t.getMessage() != null
                    && (t.getMessage().contains(JGitText.get().notAuthorized)
                            || t.getMessage().contains(JGitText.get().noCredentialsProvider))) {
                return true;
            }
        }
        return false;
    }

    public String checkout(Path directory, String cloneUrl, String branch, Optional<String> authorization) {
        try (AutoCloseable bound = OriginBoundHttpConnectionFactory.bindTo(cloneUrl)) {
            return checkoutBound(directory, cloneUrl, branch, authorization);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new GitException("checkout " + cloneUrl + " failed: " + e.getMessage());
        }
    }

    private String checkoutBound(Path directory, String cloneUrl, String branch, Optional<String> authorization) {
        if (Files.isDirectory(directory.resolve(".git"))) {
            try {
                if (!cloneUrl.equals(originUrl(directory))) {
                    // a workspace of another repository (or of an older baseUrl) must never be fetched and reused
                    log.info("Workspace {} was cloned from another URL; cloning it again", directory);
                    deleteRecursively(directory);
                } else {
                    return update(directory, branch, authorization);
                }
            } catch (Exception e) {
                log.warn("Workspace {} could not be updated ({}); cloning it again", directory,
                        UrlMasking.mask(e.getMessage()));
                deleteRecursively(directory);
            }
        } else if (Files.exists(directory)) {
            deleteRecursively(directory);
        }
        try {
            return cloneFresh(directory, cloneUrl, branch, authorization);
        } catch (Exception first) {
            deleteRecursively(directory);
            try {
                return cloneFresh(directory, cloneUrl, branch, authorization);
            } catch (Exception second) {
                deleteRecursively(directory);
                throw new GitException("clone " + cloneUrl + " (" + branch + ") failed: " + second.getMessage());
            }
        }
    }

    private String cloneFresh(Path directory, String cloneUrl, String branch, Optional<String> authorization)
            throws Exception {
        Files.createDirectories(directory.getParent());
        try (Git git = configure(Git.cloneRepository().setURI(cloneUrl).setDirectory(directory.toFile())
                .setBranch(branch).setCloneAllBranches(false).setDepth(settings.getInt(SettingKeys.INDEX_GIT_DEPTH)),
                authorization).call()) {
            return git.getRepository().resolve("HEAD").name();
        }
    }

    private static String originUrl(Path directory) throws IOException {
        try (Git git = Git.open(directory.toFile())) {
            return git.getRepository().getConfig().getString("remote", "origin", "url");
        }
    }

    private String update(Path directory, String branch, Optional<String> authorization) throws Exception {
        try (Git git = Git.open(directory.toFile())) {
            String remoteRef = "refs/remotes/origin/" + branch;
            configure(git.fetch().setRemote("origin").setRefSpecs(new RefSpec("+" + HEADS + branch + ":" + remoteRef))
                    .setDepth(settings.getInt(SettingKeys.INDEX_GIT_DEPTH)), authorization).call();
            ObjectId target = git.getRepository().resolve(remoteRef);
            if (target == null) {
                throw new GitException("fetch did not produce " + remoteRef);
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.name()).call();
            git.clean().setCleanDirectories(true).setForce(true).setIgnore(false).call();
            return target.name();
        }
    }

    private <C extends TransportCommand<C, ?>> C configure(C command, Optional<String> authorization) {
        int timeoutSeconds = (int) Math.max(1, settings.getDuration(SettingKeys.INDEX_GIT_TIMEOUT).toSeconds());
        return command.setTimeout(timeoutSeconds).setTransportConfigCallback(transport -> {
            if (transport instanceof TransportHttp http && authorization.isPresent()) {
                http.setAdditionalHeaders(Map.of("Authorization", authorization.get()));
            }
        });
    }

    private static String id(Ref ref) {
        if (ref.getObjectId() == null) {
            throw new GitException("Remote branch " + ref.getName() + " has no commit");
        }
        return ref.getObjectId().name();
    }

    private static String shortName(String refName) {
        return refName.startsWith(HEADS) ? refName.substring(HEADS.length()) : refName;
    }

    /** One path segment that can never be empty, "." or "..", so a directory is always strictly inside its parent. */
    private static String safe(String segment) {
        if (segment == null || segment.isBlank()) {
            return "_";
        }
        String cleaned = segment.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "__");
        return cleaned.equals(".") ? "_" : cleaned;
    }

    /**
     * The readable form of safe(), plus a short hash of the raw value whenever safe() changed it, so two raw names
     * that clean to the same text (a/b and a_b) get different directories. "~" cannot occur in a cleaned segment.
     */
    private static String segment(String raw) {
        String cleaned = safe(raw);
        if (raw != null && cleaned.equals(raw)) {
            return cleaned;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            return cleaned + "~" + HexFormat.of().formatHex(digest, 0, HASH_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new GitException("could not delete workspace " + directory + ": " + e.getMessage());
        }
    }
}

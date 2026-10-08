package com.graphify.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

/** Local bare git repositories on branch {@code main}, reachable by {@code file://} URLs. */
public final class GitFixtures {

    private GitFixtures() {
    }

    public static Path bareRepository(Path dir, String name, Map<String, String> files) throws IOException {
        Path bare = dir.resolve(name + ".git");
        try {
            Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
            Path seed = dir.resolve(name + "-seed");
            try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
                git.remoteAdd().setName("origin").setUri(new URIish(url(bare))).call();
                write(seed, files);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
                git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main")).call();
            }
            return bare;
        } catch (GitAPIException | java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    public static String commit(Path bare, Map<String, String> files, String message) throws IOException {
        Path work = Files.createTempDirectory("git-fixture");
        try (Git git = Git.cloneRepository().setURI(url(bare)).setDirectory(work.toFile()).setBranch("main").call()) {
            write(work, files);
            git.add().addFilepattern(".").call();
            String id = git.commit().setMessage(message).setAuthor("t", "t@t").setCommitter("t", "t@t").call().name();
            git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main")).call();
            return id;
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

    /** Pushes a commit that deletes the given paths; returns the new commit id. */
    public static String remove(Path bare, Collection<String> paths, String message) throws IOException {
        Path work = Files.createTempDirectory("git-fixture");
        try (Git git = Git.cloneRepository().setURI(url(bare)).setDirectory(work.toFile()).setBranch("main").call()) {
            for (String path : paths) {
                git.rm().addFilepattern(path).call();
            }
            String id = git.commit().setMessage(message).setAuthor("t", "t@t").setCommitter("t", "t@t").call().name();
            git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main")).call();
            return id;
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

    public static String url(Path bare) {
        return bare.toUri().toString();
    }

    private static void write(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }
}

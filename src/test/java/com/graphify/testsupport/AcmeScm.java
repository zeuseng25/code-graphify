package com.graphify.testsupport;

import com.graphify.common.crypto.SecretCipher;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repositories that provide artifacts to each other (Plan 16), behind a fake Bitbucket; group
 * {@value #GROUP}, version {@value #VERSION}. Nothing is published to a file repository, so a consumer resolves only
 * after its providers were installed into the local repository.
 * <ul>
 * <li>{@code acme-parent}: a pom-only parent;</li>
 * <li>{@code acme-lib}: a jar with {@code Greeter}, whose parent is acme-parent;</li>
 * <li>{@code acme-app}: uses {@code Greeter}; its parent is acme-parent and it depends on acme-lib;</li>
 * <li>{@code acme-gateway}: no root pom, two independent sub-projects, one in a folder with a space.</li>
 * </ul>
 */
public final class AcmeScm implements AutoCloseable {

    public static final String TOKEN = "acme-token";
    public static final String GROUP = "com.graphify.testfixture.acme";
    public static final String VERSION = "1.0-SNAPSHOT";

    private final FakeBitbucket bitbucket;
    private final Path dir;
    private final Path libBare;

    private AcmeScm(FakeBitbucket bitbucket, Path dir, Path libBare) {
        this.bitbucket = bitbucket;
        this.dir = dir;
        this.libBare = libBare;
    }

    static String properties() {
        return "<properties><maven.compiler.release>17</maven.compiler.release>"
                + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>";
    }

    static String parentRef() {
        return "<parent><groupId>" + GROUP + "</groupId><artifactId>acme-parent</artifactId><version>" + VERSION
                + "</version><relativePath/></parent>";
    }

    public static AcmeScm create(JdbcTemplate jdbc, SecretCipher cipher, Path dir) throws IOException {
        Path parentBare = GitFixtures.bareRepository(dir, "acme-parent", Map.of("pom.xml",
                MavenFixtures.pom(GROUP, "acme-parent", VERSION, "<packaging>pom</packaging>" + properties(), "")));
        Path libBare = GitFixtures.bareRepository(dir, "acme-lib", Map.of(
                "pom.xml", MavenFixtures.pom(GROUP, "acme-lib", VERSION, parentRef(), ""),
                "src/main/java/com/graphify/testfixture/acme/lib/Greeter.java",
                "package com.graphify.testfixture.acme.lib; public class Greeter { public String greet() { return \"hi\"; } }"));
        Path appBare = GitFixtures.bareRepository(dir, "acme-app", Map.of(
                "pom.xml", MavenFixtures.pom(GROUP, "acme-app", VERSION, parentRef(),
                        MavenFixtures.dependency(GROUP, "acme-lib", VERSION)),
                "src/main/java/com/graphify/testfixture/acme/app/App.java",
                "package com.graphify.testfixture.acme.app; import com.graphify.testfixture.acme.lib.Greeter; "
                        + "public class App { String run() { return new Greeter().greet(); } }"));
        Path gatewayBare = GitFixtures.bareRepository(dir, "acme-gateway", Map.of(
                "Payment Service/pom.xml", MavenFixtures.pom(GROUP, "payment", VERSION, properties(), ""),
                "Payment Service/src/main/java/acme/pay/Pay.java", "package acme.pay; public class Pay {}",
                "orders/pom.xml", MavenFixtures.pom(GROUP, "orders", VERSION, properties(), ""),
                "orders/src/main/java/acme/orders/Order.java", "package acme.orders; public class Order {}"));
        FakeBitbucket bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("ACME", "acme-parent", GitFixtures.url(parentBare))
                .addRepository("ACME", "acme-lib", GitFixtures.url(libBare))
                .addRepository("ACME", "acme-app", GitFixtures.url(appBare))
                .addRepository("ACME", "acme-gateway", GitFixtures.url(gatewayBare));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, secret_enc) VALUES ('acme', 'BITBUCKET_DC', ?, ?)",
                bitbucket.baseUrl(), cipher.encrypt(TOKEN));
        return new AcmeScm(bitbucket, dir, libBare);
    }

    /**
     * Adds two repositories that need each other's jar ({@code acme-cycle-a} and {@code acme-cycle-b}); listed from
     * the next sync on.
     */
    public AcmeScm addCycle() throws IOException {
        for (List<String> pair : List.of(List.of("acme-cycle-a", "acme-cycle-b"), List.of("acme-cycle-b", "acme-cycle-a"))) {
            String name = pair.get(0);
            String other = pair.get(1);
            String pkg = name.replace("-", "");
            Path bare = GitFixtures.bareRepository(dir, name, Map.of(
                    "pom.xml", MavenFixtures.pom(GROUP, name, VERSION, properties(),
                            MavenFixtures.dependency(GROUP, other, VERSION)),
                    "src/main/java/acme/" + pkg + "/Node.java", "package acme." + pkg + "; public class Node {}"));
            bitbucket.addRepository("ACME", name, GitFixtures.url(bare));
        }
        return this;
    }

    public Path libBare() {
        return libBare;
    }

    /** Removes {@value #GROUP} from a local repository (the only group these tests install into ~/.m2). */
    public static void deleteLocalGroup(Path localRepository) throws IOException {
        MavenFixtures.deleteGroup(localRepository, GROUP);
    }

    @Override
    public void close() {
        bitbucket.close();
    }
}

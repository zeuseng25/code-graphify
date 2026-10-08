package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.indexing.IndexLock;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.FakeBitbucket;
import com.graphify.testsupport.FakeGitHub;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ScmConnectionAdminApiTest extends OracleIntegrationTest {

    private static final String TOKEN = "scm-token-7";

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    @Autowired
    IndexLock lock;

    private FakeBitbucket bitbucket;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SCM_CONNECTION%'");
        bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("SHOP", "api", "https://scm/a.git");
    }

    @AfterEach
    void tearDown() {
        lock.forceRelease();
        bitbucket.close();
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SCM_CONNECTION%'");
    }

    private String body(String name, String baseUrl, String secret) {
        return """
                {"name":"%s","type":"BITBUCKET_DC","baseUrl":"%s","username":null,%s
                 "includeProjects":[" SHOP ","PAY"],"excludeRepos":["SHOP/old-*"],"repositoryUrls":[],"enabled":true}
                """.formatted(name, baseUrl, secret == null ? "" : "\"secret\":\"" + secret + "\",");
    }

    private long create(String name, String secret) throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body(name, bitbucket.baseUrl() + "/", secret)).exchange();
        assertThat(created).hasStatus(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void createsShowsAndTestsAConnectionWithoutRevealingItsSecret() throws Exception {
        long id = create("corp", TOKEN);

        assertThat(mvc.get().uri("/api/v1/admin/scm-connections/" + id)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.baseUrl").isEqualTo(bitbucket.baseUrl());
            assertThat(json).extractingPath("$.secretSet").isEqualTo(true);
            assertThat(json).extractingPath("$.includeProjects").asArray().containsExactly("SHOP", "PAY");
            assertThat(json).extractingPath("$.repositoryCount").isEqualTo(0);
        });
        assertThat(mvc.get().uri("/api/v1/admin/scm-connections")).hasStatusOk().bodyText().doesNotContain(TOKEN);
        assertThat(connections.find(id).orElseThrow().secret()).isEqualTo(TOKEN);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatusOk().bodyJson()
                .extractingPath("$.ok").isEqualTo(true);
        assertThat(bitbucket.requestedStarts()).containsExactly("0");
        assertThat(mvc.get().uri("/api/v1/admin/scm-connections/" + id)).hasStatusOk().bodyJson()
                .extractingPath("$.lastTestStatus").isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT NVL(details, '-') FROM audit_log WHERE action LIKE 'SCM_CONNECTION%'",
                String.class)).isNotEmpty().noneMatch(details -> details.contains(TOKEN));
    }

    @Test
    void aRejectedTokenIsRecordedAsAuthFailedAndAnswers502() throws Exception {
        long id = create("corp", "wrong-token");

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatus(502).bodyText()
                .doesNotContain("wrong-token");
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM scm_connection WHERE id = ?", String.class, id))
                .isEqualTo("AUTH_FAILED");
    }

    @Test
    void testsAGitHubConnection() throws Exception {
        try (FakeGitHub github = new FakeGitHub().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("shop", "api", "https://github.test/shop/api.git")) {
            long id = createGitHub("hub", github.baseUrl(), TOKEN);
            assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatusOk().bodyJson()
                    .extractingPath("$.ok").isEqualTo(true);
            assertThat(mvc.get().uri("/api/v1/admin/scm-connections/" + id)).hasStatusOk().bodyJson()
                    .extractingPath("$.lastTestStatus").isEqualTo("SUCCESS");

            long wrong = createGitHub("hub-wrong", github.baseUrl(), "wrong-token");
            assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + wrong + "/test")).hasStatus(502).bodyText()
                    .doesNotContain("wrong-token");
            assertThat(jdbc.queryForObject("SELECT last_test_status FROM scm_connection WHERE id = ?", String.class,
                    wrong)).isEqualTo("AUTH_FAILED");
        }
    }

    @Test
    void testsAGitHubConnectionThatListsOnlyItsOwnRepositories() throws Exception {
        try (FakeGitHub github = new FakeGitHub().start().requireAuthorization("Bearer " + TOKEN)) {
            String json = """
                    {"name":"own","type":"GITHUB","baseUrl":"%s","username":null,"secret":"%s",
                     "includeProjects":[],"excludeRepos":[],"repositoryUrls":[],"includeOwnRepositories":true,
                     "enabled":true}
                    """.formatted(github.baseUrl(), TOKEN);
            MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections")
                    .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
            assertThat(created).hasStatus(201);
            long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

            assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatusOk().bodyJson()
                    .extractingPath("$.ok").isEqualTo(true);
            assertThat(github.requestedPaths()).containsExactly("/user");
        }
    }

    private long createGitHub(String name, String baseUrl, String secret) throws Exception {
        String json = """
                {"name":"%s","type":"GITHUB","baseUrl":"%s","username":null,"secret":"%s",
                 "includeProjects":["shop"],"excludeRepos":[],"repositoryUrls":[],"enabled":true}
                """.formatted(name, baseUrl, secret);
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(json).exchange();
        assertThat(created).hasStatus(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void aChangedTargetNeedsTheSecretAgain() throws Exception {
        long id = create("corp", TOKEN);

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", "https://elsewhere.example", null))).hasStatus(400);
        assertThat(connections.find(id).orElseThrow().baseUrl()).isEqualTo(bitbucket.baseUrl());

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp-renamed", bitbucket.baseUrl(), null))).hasStatusOk().bodyJson()
                .extractingPath("$.secretSet").isEqualTo(true);
        assertThat(connections.find(id).orElseThrow().secret()).isEqualTo(TOKEN);
    }

    @Test
    void repointingAConnectionDeactivatesItsRepositoriesButRenamingDoesNot() throws Exception {
        long id = create("corp", TOKEN);
        jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url) VALUES (?, 'SHOP', 'api', "
                + "'" + bitbucket.baseUrl() + "/scm/shop/api.git')", id);
        long repoId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE connection_id = ?", Long.class, id);

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp-renamed", bitbucket.baseUrl(), null))).hasStatusOk();
        assertThat(jdbc.queryForObject("SELECT active FROM scm_repository WHERE id = ?", Integer.class, repoId))
                .isEqualTo(1);

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp-renamed", "https://elsewhere.example", "new-token"))).hasStatusOk();
        assertThat(jdbc.queryForObject("SELECT active FROM scm_repository WHERE id = ?", Integer.class, repoId))
                .isEqualTo(0);
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\",\"id\":" + repoId + "}")).hasStatus(409);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_log WHERE action = 'SCM_CONNECTION_UPDATED' "
                + "AND details LIKE '%baseUrl%'", String.class)).contains("repositories deactivated until the next sync");
    }

    @Test
    void repointingWaitsForNoIndexRunButRenamingDoesNot() throws Exception {
        long id = create("corp", TOKEN);
        jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url) VALUES (?, 'SHOP', 'api', "
                + "'https://scm/a.git')", id);
        assertThat(lock.tryAcquire(IndexLock.runHolder(999))).isTrue();

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", "https://elsewhere.example", "new-token"))).hasStatus(409);
        assertThat(connections.find(id).orElseThrow().baseUrl()).isEqualTo(bitbucket.baseUrl());
        assertThat(jdbc.queryForObject("SELECT active FROM scm_repository WHERE connection_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp-renamed", bitbucket.baseUrl(), null))).hasStatusOk();
        assertThat(lock.holder()).contains(IndexLock.runHolder(999));

        lock.release(IndexLock.runHolder(999));
        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp-renamed", "https://elsewhere.example", "new-token"))).hasStatusOk();
        assertThat(lock.holder()).isEmpty();
    }

    @Test
    void aConnectionWithRepositoriesCannotBeDeleted() throws Exception {
        long id = create("corp", TOKEN);
        jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url) VALUES (?, 'SHOP', 'api', "
                + "'https://scm/a.git')", id);

        assertThat(mvc.delete().uri("/api/v1/admin/scm-connections/" + id)).hasStatus(409);
        assertThat(connections.find(id)).isPresent();

        jdbc.update("DELETE FROM scm_repository WHERE connection_id = ?", id);
        assertThat(mvc.delete().uri("/api/v1/admin/scm-connections/" + id)).hasStatus(204);
        assertThat(connections.find(id)).isEmpty();
        assertThat(mvc.delete().uri("/api/v1/admin/scm-connections/" + id)).hasStatus(404);
    }

    @Test
    void validatesInputAndUniqueness() throws Exception {
        create("corp", TOKEN);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", bitbucket.baseUrl(), TOKEN))).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("other", "ftp://scm", TOKEN))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("", bitbucket.baseUrl(), TOKEN))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("third", bitbucket.baseUrl(), TOKEN).replace("\"PAY\"", "\"A,B\""))).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/scm-connections")).hasStatus(403);
    }

    @Test
    void rejectsBaseUrlsWithCredentialsOrBadShape() throws Exception {
        for (String url : new String[] {"https://user:topsecret@host", "http:///x", "https:host", "https://host/x?a=b",
                "https://host/x#frag"}) {
            assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                    .content(body("bad", url, TOKEN))).hasStatus(400).bodyText().doesNotContain("topsecret");
        }
        assertThat(connections.views()).isEmpty();
    }

    @Test
    void anEmptySecretClearsIt() throws Exception {
        long id = create("corp", TOKEN);

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", bitbucket.baseUrl(), ""))).hasStatusOk().bodyJson()
                .extractingPath("$.secretSet").isEqualTo(false);
    }

    @Test
    void aFailedTestIsRecordedAnswers502AndIsNotRetried() throws Exception {
        long id = create("corp", TOKEN);
        bitbucket.failNext(500, 5);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatus(502);
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM scm_connection WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(bitbucket.requestedStarts()).hasSize(1);
        assertThat(bitbucket.requestedLimits()).containsExactly("1");
    }

    @Test
    void aUrlTheHttpClientRefusesIsAFailedTestNotA500() throws Exception {
        long id = create("corp", TOKEN);
        jdbc.update("UPDATE scm_connection SET base_url = 'http:///x' WHERE id = ?", id);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatus(502);
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM scm_connection WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
    }

    @Test
    void unknownIdsAnswer404() throws Exception {
        assertThat(mvc.get().uri("/api/v1/admin/scm-connections/999999")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/999999").contentType(MediaType.APPLICATION_JSON)
                .content(body("x", bitbucket.baseUrl(), TOKEN))).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/999999/test")).hasStatus(404);
    }

    @Test
    void aChangedUsernameNeedsTheSecretAgain() throws Exception {
        long id = create("corp", TOKEN);

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", bitbucket.baseUrl(), null).replace("\"username\":null", "\"username\":\"bob\"")))
                .hasStatus(400);
    }

    @Test
    void renamingIsAuditedWithTheOldName() throws Exception {
        long id = create("corp", TOKEN);
        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp2", bitbucket.baseUrl(), null))).hasStatusOk();

        assertThat(jdbc.queryForObject("SELECT details FROM audit_log WHERE action = 'SCM_CONNECTION_UPDATED'",
                String.class)).contains("name (was corp)").doesNotContain(TOKEN);
    }

    private static final String GIT_BASE = "https://git.corp/scm";

    private String gitBody(String name, String urls) {
        return gitBody(name, GIT_BASE, urls);
    }

    private String gitBody(String name, String baseUrl, String urls) {
        return """
                {"name":"%s","type":"GIT","baseUrl":"%s","username":null,"includeProjects":[],"excludeRepos":[],
                 "repositoryUrls":%s,"enabled":true}
                """.formatted(name, baseUrl, urls);
    }

    private String gitHubBody(String name, String orgs, String secret) {
        return """
                {"name":"%s","type":"GITHUB","baseUrl":"https://github.corp/api/v3","username":null,%s
                 "includeProjects":%s,"excludeRepos":[],"repositoryUrls":[],"enabled":true}
                """.formatted(name, secret == null ? "" : "\"secret\":\"" + secret + "\",", orgs);
    }

    @Test
    void createsAGitConnectionWithItsUrls() throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(gitBody("git", "[\"" + GIT_BASE + "/team/api.git\",\"" + GIT_BASE + "/solo.git/\"]"))
                .exchange();

        assertThat(created).hasStatus(201).bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.repositoryUrls").asArray()
                    .containsExactly(GIT_BASE + "/team/api.git", GIT_BASE + "/solo.git");
            assertThat(json).extractingPath("$.secretSet").isEqualTo(false);
            assertThat(json).extractingPath("$.includeProjects").asArray().isEmpty();
        });
    }

    @Test
    void aGitHubConnectionNeedsATokenAndAnOrganization() throws Exception {
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(gitHubBody("gh", "[\"acme\"]", null))).hasStatus(400).bodyText()
                .contains("A GitHub connection needs a token");
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(gitHubBody("gh", "[]", "ghp_x"))).hasStatus(400).bodyText()
                .contains("at least one GitHub organization");
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(gitHubBody("gh", "[\"acme\"]", "ghp_x")).exchange();
        assertThat(created).hasStatus(201);
        long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(gitHubBody("gh", "[\"acme\"]", ""))).hasStatus(400).bodyText()
                .contains("A GitHub connection needs a token");
        assertThat(connections.find(id).orElseThrow().secret()).isEqualTo("ghp_x");
    }

    @Test
    void theTypeOfASavedConnectionIsFixed() throws Exception {
        long id = create("corp", TOKEN);
        jdbc.update("DELETE FROM audit_log WHERE action = 'SCM_CONNECTION_UPDATED'");

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(gitBody("corp", "[\"" + GIT_BASE + "/a/b.git\"]"))).hasStatus(400).bodyText()
                .contains("type cannot be changed");
        assertThat(mvc.get().uri("/api/v1/admin/scm-connections/" + id)).hasStatusOk().bodyJson()
                .extractingPath("$.type").isEqualTo("BITBUCKET_DC");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SCM_CONNECTION_UPDATED'",
                Integer.class)).isZero();
    }

    @Test
    void testingAGitConnectionThatCannotBeReadIsAClearBadGatewayNotA500() throws Exception {
        // an unreachable local port: hermetic and fast
        String base = "http://127.0.0.1:1/scm";
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(gitBody("git", base, "[\"" + base + "/a/b.git\"]")).exchange();
        assertThat(created).hasStatus(201);
        long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatus(502).bodyText()
                .contains("could not read");
    }

    @Test
    void aGitConnectionTakesMoreRepositoryUrlsThanTheOtherListsCouldHold() throws Exception {
        StringBuilder urls = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            urls.append(i == 0 ? "" : ",").append("\"").append(GIT_BASE).append("/team/").append("r".repeat(40))
                    .append(i).append(".git\"");
        }
        urls.append("]");

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(gitBody("git", urls.toString()))).hasStatus(201).bodyJson()
                .extractingPath("$.repositoryUrls.length()").isEqualTo(100);
    }

    private String ownGitHubBody(String type, String orgs, boolean own) {
        return """
                {"name":"own-%s","type":"%s","baseUrl":"https://github.corp/api/v3","username":null,"secret":"ghp_x",
                 "includeProjects":%s,"excludeRepos":[],"repositoryUrls":[],"includeOwnRepositories":%s,"enabled":true}
                """.formatted(type.toLowerCase(java.util.Locale.ROOT), type, orgs, own);
    }

    @Test
    void aGitHubConnectionCanListOnlyTheTokenOwnersRepositories() throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(ownGitHubBody("GITHUB", "[]", true)).exchange();
        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.includeOwnRepositories").isEqualTo(true);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(ownGitHubBody("BITBUCKET_DC", "[]", true))).hasStatus(400).bodyText()
                .contains("includeOwnRepositories");
    }
}

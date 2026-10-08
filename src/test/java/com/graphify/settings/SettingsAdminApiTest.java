package com.graphify.settings;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.testsupport.SettingsOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class SettingsAdminApiTest extends OracleIntegrationTest {

    @Autowired
    AppSettings settings;

    private SettingsOverride overrides;

    @BeforeEach
    void setUp() {
        overrides = new SettingsOverride(settings);
        overrides.set(SettingKeys.API_PAGE_DEFAULT_SIZE, settings.all().stream()
                .filter(s -> s.key().equals(SettingKeys.API_PAGE_DEFAULT_SIZE)).findFirst().orElseThrow().value());
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
    }

    @Test
    void listsEverySettingWithItsTypeAndBounds() {
        assertThat(mvc.get().uri("/api/v1/admin/settings")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$[?(@.key == 'index.parallelism')].type").asArray().containsExactly("INT");
            assertThat(json).extractingPath("$[?(@.key == 'index.parallelism')].minValue").asArray().containsExactly(1);
        });
    }

    @Test
    void updatesAValueAndRecordsWhoChangedIt() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/api.page_default_size").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"40\"}")).hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.value").isEqualTo("40");
                    assertThat(json).extractingPath("$.updatedBy").isEqualTo(TEST_ACTOR);
                });
        assertThat(settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE)).isEqualTo(40);
    }

    @Test
    void rejectsBadValuesUnknownKeysAndNonAdmins() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.parallelism").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"0\"}")).hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .contains("index.parallelism");
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.cron").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"not a cron\"}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/settings/no.such.key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"1\"}")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.parallelism").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/settings")).hasStatus(403);
    }

    @TempDir
    Path dir;

    private void remember(String key) {
        overrides.set(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().value());
    }

    @Test
    void savesAWritableDirectoryAndCreatesIt() {
        remember(SettingKeys.INDEX_WORKSPACE_DIR);
        Path target = dir.resolve("repos");

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + target + "\"}")).hasStatusOk();
        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo(target.toString());
        assertThat(target).isDirectory();
    }

    @Test
    void refusesAnUnusableDirectoryAndKeepsTheOldValue() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root can write anywhere");
        remember(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
        String before = settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
        Path readOnly = Files.createDirectory(dir.resolve("ro"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(mvc.put().uri("/api/v1/admin/settings/index.maven_local_repository")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + readOnly.resolve("m2") + "\"}"))
                    .hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                    .contains("index.maven_local_repository").contains("directory is not usable").contains(readOnly.toString());
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY)).isEqualTo(before);
    }

    @Test
    void refusesARelativeDirectory() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"data/repos\"}")).hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .contains("absolute path");
    }

    @Test
    void refusesAMavenThatCannotRunAndKeepsTheOldValue() {
        String before = settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE);

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.maven_executable").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + dir.resolve("no-such-mvn") + "\"}")).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("index.maven_executable").contains("could not be started");
        assertThat(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE)).isEqualTo(before);
    }

    @Test
    void savesAMavenThatAnswersVersion() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        remember(SettingKeys.INDEX_MAVEN_EXECUTABLE);
        Path mvn = dir.resolve("mvn");
        Files.writeString(mvn, "#!/bin/sh\necho 'Apache Maven 3.9.9'\n");
        Files.setPosixFilePermissions(mvn, PosixFilePermissions.fromString("rwx------"));

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.maven_executable").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + mvn + "\"}")).hasStatusOk();
        assertThat(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE)).isEqualTo(mvn.toString());
    }

    @Test
    void anUnknownKeyIsStillNotFoundAndATypeErrorComesFirst() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/no.such.key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"/tmp\"}")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"   \"}")).hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .contains("must not be blank");
    }

    @Test
    void storesTheNormalisedPathItChecked() {
        remember(SettingKeys.INDEX_WORKSPACE_DIR);

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + dir + "/a/../b\"}")).hasStatusOk();
        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo(dir.resolve("b").toString());
        assertThat(dir.resolve("b")).isDirectory();
    }
}

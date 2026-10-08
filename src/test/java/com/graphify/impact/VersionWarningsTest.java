package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class VersionWarningsTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    ImpactService impact;

    @TempDir
    Path work;

    @BeforeEach
    void setUp() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        dependency("shop-api", "com.graphify.testfixture.shop", "shop-lib", "0.9.0");
        dependency("shop-api", "org.other", "thing", "1.0.0");
        dependency("shop-legacy", "com.graphify.testfixture.shop", "shop-lib", "1.0.0");
    }

    private void dependency(String modulePath, String groupId, String artifactId, String version) {
        jdbc.update("""
                INSERT INTO module_dependency (module_id, group_id, artifact_id, version, scope)
                SELECT id, ?, ?, ?, 'compile' FROM maven_module WHERE path = ?
                """, groupId, artifactId, version, modulePath);
    }

    private ImpactResult analyzeFormat() {
        return impact.analyze(new ImpactRequest(
                List.of(ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)")), null, 3, null, null));
    }

    @Test
    void flagsAffectedModulesThatUseAnotherVersionOfTheChangedArtifact() {
        assertThat(analyzeFormat().versionWarnings())
                .extracting(w -> w.repository().slug(), VersionWarning::modulePath, VersionWarning::dependency,
                        VersionWarning::usedVersion, VersionWarning::declaredVersion)
                .containsExactly(tuple("shop-api", "shop-api", "com.graphify.testfixture.shop:shop-lib", "0.9.0", "1.0.0"));
    }

    @Test
    void matchingVersionsGiveNoWarning() {
        jdbc.update("UPDATE module_dependency SET version = '1.0.0' WHERE artifact_id = 'shop-lib'");

        assertThat(analyzeFormat().versionWarnings()).isEmpty();
    }
}

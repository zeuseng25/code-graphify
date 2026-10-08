package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.GraphFixture;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class ClassGraphLoaderTest extends OracleIntegrationTest {

    @Autowired
    ClassGraphLoader loader;

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    Path work;

    @Test
    void loadsTheRepositoryClassesAndTheirUsagesBetweenEachOther() {
        long repo = GraphFixture.load(jdbc, writer);

        ClassGraph graph = loader.load(repo, false);

        assertThat(graph.classes().keySet()).containsExactly("com.g.a.Alpha", "com.g.a.AlphaHelper", "com.g.b.Beta",
                "com.g.c.Delta", "com.g.c.Gamma", "com.g.web.Api", "com.g.web.Endpoint");
        assertThat(graph.classes().get("com.g.a.Alpha").modulePath()).isEqualTo("core");
        assertThat(graph.classes().get("com.g.c.Gamma").modulePath()).isEqualTo("app");
        assertThat(graph.edges()).extracting(e -> e.fromFqn() + ">" + e.toFqn()).contains(
                "com.g.a.Alpha>com.g.b.Beta", "com.g.b.Beta>com.g.a.AlphaHelper", "com.g.c.Gamma>com.g.a.Alpha",
                "com.g.c.Delta>com.g.c.Gamma", "com.g.web.Api>com.g.web.Endpoint")
                .noneMatch(pair -> pair.split(">")[0].equals(pair.split(">")[1]));
        assertThat(graph.edges()).anySatisfy(e -> {
            assertThat(e.fromFqn()).isEqualTo("com.g.b.Beta");
            assertThat(e.kind()).isEqualTo(UsageKind.CALL);
        });
        assertThat(graph.external()).isEmpty();
    }

    @Test
    void groupsTypesFromOtherRepositoriesWhenAsked() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        long api = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);

        assertThat(loader.load(api, true).external()).extracting(ExternalEdge::group)
                .contains(new ExternalGroup("repo:TEST/shop-lib", "TEST/shop-lib", NodeType.EXTERNAL_REPOSITORY));
        assertThat(loader.load(api, false).external()).isEmpty();
    }

    @Test
    void loadsAClassMembersWithTheirCallersAndCallees() {
        long repo = GraphFixture.load(jdbc, writer);

        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES ('com.g.a.Alpha#gone()', 'METHOD', 'com.g.a.Alpha', 'gone', 'gone()', 'SOURCE', 0)""");

        MemberGraph members = loader.members(repo, "com.g.a.Alpha");

        assertThat(members.focusMembers()).extracting(MemberRef::key).contains("com.g.a.Alpha#run()");
        assertThat(members.focusMembers()).extracting(MemberRef::key).doesNotContain("com.g.a.Alpha#gone()");
        assertThat(members.uses()).extracting(u -> u.from().key() + ">" + u.to().key()).contains(
                "com.g.a.Alpha#run()>com.g.a.AlphaHelper#one()", "com.g.c.Gamma#total()>com.g.a.Alpha#run()");
    }

    @Test
    void withoutExternalTypesTheInternalEdgesAreUnchanged() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        long api = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);

        ClassGraph internal = loader.load(api, false);
        ClassGraph all = loader.load(api, true);

        assertThat(internal.external()).isEmpty();
        assertThat(internal.edges()).isNotEmpty().isEqualTo(all.edges());
        assertThat(all.external()).isNotEmpty();
        assertThat(loader.loadClasses(api).classes()).isEqualTo(all.classes());
        assertThat(loader.loadClasses(api).edges()).isEmpty();
    }
}

package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.IndexWarning;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Method keys stay fully qualified when a parameter type is missing from the classpath (spec §4.2). */
class RecoveredSignatureTest {

    private static final String SVC = """
            package com.corp.app;
            import com.corp.dto.OrderDto;
            public class Svc {
                public void handle(OrderDto d) { "x".length(); }
            }
            """;

    private static final String CALLER = """
            package com.corp.web;
            import com.corp.app.Svc;
            class Caller {
                void run(Svc svc) { svc.handle(null); }
            }
            """;

    private static final String HANDLE = "com.corp.app.Svc#handle(com.corp.dto.OrderDto)";

    @TempDir
    Path dir;

    @Test
    void declarationKeyQualifiesMissingParameterTypeThroughImports() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/Svc.java", SVC).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey).contains(HANDLE);
    }

    @Test
    void callFromAnotherModuleUsesTheSameKeyAndIsRecovered() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "com/corp/app/Svc.java", SVC)
                .java("web", "com/corp/web/Caller.java", CALLER)
                .index();

        assertThat(usage(result, "com.corp.web.Caller#run(com.corp.app.Svc)", UsageKind.CALL, HANDLE).confidence())
                .isEqualTo(Confidence.RECOVERED);
    }

    @Test
    void fromKeyOfMethodWithMissingParameterTypeIsQualified() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/Svc.java", SVC).index();

        usage(result, HANDLE, UsageKind.CALL, "java.lang.String#length()");
    }

    @Test
    void samePackageMissingTypeWithoutWildcardGetsTheDeclaringPackage() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/Local.java", """
                package com.corp.app;
                import java.util.List;
                class Local { void take(Missing m, List<String> xs) {} }
                """).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey)
                .contains("com.corp.app.Local#take(com.corp.app.Missing,java.util.List)");
    }

    @Test
    void ambiguousWildcardsFallBackToQuestionMarkAndWarn() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/Amb.java", """
                package com.corp.app;
                import org.a.*;
                import org.b.*;
                class Amb { void take(Thing t) {} }
                """).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey).contains("com.corp.app.Amb#take(?Thing)");
        assertThat(result.warnings()).extracting(IndexWarning::message)
                .anySatisfy(message -> assertThat(message).contains("?Thing").contains("com.corp.app.Amb#take"));
    }

    @Test
    void keyIsIdenticalAndExactWhenTheTypeIsOnTheClasspath() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "dto", Map.of("com/corp/dto/OrderDto.java", """
                package com.corp.dto;
                public class OrderDto {}
                """), Set.of());

        IndexResult result = TempRepo.at(dir.resolve("repo"))
                .java("app", "com/corp/app/Svc.java", SVC)
                .java("web", "com/corp/web/Caller.java", CALLER)
                .classpath("app", jar)
                .classpath("web", jar)
                .index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey).contains(HANDLE);
        assertThat(usage(result, "com.corp.web.Caller#run(com.corp.app.Svc)", UsageKind.CALL, HANDLE).confidence())
                .isEqualTo(Confidence.EXACT);
    }

    @Test
    void qualifiedSpellingOfMissingTypeKeepsItsOwnPackage() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/Q.java", """
                package com.corp.app;
                public class Q { public void handle(com.corp.dto.OrderDto d) {} }
                """).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey)
                .contains("com.corp.app.Q#handle(com.corp.dto.OrderDto)");
        assertThat(result.warnings()).noneMatch(w -> w.message().contains("OrderDto"));
    }

    @Test
    void nestedSpellingsResolveToTheBinaryNestedName() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/N.java", """
                package com.corp.app;
                import com.x.Outer;
                public class N {
                    public void take(Outer.Inner i) {}
                    public void full(com.x.Outer.Inner i) {}
                }
                """).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey).contains(
                "com.corp.app.N#take(com.x.Outer$Inner)",
                "com.corp.app.N#full(com.x.Outer$Inner)");
    }

    @Test
    void qualifiedAndNestedSpellingsMatchTheKeysBuiltWithTheJar() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "x", Map.of(
                "com/x/Outer.java", "package com.x; public class Outer { public static class Inner {} }",
                "com/corp/dto/OrderDto.java", "package com.corp.dto; public class OrderDto {}"), Set.of());
        String source = """
                package com.corp.app;
                import com.x.Outer;
                public class J {
                    public void take(Outer.Inner i) {}
                    public void handle(com.corp.dto.OrderDto d) {}
                }
                """;

        IndexResult without = TempRepo.at(dir.resolve("a")).java("app", "com/corp/app/J.java", source).index();
        IndexResult with = TempRepo.at(dir.resolve("b")).java("app", "com/corp/app/J.java", source)
                .classpath("app", jar).index();

        String take = "com.corp.app.J#take(com.x.Outer$Inner)";
        String handle = "com.corp.app.J#handle(com.corp.dto.OrderDto)";
        assertThat(without.declarations()).extracting(Declaration::symbolKey).contains(take, handle);
        assertThat(with.declarations()).extracting(Declaration::symbolKey).contains(take, handle);
    }
}

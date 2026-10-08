# Plan 1 — Java Indexer Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Given a local checkout of a Maven repository (its modules, source roots and optional per-module classpath), produce an in-memory index of every class/method/field symbol and every usage (call, instantiation, method reference, type reference, extends/implements/overrides, field read/write, annotation) with an `EXACT` / `RECOVERED` / `NAME_ONLY` confidence.

**Architecture:** A plain-Java library in the feature package `com.graphify.indexer` (no Spring, no DB — later plans wire it). Eclipse JDT `ASTParser` parses each module's files in batches with bindings resolved against that module's classpath plus every source root of the repo. Two AST visitors run per file: `DeclarationVisitor` (symbols, declarations, hierarchy, overrides) and `ReferenceVisitor` (all other usages). When JDT cannot bind, a `NameOnlyResolver` derives the target class from the file's imports.

**Tech Stack:** Java 25, Spring Boot 4.1.1 parent (build only), Eclipse JDT Core 3.47.0, JUnit 5 + AssertJ (from `spring-boot-starter-test`), `javax.tools` for building test jars.

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` (this plan implements §3.2 steps 6–7, §4.2, §4.3 and the "JDT indeksleyici" row of §11).

## Plan series

This is plan 1 of 6. Each plan ships working, tested software on its own:

1. **Java indexer core** (this plan): local directory → symbols + usages.
2. Persistence & settings foundation: Oracle, Flyway, `APP_SETTING`, secret encryption, writing an `IndexResult` to the DB per repo transactionally.
3. Search, impact analysis (BFS, dispatch, entry points, version warnings) and their REST API.
4. Bitbucket DC client, git workspace, Maven classpath resolution (fills `SYMBOL.artifact`), scheduler, index runs, end-to-end acceptance test.
5. Authentication (LDAP + local admin + roles) and the admin APIs.
6. Repo graph view (levels, communities, metrics, cycles).

## Global Constraints

- JDK 25 is required. Every command in this plan runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` set in the shell (JDK 25.0.4 is already installed there; it is keg-only, so `java -version` on the default PATH still shows 17).
- `pom.xml` keeps `<java.version>25</java.version>` and parent `spring-boot-starter-parent` `4.1.1`.
- Eclipse JDT dependency: `org.eclipse.jdt:org.eclipse.jdt.core:3.47.0`.
- No hardcoded tuning values in production code ("kodda sabit değer yok", spec §6.1): batch size and snippet length arrive through `IndexerOptions`; only tests choose literal values.
- `symbol_key` formats are exactly spec §4.2: class `com.corp.common.MoneyUtil`, inner `com.corp.common.MoneyUtil$Rounding`, method `com.corp.common.MoneyUtil#format(int)` (erased, fully qualified parameter types, joined with `,` and no spaces), constructor `...#<init>(java.lang.String)`, field `com.corp.common.MoneyUtil.rate`, binding-less method `com.corp.common.MoneyUtil#format/1`.
- Enum names are exactly spec §4.3: `SymbolKind` `CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, METHOD, CONSTRUCTOR, FIELD`; `UsageKind` `CALL, INSTANTIATION, METHOD_REF, TYPE_REF, EXTENDS, IMPLEMENTS, OVERRIDES, FIELD_READ, FIELD_WRITE, ANNOTATION`; `Confidence` `EXACT, RECOVERED, NAME_ONLY`; `SymbolOrigin` `SOURCE, BINARY`.
- `from` symbol of a usage (spec §4.3): the nearest enclosing *named* method/constructor; in field initializers, initializer blocks and class-level annotations/type references it is the class. Code inside lambdas and anonymous classes is attributed to the enclosing named method.
- Confidence rule (verified in a JDT spike): `EXACT` only when the binding is non-null, not recovered, and no compile error overlaps the node; a non-null binding with a recovered flag or an overlapping error is `RECOVERED`; no usable binding → `NAME_ONLY` via imports (never via JDT's guessed package).
- `ANNOTATION` usages store the annotation's source text (e.g. `@PostMapping("/orders")`) as `snippet` (plan 3 reads endpoint paths from it); all other usages store the stripped source line. Both truncated to `snippetMaxLength`.
- File paths in results are relative to the repo root and use `/`.
- Feature-based packaging: production code in `com.graphify.indexer` (package-private internals) and `com.graphify.indexer.model` (public records/enums).
- Commit messages end with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **A source file that does not compile** (syntax or type errors, common in a 300-repo estate) must not abort the repo; well-formed code in it and in other files is still indexed. Test: Task 8 `RobustnessTest.brokenFileDoesNotStopIndexing`.
2. **Source files not encoded in UTF-8** (e.g. ISO-8859-9 Turkish comments) must be indexed, with undecodable bytes replaced. Tests: Task 3 `SourceLinesTest.invalidUtf8BytesAreReplacedNotRejected`, Task 8 `RobustnessTest.nonUtf8FileIsIndexed`.
3. **Types that land in different parse batches** must resolve identically; results must not depend on `parseBatchSize`. Test: Task 8 `CorpRepoFixtureTest.resultsDoNotDependOnParseBatchSize`.
4. **The same FQN declared in two modules** (copied code) must yield one symbol with two declarations, not two symbols or a crash. Test: Task 5 `DeclarationVisitorTest.sameClassInTwoModulesIsOneSymbolWithTwoDeclarations`.
5. **Usages in field initializers, static blocks, lambdas and anonymous classes** must be attributed to the right `from` symbol, or impact analysis loses callers. Test: Task 6 `ReferenceVisitorCallsTest.usagesAreAttributedToNearestNamedMemberOrClass`.

---

## File Structure

Production (`src/main/java/com/graphify/indexer/`):

| File | Responsibility |
|---|---|
| `JdtParser.java` | Configure `ASTParser` (latest compliance, bindings + recovery) and parse files in batches |
| `model/SymbolKind.java`, `model/SymbolOrigin.java`, `model/Symbol.java` | Symbol model |
| `model/UsageKind.java`, `model/Confidence.java`, `model/Usage.java`, `model/Declaration.java`, `model/IndexWarning.java`, `model/IndexResult.java` | Usage/result model |
| `SymbolKeys.java` | Build spec §4.2 keys from JDT bindings |
| `SymbolRegistry.java` | Deduplicated symbol table (type → parent chain, SOURCE beats BINARY, binding beats name-only) |
| `SourceLines.java` | Read source text tolerant of bad encodings; produce snippets |
| `IndexCollector.java` | Accumulate declarations, usages, warnings |
| `ConfidenceClassifier.java` | `EXACT` vs `RECOVERED` from binding flags + compile-error ranges |
| `ImportResolver.java` | Simple type name → FQN using the file's imports |
| `NameOnlyResolver.java` | Target class of a binding-less call/type/annotation |
| `FileContext.java` | Per-file state shared by visitors; creates `Usage`/`Declaration`/`IndexWarning` |
| `ScopedVisitor.java` | Tracks the current `from` symbol while walking the AST |
| `Overrides.java` | Find methods a method overrides across the supertype graph |
| `DeclarationVisitor.java` | Declarations + `EXTENDS`/`IMPLEMENTS`/`OVERRIDES` |
| `ReferenceVisitor.java` | `CALL`/`INSTANTIATION`/`METHOD_REF`/`FIELD_*`/`TYPE_REF`/`ANNOTATION` |
| `IndexerOptions.java`, `ModuleSource.java`, `IndexRequest.java` | Public request types |
| `JavaRepositoryIndexer.java` | Public façade: `IndexResult index(IndexRequest)` |

Tests (`src/test/java/com/graphify/indexer/`): `ParsedSources` (parse in-memory sources), `TempRepo` (build a repo on disk + index it), `TestJars` (compile a jar), `IndexResults` (lookup helpers), one test class per production unit, plus `CorpRepoFixtureTest` and `RobustnessTest`. Fixture repo: `src/test/resources/fixtures/corp-repo/`.

---

### Task 1: JDK 25 toolchain, JDT dependency and batch parser

**Files:**
- Modify: `pom.xml`
- Modify: `README.md`
- Create: `src/main/java/com/graphify/indexer/JdtParser.java`
- Test: `src/test/java/com/graphify/indexer/JdtParserTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `final class JdtParser` with `interface UnitConsumer { void accept(Path file, CompilationUnit unit); }` and `void parse(List<Path> files, List<Path> classpath, List<Path> sourcepath, int batchSize, UnitConsumer consumer)`. Files and paths must be absolute; `consumer` receives the same path string it was given.

- [ ] **Step 1: Add the JDT dependency and a JDK 25 enforcer rule to `pom.xml`**

In `<properties>` add the version property:

```xml
	<properties>
		<java.version>25</java.version>
		<eclipse-jdt.version>3.47.0</eclipse-jdt.version>
	</properties>
```

Add to `<dependencies>` (before `spring-boot-starter-test`):

```xml
		<dependency>
			<groupId>org.eclipse.jdt</groupId>
			<artifactId>org.eclipse.jdt.core</artifactId>
			<version>${eclipse-jdt.version}</version>
		</dependency>
```

Add to `<build><plugins>` (after `spring-boot-maven-plugin`):

```xml
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-enforcer-plugin</artifactId>
				<executions>
					<execution>
						<id>require-jdk-25</id>
						<goals>
							<goal>enforce</goal>
						</goals>
						<configuration>
							<rules>
								<requireJavaVersion>
									<version>[25,)</version>
									<message>JDK 25 is required. On macOS/Homebrew: export JAVA_HOME=/opt/homebrew/opt/openjdk@25</message>
								</requireJavaVersion>
							</rules>
						</configuration>
					</execution>
				</executions>
			</plugin>
```

- [ ] **Step 2: Verify the enforcer rejects JDK 17 and the build passes on JDK 25**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -q validate`
Expected: BUILD FAILURE containing `JDK 25 is required`.

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && ./mvnw test`
Expected: `Tests run: 1, Failures: 0, Errors: 0` (the existing `GraphifyApplicationTests`), BUILD SUCCESS.

- [ ] **Step 3: Document the JDK in `README.md`**

Replace the `## Requirements` section with:

```markdown
## Requirements

- JDK 25 (macOS/Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; the build fails fast on older JDKs)
- Maven (wrapper included: `./mvnw`)
```

- [ ] **Step 4: Write the failing test**

`src/test/java/com/graphify/indexer/JdtParserTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdtParserTest {

    @TempDir
    Path dir;

    @Test
    void resolvesTypesFromOtherSourceRootsOnTheSourcepath() throws IOException {
        write("lib/src/main/java/com/acme/lib/Greeter.java", """
                package com.acme.lib;
                public class Greeter { public String greet(String name) { return name; } }
                """);
        Path app = write("app/src/main/java/com/acme/app/App.java", """
                package com.acme.app;
                import com.acme.lib.Greeter;
                public class App { String run() { return new Greeter().greet("x"); } }
                """);

        List<CompilationUnit> units = new ArrayList<>();
        new JdtParser().parse(List.of(app), List.of(),
                List.of(dir.resolve("lib/src/main/java"), dir.resolve("app/src/main/java")), 10,
                (file, unit) -> units.add(unit));

        assertThat(units).hasSize(1);
        List<IMethodBinding> calls = new ArrayList<>();
        units.getFirst().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                calls.add(node.resolveMethodBinding());
                return true;
            }
        });
        assertThat(calls).singleElement().satisfies(binding -> {
            assertThat(binding).isNotNull();
            assertThat(binding.isRecovered()).isFalse();
            assertThat(binding.getDeclaringClass().getQualifiedName()).isEqualTo("com.acme.lib.Greeter");
        });
    }

    @Test
    void deliversEveryFileWhenSplitIntoBatches() throws IOException {
        List<Path> files = List.of(
                write("src/p/A.java", "package p; class A {}"),
                write("src/p/B.java", "package p; class B {}"),
                write("src/p/C.java", "package p; class C {}"));

        List<Path> seen = new ArrayList<>();
        new JdtParser().parse(files, List.of(), List.of(dir.resolve("src")), 2, (file, unit) -> seen.add(file));

        assertThat(seen).containsExactlyInAnyOrderElementsOf(files);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./mvnw test -Dtest=JdtParserTest`
Expected: BUILD FAILURE, test compilation error `cannot find symbol ... class JdtParser`.

- [ ] **Step 6: Write the implementation**

`src/main/java/com/graphify/indexer/JdtParser.java`:

```java
package com.graphify.indexer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;

/** Parses Java files with Eclipse JDT, resolving bindings against a classpath and sourcepath. */
final class JdtParser {

    interface UnitConsumer {
        void accept(Path file, CompilationUnit unit);
    }

    void parse(List<Path> files, List<Path> classpath, List<Path> sourcepath, int batchSize, UnitConsumer consumer) {
        String[] classpathEntries = classpath.stream().map(Path::toString).toArray(String[]::new);
        String[] sourcepathEntries = sourcepath.stream().map(Path::toString).toArray(String[]::new);
        for (int from = 0; from < files.size(); from += batchSize) {
            List<Path> batch = files.subList(from, Math.min(files.size(), from + batchSize));
            String[] paths = batch.stream().map(Path::toString).toArray(String[]::new);
            ASTParser parser = newParser(classpathEntries, sourcepathEntries);
            parser.createASTs(paths, utf8(paths.length), new String[0], new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                    consumer.accept(Path.of(sourceFilePath), ast);
                }
            }, null);
        }
    }

    private static ASTParser newParser(String[] classpath, String[] sourcepath) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.DISABLED);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setEnvironment(classpath, sourcepath, utf8(sourcepath.length), true);
        return parser;
    }

    private static String[] utf8(int count) {
        String[] encodings = new String[count];
        Arrays.fill(encodings, "UTF-8");
        return encodings;
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw test -Dtest=JdtParserTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`, BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add pom.xml README.md src/main/java/com/graphify/indexer/JdtParser.java src/test/java/com/graphify/indexer/JdtParserTest.java
git commit -m "feat(indexer): add JDT batch parser and require JDK 25" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Symbol model, symbol keys and symbol registry

**Files:**
- Create: `src/main/java/com/graphify/indexer/model/SymbolKind.java`
- Create: `src/main/java/com/graphify/indexer/model/SymbolOrigin.java`
- Create: `src/main/java/com/graphify/indexer/model/Symbol.java`
- Create: `src/main/java/com/graphify/indexer/SymbolKeys.java`
- Create: `src/main/java/com/graphify/indexer/SymbolRegistry.java`
- Test: `src/test/java/com/graphify/indexer/ParsedSources.java`
- Test: `src/test/java/com/graphify/indexer/SymbolKeysTest.java`
- Test: `src/test/java/com/graphify/indexer/SymbolRegistryTest.java`

**Interfaces:**
- Consumes: `JdtParser.parse(...)` (Task 1), used by the `ParsedSources` test helper.
- Produces:
  - `public record Symbol(String key, SymbolKind kind, String classFqn, String memberName, String displaySignature, String parentKey, SymbolOrigin origin)`.
  - `final class SymbolKeys`: `static String typeKey(ITypeBinding)`, `static String methodKey(IMethodBinding)`, `static String fieldKey(IVariableBinding)`, `static String nameOnlyMethodKey(String classFqn, String methodName, int argumentCount)`, `static String nameOnlyConstructorKey(String classFqn, int argumentCount)`, `static String memberName(IMethodBinding)`, `static String parameterTypeName(ITypeBinding)`.
  - `final class SymbolRegistry`: `String type(ITypeBinding)` (returns `null` for primitives/null type), `String method(IMethodBinding)`, `String field(IVariableBinding)` (returns `null` when the field has no declaring class, e.g. `array.length`), `String nameOnlyType(String classFqn)`, `String nameOnlyMethod(String classFqn, String methodName, int argumentCount)`, `String nameOnlyConstructor(String classFqn, int argumentCount)`, `List<Symbol> all()`. Each registering method returns the symbol key.
  - Test helper `ParsedSources`: `static Map<String, CompilationUnit> parse(Path dir, Map<String, String> sources, List<Path> classpath)` (keys are the source-relative paths given) and `static <T extends ASTNode> List<T> find(CompilationUnit unit, Class<T> type)` (source order).

- [ ] **Step 1: Write the test helper**

`src/test/java/com/graphify/indexer/ParsedSources.java`:

```java
package com.graphify.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Writes sources under {@code dir/src}, parses them with bindings and returns units keyed by relative path. */
final class ParsedSources {

    private ParsedSources() {
    }

    static Map<String, CompilationUnit> parse(Path dir, Map<String, String> sources, List<Path> classpath)
            throws IOException {
        Path root = dir.resolve("src").toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        new JdtParser().parse(files, classpath, List.of(root), 100,
                (file, unit) -> units.put(root.relativize(file).toString().replace('\\', '/'), unit));
        return units;
    }

    static <T extends ASTNode> List<T> find(CompilationUnit unit, Class<T> type) {
        List<T> found = new ArrayList<>();
        unit.accept(new ASTVisitor(false) {
            @Override
            public void preVisit(ASTNode node) {
                if (type.isInstance(node)) {
                    found.add(type.cast(node));
                }
            }
        });
        return found;
    }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/graphify/indexer/SymbolKeysTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolKeysTest {

    @TempDir
    Path dir;

    private CompilationUnit money() throws Exception {
        return ParsedSources.parse(dir, Map.of("com/acme/Money.java", """
                package com.acme;
                import java.util.List;
                import java.util.Map;
                public class Money {
                    public Money(String currency) {}
                    public String format(int amount) { return ""; }
                    public String format(String amount) { return ""; }
                    public <T extends Number> void sum(List<T> values, Map.Entry<String, T> pair, int[][] grid, Object... rest) {}
                    public static class Rounding { public int scale; }
                }
                """), List.of()).get("com/acme/Money.java");
    }

    @Test
    void methodKeysDistinguishOverloadsAndUseErasedQualifiedParameterTypes() throws Exception {
        List<String> keys = ParsedSources.find(money(), MethodDeclaration.class).stream()
                .map(m -> SymbolKeys.methodKey(m.resolveBinding()))
                .toList();

        assertThat(keys).containsExactly(
                "com.acme.Money#<init>(java.lang.String)",
                "com.acme.Money#format(int)",
                "com.acme.Money#format(java.lang.String)",
                "com.acme.Money#sum(java.util.List,java.util.Map$Entry,int[][],java.lang.Object[])");
    }

    @Test
    void nestedTypesUseBinaryNamesAndFieldsHangOffTheirType() throws Exception {
        CompilationUnit unit = money();
        TypeDeclaration rounding = ParsedSources.find(unit, TypeDeclaration.class).get(1);
        VariableDeclarationFragment scale = ParsedSources.find(unit, VariableDeclarationFragment.class).getFirst();

        assertThat(SymbolKeys.typeKey(rounding.resolveBinding())).isEqualTo("com.acme.Money$Rounding");
        assertThat(SymbolKeys.fieldKey(scale.resolveBinding())).isEqualTo("com.acme.Money$Rounding.scale");
    }

    @Test
    void nameOnlyKeysCarryNameAndArgumentCount() {
        assertThat(SymbolKeys.nameOnlyMethodKey("org.x.Rest", "exchange", 4)).isEqualTo("org.x.Rest#exchange/4");
        assertThat(SymbolKeys.nameOnlyConstructorKey("org.x.Rest", 0)).isEqualTo("org.x.Rest#<init>/0");
    }
}
```

`src/test/java/com/graphify/indexer/SymbolRegistryTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolRegistryTest {

    @TempDir
    Path dir;

    @Test
    void registersMembersWithTheirOwningTypesAsParents() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("com/acme/Outer.java", """
                package com.acme;
                public class Outer {
                    public static class Inner { public void run() {} }
                }
                """), List.of()).get("com/acme/Outer.java");
        MethodDeclaration run = ParsedSources.find(unit, MethodDeclaration.class).getFirst();
        SymbolRegistry registry = new SymbolRegistry();

        assertThat(registry.method(run.resolveBinding())).isEqualTo("com.acme.Outer$Inner#run()");
        assertThat(registry.all())
                .extracting(Symbol::key, Symbol::kind, Symbol::parentKey, Symbol::origin, Symbol::displaySignature)
                .containsExactlyInAnyOrder(
                        tuple("com.acme.Outer", SymbolKind.CLASS, null, SymbolOrigin.SOURCE, "Outer"),
                        tuple("com.acme.Outer$Inner", SymbolKind.CLASS, "com.acme.Outer", SymbolOrigin.SOURCE, "Inner"),
                        tuple("com.acme.Outer$Inner#run()", SymbolKind.METHOD, "com.acme.Outer$Inner",
                                SymbolOrigin.SOURCE, "Inner.run()"));
    }

    @Test
    void jdkMembersAreBinary() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", """
                package p;
                class A { int f() { return "x".length(); } }
                """), List.of()).get("p/A.java");
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();
        SymbolRegistry registry = new SymbolRegistry();

        String key = registry.method(call.resolveMethodBinding());

        assertThat(key).isEqualTo("java.lang.String#length()");
        assertThat(registry.all()).extracting(Symbol::key, Symbol::origin).containsExactlyInAnyOrder(
                tuple("java.lang.String", SymbolOrigin.BINARY),
                tuple("java.lang.String#length()", SymbolOrigin.BINARY));
    }

    @Test
    void classifiesTypeKinds() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/Kinds.java", """
                package p;
                class Kinds {
                    interface I {}
                    enum E { X }
                    record R(int a) {}
                    @interface A {}
                }
                """), List.of()).get("p/Kinds.java");
        SymbolRegistry registry = new SymbolRegistry();
        ParsedSources.find(unit, AbstractTypeDeclaration.class).forEach(t -> registry.type(t.resolveBinding()));

        assertThat(registry.all()).extracting(Symbol::key, Symbol::kind).containsExactlyInAnyOrder(
                tuple("p.Kinds", SymbolKind.CLASS),
                tuple("p.Kinds$I", SymbolKind.INTERFACE),
                tuple("p.Kinds$E", SymbolKind.ENUM),
                tuple("p.Kinds$R", SymbolKind.RECORD),
                tuple("p.Kinds$A", SymbolKind.ANNOTATION_TYPE));
    }

    @Test
    void bindingBasedSymbolReplacesNameOnlySymbol() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("com/acme/Api.java", """
                package com.acme;
                public interface Api {}
                """), List.of()).get("com/acme/Api.java");
        SymbolRegistry registry = new SymbolRegistry();

        registry.nameOnlyType("com.acme.Api");
        registry.type(ParsedSources.find(unit, AbstractTypeDeclaration.class).getFirst().resolveBinding());

        assertThat(registry.all()).extracting(Symbol::key, Symbol::kind, Symbol::origin)
                .containsExactly(tuple("com.acme.Api", SymbolKind.INTERFACE, SymbolOrigin.SOURCE));
    }

    @Test
    void nameOnlyMethodRegistersItsClassAsParent() {
        SymbolRegistry registry = new SymbolRegistry();

        assertThat(registry.nameOnlyMethod("org.x.Rest", "exchange", 2)).isEqualTo("org.x.Rest#exchange/2");
        assertThat(registry.nameOnlyConstructor("org.x.Rest", 0)).isEqualTo("org.x.Rest#<init>/0");
        assertThat(registry.all())
                .extracting(Symbol::key, Symbol::kind, Symbol::memberName, Symbol::parentKey, Symbol::displaySignature)
                .containsExactlyInAnyOrder(
                        tuple("org.x.Rest", SymbolKind.CLASS, null, null, "Rest"),
                        tuple("org.x.Rest#exchange/2", SymbolKind.METHOD, "exchange", "org.x.Rest", "Rest.exchange(2 args)"),
                        tuple("org.x.Rest#<init>/0", SymbolKind.CONSTRUCTOR, "<init>", "org.x.Rest", "Rest.Rest(0 args)"));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='SymbolKeysTest,SymbolRegistryTest'`
Expected: BUILD FAILURE, test compilation errors `cannot find symbol` for `SymbolKeys`, `SymbolRegistry`, `Symbol`.

- [ ] **Step 4: Write the model**

`src/main/java/com/graphify/indexer/model/SymbolKind.java`:

```java
package com.graphify.indexer.model;

public enum SymbolKind {
    CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, METHOD, CONSTRUCTOR, FIELD
}
```

`src/main/java/com/graphify/indexer/model/SymbolOrigin.java`:

```java
package com.graphify.indexer.model;

/** SOURCE: declared in an indexed source file. BINARY: known only from a jar, the JDK, or by name. */
public enum SymbolOrigin {
    SOURCE, BINARY
}
```

`src/main/java/com/graphify/indexer/model/Symbol.java`:

```java
package com.graphify.indexer.model;

/**
 * A class, method, constructor or field, identified by its spec §4.2 key.
 *
 * @param classFqn    key of the owning type (the type's own key for type symbols)
 * @param memberName  method/field name, {@code <init>} for constructors, {@code null} for types
 * @param parentKey   enclosing type for members and nested types, {@code null} for top-level types
 */
public record Symbol(
        String key,
        SymbolKind kind,
        String classFqn,
        String memberName,
        String displaySignature,
        String parentKey,
        SymbolOrigin origin) {
}
```

- [ ] **Step 5: Write `SymbolKeys`**

`src/main/java/com/graphify/indexer/SymbolKeys.java`:

```java
package com.graphify.indexer;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/** Builds the spec §4.2 symbol keys that link usages to declarations across repositories. */
final class SymbolKeys {

    private SymbolKeys() {
    }

    static String typeKey(ITypeBinding type) {
        ITypeBinding erased = type.getErasure();
        if (erased.isArray()) {
            return typeKey(erased.getElementType());
        }
        String binaryName = erased.getBinaryName();
        return binaryName != null ? binaryName : erased.getQualifiedName();
    }

    static String methodKey(IMethodBinding method) {
        IMethodBinding declaration = method.getMethodDeclaration();
        String parameters = Arrays.stream(declaration.getParameterTypes())
                .map(SymbolKeys::parameterTypeName)
                .collect(Collectors.joining(","));
        return typeKey(declaration.getDeclaringClass()) + "#" + memberName(declaration) + "(" + parameters + ")";
    }

    static String fieldKey(IVariableBinding field) {
        IVariableBinding declaration = field.getVariableDeclaration();
        return typeKey(declaration.getDeclaringClass()) + "." + declaration.getName();
    }

    static String nameOnlyMethodKey(String classFqn, String methodName, int argumentCount) {
        return classFqn + "#" + methodName + "/" + argumentCount;
    }

    static String nameOnlyConstructorKey(String classFqn, int argumentCount) {
        return nameOnlyMethodKey(classFqn, "<init>", argumentCount);
    }

    static String memberName(IMethodBinding method) {
        return method.isConstructor() ? "<init>" : method.getName();
    }

    static String parameterTypeName(ITypeBinding type) {
        if (type.isArray()) {
            return parameterTypeName(type.getElementType()) + "[]".repeat(type.getDimensions());
        }
        if (type.isPrimitive()) {
            return type.getName();
        }
        return typeKey(type);
    }
}
```

- [ ] **Step 6: Write `SymbolRegistry`**

`src/main/java/com/graphify/indexer/SymbolRegistry.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/**
 * Deduplicated symbol table for one indexing run. A SOURCE symbol replaces a BINARY one with the same key,
 * and a binding-based symbol replaces a name-only guess.
 */
final class SymbolRegistry {

    private final Map<String, Symbol> symbols = new LinkedHashMap<>();
    private final Set<String> nameOnlyKeys = new HashSet<>();

    String type(ITypeBinding binding) {
        ITypeBinding type = binding.getErasure();
        if (type.isArray()) {
            type = type.getElementType().getErasure();
        }
        if (type.isPrimitive() || type.isNullType()) {
            return null;
        }
        String key = SymbolKeys.typeKey(type);
        if (key.isBlank()) {
            return null;
        }
        String parent = type.getDeclaringClass() == null ? null : type(type.getDeclaringClass());
        putResolved(new Symbol(key, kindOf(type), key, null, type.getName(), parent, originOf(type)));
        return key;
    }

    String method(IMethodBinding binding) {
        IMethodBinding method = binding.getMethodDeclaration();
        String owner = type(method.getDeclaringClass());
        if (owner == null) {
            return null;
        }
        String key = SymbolKeys.methodKey(method);
        String ownerName = method.getDeclaringClass().getErasure().getName();
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(t -> t.getErasure().getName())
                .collect(Collectors.joining(", "));
        String display = ownerName + "." + (method.isConstructor() ? ownerName : method.getName()) + "(" + parameters + ")";
        SymbolKind kind = method.isConstructor() ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
        putResolved(new Symbol(key, kind, owner, SymbolKeys.memberName(method), display, owner,
                originOf(method.getDeclaringClass())));
        return key;
    }

    String field(IVariableBinding binding) {
        IVariableBinding field = binding.getVariableDeclaration();
        if (field.getDeclaringClass() == null) {
            return null;
        }
        String owner = type(field.getDeclaringClass());
        if (owner == null) {
            return null;
        }
        String key = SymbolKeys.fieldKey(field);
        String display = field.getDeclaringClass().getErasure().getName() + "." + field.getName();
        putResolved(new Symbol(key, SymbolKind.FIELD, owner, field.getName(), display, owner,
                originOf(field.getDeclaringClass())));
        return key;
    }

    String nameOnlyType(String classFqn) {
        putNameOnly(new Symbol(classFqn, SymbolKind.CLASS, classFqn, null, simpleName(classFqn), null,
                SymbolOrigin.BINARY));
        return classFqn;
    }

    String nameOnlyMethod(String classFqn, String methodName, int argumentCount) {
        String owner = nameOnlyType(classFqn);
        String key = SymbolKeys.nameOnlyMethodKey(classFqn, methodName, argumentCount);
        String display = simpleName(classFqn) + "." + methodName + "(" + argumentCount + " args)";
        putNameOnly(new Symbol(key, SymbolKind.METHOD, owner, methodName, display, owner, SymbolOrigin.BINARY));
        return key;
    }

    String nameOnlyConstructor(String classFqn, int argumentCount) {
        String owner = nameOnlyType(classFqn);
        String key = SymbolKeys.nameOnlyConstructorKey(classFqn, argumentCount);
        String display = simpleName(classFqn) + "." + simpleName(classFqn) + "(" + argumentCount + " args)";
        putNameOnly(new Symbol(key, SymbolKind.CONSTRUCTOR, owner, "<init>", display, owner, SymbolOrigin.BINARY));
        return key;
    }

    List<Symbol> all() {
        return List.copyOf(symbols.values());
    }

    private void putResolved(Symbol symbol) {
        Symbol existing = symbols.get(symbol.key());
        if (existing == null
                || nameOnlyKeys.remove(symbol.key())
                || (existing.origin() == SymbolOrigin.BINARY && symbol.origin() == SymbolOrigin.SOURCE)) {
            symbols.put(symbol.key(), symbol);
        }
    }

    private void putNameOnly(Symbol symbol) {
        if (symbols.putIfAbsent(symbol.key(), symbol) == null) {
            nameOnlyKeys.add(symbol.key());
        }
    }

    private static SymbolKind kindOf(ITypeBinding type) {
        if (type.isAnnotation()) {
            return SymbolKind.ANNOTATION_TYPE;
        }
        if (type.isInterface()) {
            return SymbolKind.INTERFACE;
        }
        if (type.isEnum()) {
            return SymbolKind.ENUM;
        }
        if (type.isRecord()) {
            return SymbolKind.RECORD;
        }
        return SymbolKind.CLASS;
    }

    private static SymbolOrigin originOf(ITypeBinding type) {
        return type.getErasure().isFromSource() ? SymbolOrigin.SOURCE : SymbolOrigin.BINARY;
    }

    private static String simpleName(String classFqn) {
        String afterPackage = classFqn.substring(classFqn.lastIndexOf('.') + 1);
        return afterPackage.substring(afterPackage.lastIndexOf('$') + 1);
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SymbolKeysTest,SymbolRegistryTest'`
Expected: `Tests run: 8, Failures: 0, Errors: 0`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/graphify/indexer src/test/java/com/graphify/indexer
git commit -m "feat(indexer): add symbol model, spec keys and symbol registry" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Usage model, source lines, collector and confidence classifier

**Files:**
- Create: `src/main/java/com/graphify/indexer/model/UsageKind.java`
- Create: `src/main/java/com/graphify/indexer/model/Confidence.java`
- Create: `src/main/java/com/graphify/indexer/model/Usage.java`
- Create: `src/main/java/com/graphify/indexer/model/Declaration.java`
- Create: `src/main/java/com/graphify/indexer/model/IndexWarning.java`
- Create: `src/main/java/com/graphify/indexer/model/IndexResult.java`
- Create: `src/main/java/com/graphify/indexer/SourceLines.java`
- Create: `src/main/java/com/graphify/indexer/IndexCollector.java`
- Create: `src/main/java/com/graphify/indexer/ConfidenceClassifier.java`
- Test: `src/test/java/com/graphify/indexer/SourceLinesTest.java`
- Test: `src/test/java/com/graphify/indexer/ConfidenceClassifierTest.java`

**Interfaces:**
- Consumes: `ParsedSources` (Task 2).
- Produces:
  - `public record Usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, String modulePath, String filePath, int line, int column, String snippet)`.
  - `public record Declaration(String symbolKey, String modulePath, String filePath, int line)`.
  - `public record IndexWarning(String modulePath, String filePath, int line, String message)` (`line` 0 = whole file).
  - `public record IndexResult(List<Symbol> symbols, List<Declaration> declarations, List<Usage> usages, List<IndexWarning> warnings)`.
  - `final class SourceLines`: `static SourceLines read(Path) throws IOException`, `static SourceLines of(String)`, `String snippet(int line, int maxLength)`, `static String truncate(String text, int maxLength)`.
  - `final class IndexCollector`: `void declaration(Declaration)`, `void usage(Usage)`, `void warning(IndexWarning)`, `List<Declaration> declarations()`, `List<Usage> usages()`, `List<IndexWarning> warnings()`.
  - `final class ConfidenceClassifier`: `ConfidenceClassifier(CompilationUnit)`, `Confidence of(IBinding binding, ASTNode node)`, `boolean hasErrorWithin(ASTNode node)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/indexer/SourceLinesTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLinesTest {

    @Test
    void returnsStrippedLineTruncatedToMaxLength() {
        SourceLines lines = SourceLines.of("a\r\n    money.format(\"10\");   \nlast");

        assertThat(lines.snippet(2, 100)).isEqualTo("money.format(\"10\");");
        assertThat(lines.snippet(2, 5)).isEqualTo("money");
        assertThat(lines.snippet(3, 100)).isEqualTo("last");
    }

    @Test
    void outOfRangeLineGivesEmptySnippet() {
        SourceLines lines = SourceLines.of("only");

        assertThat(lines.snippet(0, 10)).isEmpty();
        assertThat(lines.snippet(2, 10)).isEmpty();
    }

    @Test
    void invalidUtf8BytesAreReplacedNotRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("A.java");
        Files.write(file, new byte[] {'/', '/', ' ', (byte) 0xFE, '\n', 'x'});

        assertThat(SourceLines.read(file).snippet(1, 10)).isEqualTo("// �");
    }

    @Test
    void truncateLeavesShortTextAlone() {
        assertThat(SourceLines.truncate("@Get(\"/a\")", 100)).isEqualTo("@Get(\"/a\")");
        assertThat(SourceLines.truncate("@Get(\"/a\")", 4)).isEqualTo("@Get");
    }
}
```

`src/test/java/com/graphify/indexer/ConfidenceClassifierTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfidenceClassifierTest {

    @TempDir
    Path dir;

    @Test
    void exactOnlyWhenNoCompileErrorOverlapsTheNode() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", """
                package p;
                class A {
                    int ok() { return "x".length(); }
                    void broken() { int n = "not a number"; }
                }
                """), List.of()).get("p/A.java");
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();
        List<MethodDeclaration> methods = ParsedSources.find(unit, MethodDeclaration.class);
        ConfidenceClassifier classifier = new ConfidenceClassifier(unit);

        assertThat(classifier.hasErrorWithin(methods.get(0))).isFalse();
        assertThat(classifier.hasErrorWithin(methods.get(1))).isTrue();
        assertThat(classifier.of(call.resolveMethodBinding(), call)).isEqualTo(Confidence.EXACT);
        assertThat(classifier.of(call.resolveMethodBinding(), methods.get(1))).isEqualTo(Confidence.RECOVERED);
    }

    @Test
    void recoveredWhenTheBindingIsRecovered() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/B.java", """
                package p;
                class B { Missing field; }
                """), List.of()).get("p/B.java");
        SimpleType missing = ParsedSources.find(unit, SimpleType.class).getFirst();

        assertThat(missing.resolveBinding().isRecovered()).isTrue();
        assertThat(new ConfidenceClassifier(unit).of(missing.resolveBinding(), missing))
                .isEqualTo(Confidence.RECOVERED);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='SourceLinesTest,ConfidenceClassifierTest'`
Expected: BUILD FAILURE, `cannot find symbol` for `SourceLines`, `ConfidenceClassifier`, `Confidence`.

- [ ] **Step 3: Write the model**

`src/main/java/com/graphify/indexer/model/UsageKind.java`:

```java
package com.graphify.indexer.model;

public enum UsageKind {
    CALL, INSTANTIATION, METHOD_REF, TYPE_REF, EXTENDS, IMPLEMENTS, OVERRIDES, FIELD_READ, FIELD_WRITE, ANNOTATION
}
```

`src/main/java/com/graphify/indexer/model/Confidence.java`:

```java
package com.graphify.indexer.model;

/**
 * EXACT: binding resolved with no error at the site. RECOVERED: binding exists but is recovered or the site
 * has a compile error (incomplete classpath). NAME_ONLY: no binding; target derived from imports and names.
 */
public enum Confidence {
    EXACT, RECOVERED, NAME_ONLY
}
```

`src/main/java/com/graphify/indexer/model/Usage.java`:

```java
package com.graphify.indexer.model;

/** One usage site: {@code fromKey} (enclosing method or class) uses {@code toKey}. Line and column are 1-based. */
public record Usage(
        String fromKey,
        String toKey,
        UsageKind kind,
        Confidence confidence,
        String modulePath,
        String filePath,
        int line,
        int column,
        String snippet) {
}
```

`src/main/java/com/graphify/indexer/model/Declaration.java`:

```java
package com.graphify.indexer.model;

/** Where a SOURCE symbol is declared. {@code line} is the line of the declared name. */
public record Declaration(String symbolKey, String modulePath, String filePath, int line) {
}
```

`src/main/java/com/graphify/indexer/model/IndexWarning.java`:

```java
package com.graphify.indexer.model;

/** Something the indexer skipped. {@code line} is 0 when the whole file is affected. */
public record IndexWarning(String modulePath, String filePath, int line, String message) {
}
```

`src/main/java/com/graphify/indexer/model/IndexResult.java`:

```java
package com.graphify.indexer.model;

import java.util.List;

public record IndexResult(
        List<Symbol> symbols,
        List<Declaration> declarations,
        List<Usage> usages,
        List<IndexWarning> warnings) {

    public IndexResult {
        symbols = List.copyOf(symbols);
        declarations = List.copyOf(declarations);
        usages = List.copyOf(usages);
        warnings = List.copyOf(warnings);
    }
}
```

- [ ] **Step 4: Write `SourceLines`, `IndexCollector` and `ConfidenceClassifier`**

`src/main/java/com/graphify/indexer/SourceLines.java`:

```java
package com.graphify.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Source text split into lines. Undecodable bytes become U+FFFD instead of failing the file. */
final class SourceLines {

    private final List<String> lines;

    private SourceLines(String content) {
        this.lines = content.lines().toList();
    }

    static SourceLines read(Path file) throws IOException {
        return of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    static SourceLines of(String content) {
        return new SourceLines(content);
    }

    String snippet(int line, int maxLength) {
        if (line < 1 || line > lines.size()) {
            return "";
        }
        return truncate(lines.get(line - 1).strip(), maxLength);
    }

    static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
```

`src/main/java/com/graphify/indexer/IndexCollector.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexWarning;
import com.graphify.indexer.model.Usage;
import java.util.ArrayList;
import java.util.List;

/** Accumulates the output of one indexing run. */
final class IndexCollector {

    private final List<Declaration> declarations = new ArrayList<>();
    private final List<Usage> usages = new ArrayList<>();
    private final List<IndexWarning> warnings = new ArrayList<>();

    void declaration(Declaration declaration) {
        declarations.add(declaration);
    }

    void usage(Usage usage) {
        usages.add(usage);
    }

    void warning(IndexWarning warning) {
        warnings.add(warning);
    }

    List<Declaration> declarations() {
        return List.copyOf(declarations);
    }

    List<Usage> usages() {
        return List.copyOf(usages);
    }

    List<IndexWarning> warnings() {
        return List.copyOf(warnings);
    }
}
```

`src/main/java/com/graphify/indexer/ConfidenceClassifier.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;

/**
 * Decides EXACT vs RECOVERED for a resolved binding. With an incomplete classpath JDT can bind a call to the
 * wrong overload without flagging the binding as recovered, but it always reports an error at that call, so any
 * error overlapping the node downgrades the usage.
 */
final class ConfidenceClassifier {

    private final List<int[]> errorRanges = new ArrayList<>();

    ConfidenceClassifier(CompilationUnit unit) {
        for (IProblem problem : unit.getProblems()) {
            if (problem.isError()) {
                errorRanges.add(new int[] {problem.getSourceStart(), problem.getSourceEnd()});
            }
        }
    }

    Confidence of(IBinding binding, ASTNode node) {
        return binding.isRecovered() || hasErrorWithin(node) ? Confidence.RECOVERED : Confidence.EXACT;
    }

    boolean hasErrorWithin(ASTNode node) {
        int start = node.getStartPosition();
        int end = start + node.getLength() - 1;
        for (int[] range : errorRanges) {
            if (range[0] <= end && range[1] >= start) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SourceLinesTest,ConfidenceClassifierTest'`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/indexer src/test/java/com/graphify/indexer
git commit -m "feat(indexer): add usage model, source snippets and confidence classifier" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Import-based name resolution and per-file context

**Files:**
- Create: `src/main/java/com/graphify/indexer/ImportResolver.java`
- Create: `src/main/java/com/graphify/indexer/NameOnlyResolver.java`
- Create: `src/main/java/com/graphify/indexer/FileContext.java`
- Test: `src/test/java/com/graphify/indexer/ImportResolverTest.java`
- Test: `src/test/java/com/graphify/indexer/NameOnlyResolverTest.java`

**Interfaces:**
- Consumes: `SymbolKeys.typeKey` (Task 2); `SymbolRegistry` (Task 2); `SourceLines`, `IndexCollector`, `ConfidenceClassifier`, model records (Task 3); `ParsedSources` (Task 2).
- Produces:
  - `final class ImportResolver`: `ImportResolver(CompilationUnit)`, `Optional<String> resolve(String typeName)` — returns a binary-name FQN (`Outer$Inner` for `Outer.Inner`), or empty when ambiguous.
  - `final class NameOnlyResolver`: `NameOnlyResolver(ImportResolver)`, `Optional<String> receiverClass(Expression receiver)`, `Optional<String> typeClass(Type type)`, `Optional<String> annotationClass(Name typeName)`.
  - `final class FileContext` with package-private final fields `modulePath`, `filePath`, `unit`, `symbols` (`SymbolRegistry`), `confidence` (`ConfidenceClassifier`), `nameOnly` (`NameOnlyResolver`), constructor `FileContext(String modulePath, String filePath, CompilationUnit unit, SourceLines lines, SymbolRegistry symbols, ConfidenceClassifier confidence, NameOnlyResolver nameOnly, IndexCollector out, int snippetMaxLength)`, and methods `void usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, ASTNode at)`, `void usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, ASTNode at, String snippet)` (both ignore a `null` key), `void declaration(String symbolKey, ASTNode name)` (ignores `null`), `void warning(ASTNode at, String message)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/indexer/ImportResolverTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportResolverTest {

    @TempDir
    Path dir;

    private ImportResolver resolverFor(String source) throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("p/A.java", source), List.of()).get("p/A.java");
        return new ImportResolver(unit);
    }

    @Test
    void resolvesSingleTypeImportsIncludingNestedNames() throws Exception {
        ImportResolver resolver = resolverFor("""
                package p;
                import org.springframework.web.client.RestTemplate;
                import java.util.Map;
                class A {}
                """);

        assertThat(resolver.resolve("RestTemplate")).contains("org.springframework.web.client.RestTemplate");
        assertThat(resolver.resolve("Map.Entry")).contains("java.util.Map$Entry");
    }

    @Test
    void guessesFromTheOnlyWildcardImport() throws Exception {
        assertThat(resolverFor("package p; import org.vendor.*; class A {}").resolve("Client"))
                .contains("org.vendor.Client");
    }

    @Test
    void refusesToGuessBetweenSeveralWildcardImports() throws Exception {
        assertThat(resolverFor("package p; import org.a.*; import org.b.*; class A {}").resolve("Client")).isEmpty();
    }

    @Test
    void keepsNamesThatAreAlreadyQualified() throws Exception {
        assertThat(resolverFor("package p; class A {}").resolve("org.vendor.Client")).contains("org.vendor.Client");
    }

    @Test
    void ignoresStaticImports() throws Exception {
        ImportResolver resolver = resolverFor("package p; import static org.x.Util.helper; class A {}");

        assertThat(resolver.resolve("Util")).isEmpty();
        assertThat(resolver.resolve("helper")).isEmpty();
    }
}
```

`src/test/java/com/graphify/indexer/NameOnlyResolverTest.java`:

```java
package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameOnlyResolverTest {

    @TempDir
    Path dir;

    private CompilationUnit parse(String source) throws Exception {
        return ParsedSources.parse(dir, Map.of("com/corp/order/Api.java", source), List.of())
                .get("com/corp/order/Api.java");
    }

    @Test
    void receiverOfMissingTypeResolvesThroughImportsNotTheCurrentPackage() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.springframework.web.client.RestTemplate;
                class Api {
                    RestTemplate rest;
                    void call() { rest.exchange("u"); }
                }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(call.resolveMethodBinding()).isNull();
        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("org.springframework.web.client.RestTemplate");
    }

    @Test
    void staticCallOnMissingTypeResolvesThroughImports() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.vendor.Helper;
                class Api { void call() { Helper.run(); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("org.vendor.Helper");
    }

    @Test
    void receiverOfKnownTypeUsesItsBinding() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                class Api { int call() { return "x".length(); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("java.lang.String");
    }

    @Test
    void missingParameterizedTypeResolvesThroughImports() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import org.vendor.Client;
                class Api { Client<String> client; }
                """);
        FieldDeclaration field = ParsedSources.find(unit, FieldDeclaration.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).typeClass(field.getType()))
                .contains("org.vendor.Client");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='ImportResolverTest,NameOnlyResolverTest'`
Expected: BUILD FAILURE, `cannot find symbol` for `ImportResolver`, `NameOnlyResolver`.

- [ ] **Step 3: Write `ImportResolver`**

`src/main/java/com/graphify/indexer/ImportResolver.java`:

```java
package com.graphify.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

/**
 * Turns a simple (or dotted nested) type name into a fully qualified binary name using the file's imports.
 * Used only when JDT has no binding; JDT's own guess for a missing type puts it in the current package, which
 * is wrong whenever the type is imported.
 */
final class ImportResolver {

    private final Map<String, String> singleTypeImports = new HashMap<>();
    private final List<String> onDemandPackages = new ArrayList<>();

    ImportResolver(CompilationUnit unit) {
        for (Object item : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) item;
            if (declaration.isStatic()) {
                continue;
            }
            String name = declaration.getName().getFullyQualifiedName();
            if (declaration.isOnDemand()) {
                onDemandPackages.add(name);
            } else {
                singleTypeImports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
    }

    Optional<String> resolve(String typeName) {
        int dot = typeName.indexOf('.');
        if (dot > 0 && Character.isLowerCase(typeName.charAt(0))) {
            return Optional.of(typeName);
        }
        String first = dot > 0 ? typeName.substring(0, dot) : typeName;
        String nested = typeName.substring(first.length()).replace('.', '$');
        String imported = singleTypeImports.get(first);
        if (imported != null) {
            return Optional.of(imported + nested);
        }
        if (onDemandPackages.size() == 1) {
            return Optional.of(onDemandPackages.getFirst() + "." + first + nested);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Write `NameOnlyResolver`**

`src/main/java/com/graphify/indexer/NameOnlyResolver.java`:

```java
package com.graphify.indexer;

import java.util.Optional;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;

/** Finds the class a binding-less call, type or annotation refers to. */
final class NameOnlyResolver {

    private final ImportResolver imports;

    NameOnlyResolver(ImportResolver imports) {
        this.imports = imports;
    }

    Optional<String> receiverClass(Expression receiver) {
        if (receiver == null) {
            return Optional.empty();
        }
        ITypeBinding type = receiver.resolveTypeBinding();
        if (type != null && !type.isRecovered()) {
            return Optional.of(SymbolKeys.typeKey(type));
        }
        if (type != null) {
            return imports.resolve(type.getErasure().getName());
        }
        if (receiver instanceof Name name) {
            return imports.resolve(name.getFullyQualifiedName());
        }
        return Optional.empty();
    }

    Optional<String> typeClass(Type type) {
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && !binding.isRecovered()) {
            return Optional.of(SymbolKeys.typeKey(binding));
        }
        return typeText(type).flatMap(imports::resolve);
    }

    Optional<String> annotationClass(Name typeName) {
        return imports.resolve(typeName.getFullyQualifiedName());
    }

    private static Optional<String> typeText(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return typeText(parameterized.getType());
        }
        if (type instanceof SimpleType simple) {
            return Optional.of(simple.getName().getFullyQualifiedName());
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Write `FileContext`**

`src/main/java/com/graphify/indexer/FileContext.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexWarning;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Everything the visitors need about the file being indexed. */
final class FileContext {

    final String modulePath;
    final String filePath;
    final CompilationUnit unit;
    final SymbolRegistry symbols;
    final ConfidenceClassifier confidence;
    final NameOnlyResolver nameOnly;

    private final SourceLines lines;
    private final IndexCollector out;
    private final int snippetMaxLength;

    FileContext(String modulePath, String filePath, CompilationUnit unit, SourceLines lines, SymbolRegistry symbols,
            ConfidenceClassifier confidence, NameOnlyResolver nameOnly, IndexCollector out, int snippetMaxLength) {
        this.modulePath = modulePath;
        this.filePath = filePath;
        this.unit = unit;
        this.lines = lines;
        this.symbols = symbols;
        this.confidence = confidence;
        this.nameOnly = nameOnly;
        this.out = out;
        this.snippetMaxLength = snippetMaxLength;
    }

    void usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, ASTNode at) {
        usage(fromKey, toKey, kind, confidence, at, lines.snippet(line(at), snippetMaxLength));
    }

    void usage(String fromKey, String toKey, UsageKind kind, Confidence confidence, ASTNode at, String snippet) {
        if (fromKey == null || toKey == null) {
            return;
        }
        out.usage(new Usage(fromKey, toKey, kind, confidence, modulePath, filePath, line(at), column(at),
                SourceLines.truncate(snippet, snippetMaxLength)));
    }

    void declaration(String symbolKey, ASTNode name) {
        if (symbolKey == null) {
            return;
        }
        out.declaration(new Declaration(symbolKey, modulePath, filePath, line(name)));
    }

    void warning(ASTNode at, String message) {
        out.warning(new IndexWarning(modulePath, filePath, line(at), message));
    }

    private int line(ASTNode node) {
        return unit.getLineNumber(node.getStartPosition());
    }

    private int column(ASTNode node) {
        return unit.getColumnNumber(node.getStartPosition()) + 1;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ImportResolverTest,NameOnlyResolverTest'`
Expected: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/indexer src/test/java/com/graphify/indexer
git commit -m "feat(indexer): resolve binding-less names through imports" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Declarations, hierarchy, overrides and the indexer façade

**Files:**
- Create: `src/main/java/com/graphify/indexer/ScopedVisitor.java`
- Create: `src/main/java/com/graphify/indexer/Overrides.java`
- Create: `src/main/java/com/graphify/indexer/DeclarationVisitor.java`
- Create: `src/main/java/com/graphify/indexer/IndexerOptions.java`
- Create: `src/main/java/com/graphify/indexer/ModuleSource.java`
- Create: `src/main/java/com/graphify/indexer/IndexRequest.java`
- Create: `src/main/java/com/graphify/indexer/JavaRepositoryIndexer.java`
- Test: `src/test/java/com/graphify/indexer/TempRepo.java`
- Test: `src/test/java/com/graphify/indexer/IndexResults.java`
- Test: `src/test/java/com/graphify/indexer/DeclarationVisitorTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces:
  - `public record IndexerOptions(int parseBatchSize, int snippetMaxLength)` (both must be ≥ 1, else `IllegalArgumentException`).
  - `public record ModuleSource(String modulePath, List<Path> sourceRoots, List<Path> classpath)`.
  - `public record IndexRequest(Path repoRoot, List<ModuleSource> modules, IndexerOptions options)`.
  - `public class JavaRepositoryIndexer` with `public IndexResult index(IndexRequest request)`. Non-existent source roots are ignored; a file that throws is skipped with an `IndexWarning` (line 0).
  - `abstract class ScopedVisitor extends ASTVisitor`: field `protected final FileContext ctx`, method `protected final String currentScope()`. Subclasses overriding `visit(TypeDeclaration|EnumDeclaration|RecordDeclaration|AnnotationTypeDeclaration|MethodDeclaration)` must call `super.visit(node)` first.
  - `final class Overrides`: `static List<IMethodBinding> of(IMethodBinding method)`.
  - Test helpers: `TempRepo.at(Path)`, `TempRepo java(String module, String sourcePath, String content)`, `TempRepo classpath(String module, Path jar)`, `IndexRequest request(int parseBatchSize)`, `IndexResult index()`, `IndexResult index(int parseBatchSize)`; `IndexResults.symbol(result, key)`, `IndexResults.usage(result, fromKey, kind, toKey)`, `IndexResults.usages(result, fromKey, kind, toKey)`, `IndexResults.usagesTo(result, toKey)`.

- [ ] **Step 1: Write the test helpers**

`src/test/java/com/graphify/indexer/TempRepo.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.IndexResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a multi-module repository on disk ({@code <module>/src/main/java/...}) and indexes it. */
final class TempRepo {

    private final Path root;
    private final Map<String, List<Path>> classpaths = new LinkedHashMap<>();

    private TempRepo(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    static TempRepo at(Path root) {
        return new TempRepo(root);
    }

    TempRepo java(String module, String sourcePath, String content) throws IOException {
        return rawJava(module, sourcePath, content.getBytes(StandardCharsets.UTF_8));
    }

    TempRepo rawJava(String module, String sourcePath, byte[] content) throws IOException {
        Path file = sourceRoot(module).resolve(sourcePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        classpaths.computeIfAbsent(module, m -> new ArrayList<>());
        return this;
    }

    TempRepo classpath(String module, Path jar) {
        classpaths.computeIfAbsent(module, m -> new ArrayList<>()).add(jar);
        return this;
    }

    IndexRequest request(int parseBatchSize) {
        List<ModuleSource> modules = classpaths.entrySet().stream()
                .map(e -> new ModuleSource(e.getKey(), List.of(sourceRoot(e.getKey())), e.getValue()))
                .toList();
        return new IndexRequest(root, modules, new IndexerOptions(parseBatchSize, 200));
    }

    IndexResult index() {
        return index(50);
    }

    IndexResult index(int parseBatchSize) {
        return new JavaRepositoryIndexer().index(request(parseBatchSize));
    }

    private Path sourceRoot(String module) {
        return root.resolve(module).resolve("src/main/java");
    }
}
```

`src/test/java/com/graphify/indexer/IndexResults.java`:

```java
package com.graphify.indexer;

import static java.util.stream.Collectors.joining;

import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.util.List;

/** Lookup helpers that fail with the full usage list, which makes JDT surprises easy to diagnose. */
final class IndexResults {

    private IndexResults() {
    }

    static Symbol symbol(IndexResult result, String key) {
        return result.symbols().stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No symbol " + key + " in:\n"
                        + result.symbols().stream().map(Symbol::key).collect(joining("\n"))));
    }

    static List<Usage> usages(IndexResult result, String fromKey, UsageKind kind, String toKey) {
        return result.usages().stream()
                .filter(u -> u.fromKey().equals(fromKey) && u.kind() == kind && u.toKey().equals(toKey))
                .toList();
    }

    static Usage usage(IndexResult result, String fromKey, UsageKind kind, String toKey) {
        List<Usage> matches = usages(result, fromKey, kind, toKey);
        if (matches.isEmpty()) {
            throw new AssertionError("No " + kind + " usage " + fromKey + " -> " + toKey + ". Usages:\n"
                    + result.usages().stream().map(Usage::toString).collect(joining("\n")));
        }
        return matches.getFirst();
    }

    static List<Usage> usagesTo(IndexResult result, String toKey) {
        return result.usages().stream().filter(u -> u.toKey().equals(toKey)).toList();
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphify/indexer/DeclarationVisitorTest.java`:

```java
package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.symbol;
import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarationVisitorTest {

    @TempDir
    Path dir;

    @Test
    void declaresTypesMembersAndFieldsWithTheLineOfTheirName() throws Exception {
        IndexResult result = TempRepo.at(dir).java("lib", "com/acme/Money.java", """
                package com.acme;

                public class Money {
                    public static final String EUR = "EUR";
                    private int cents;

                    public Money(int cents) { this.cents = cents; }

                    public int cents() { return cents; }

                    public enum Currency { TRY, USD }
                }
                """).index();

        String file = "lib/src/main/java/com/acme/Money.java";
        assertThat(result.declarations())
                .extracting(Declaration::symbolKey, Declaration::modulePath, Declaration::filePath, Declaration::line)
                .contains(
                        tuple("com.acme.Money", "lib", file, 3),
                        tuple("com.acme.Money.EUR", "lib", file, 4),
                        tuple("com.acme.Money.cents", "lib", file, 5),
                        tuple("com.acme.Money#<init>(int)", "lib", file, 7),
                        tuple("com.acme.Money#cents()", "lib", file, 9),
                        tuple("com.acme.Money$Currency", "lib", file, 11),
                        tuple("com.acme.Money$Currency.TRY", "lib", file, 11),
                        tuple("com.acme.Money$Currency.USD", "lib", file, 11));
        assertThat(symbol(result, "com.acme.Money$Currency"))
                .extracting(Symbol::kind, Symbol::parentKey)
                .containsExactly(SymbolKind.ENUM, "com.acme.Money");
    }

    @Test
    void recordsExtendsImplementsAndOverridesAcrossTheSupertypeGraph() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("lib", "com/acme/Repo.java", """
                        package com.acme;
                        public interface Repo<T> extends AutoCloseable { void save(T item); }
                        """)
                .java("lib", "com/acme/BaseRepo.java", """
                        package com.acme;
                        public abstract class BaseRepo { public abstract void flush(); }
                        """)
                .java("lib", "com/acme/OrderRepo.java", """
                        package com.acme;
                        public class OrderRepo extends BaseRepo implements Repo<String>, Comparable<OrderRepo> {
                            public void save(String item) {}
                            public void flush() {}
                            public int compareTo(OrderRepo other) { return 0; }
                            public void close() {}
                        }
                        """)
                .index();

        assertThat(usage(result, "com.acme.OrderRepo", UsageKind.EXTENDS, "com.acme.BaseRepo").confidence())
                .isEqualTo(Confidence.EXACT);
        usage(result, "com.acme.OrderRepo", UsageKind.IMPLEMENTS, "com.acme.Repo");
        usage(result, "com.acme.OrderRepo", UsageKind.IMPLEMENTS, "java.lang.Comparable");
        usage(result, "com.acme.Repo", UsageKind.EXTENDS, "java.lang.AutoCloseable");
        usage(result, "com.acme.OrderRepo#save(java.lang.String)", UsageKind.OVERRIDES,
                "com.acme.Repo#save(java.lang.Object)");
        usage(result, "com.acme.OrderRepo#flush()", UsageKind.OVERRIDES, "com.acme.BaseRepo#flush()");
        usage(result, "com.acme.OrderRepo#compareTo(com.acme.OrderRepo)", UsageKind.OVERRIDES,
                "java.lang.Comparable#compareTo(java.lang.Object)");
        usage(result, "com.acme.OrderRepo#close()", UsageKind.OVERRIDES, "java.lang.AutoCloseable#close()");
    }

    @Test
    void anonymousClassesAreAttributedToTheEnclosingMethod() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/acme/Jobs.java", """
                package com.acme;
                class Jobs {
                    Runnable job() {
                        return new Runnable() { public void run() {} };
                    }
                }
                """).index();

        usage(result, "com.acme.Jobs#job()", UsageKind.IMPLEMENTS, "java.lang.Runnable");
        usage(result, "com.acme.Jobs#job()", UsageKind.OVERRIDES, "java.lang.Runnable#run()");
        assertThat(result.symbols()).extracting(Symbol::key).noneMatch(key -> key.contains("$1"));
    }

    @Test
    void sameClassInTwoModulesIsOneSymbolWithTwoDeclarations() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("a", "com/acme/Dup.java", "package com.acme; public class Dup {}")
                .java("b", "com/acme/Dup.java", "package com.acme; public class Dup {}")
                .index();

        assertThat(result.symbols()).extracting(Symbol::key).containsOnlyOnce("com.acme.Dup");
        assertThat(result.declarations())
                .filteredOn(d -> d.symbolKey().equals("com.acme.Dup"))
                .extracting(Declaration::modulePath)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void missingSuperclassFallsBackToTheImportedName() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/acme/Controller.java", """
                package com.acme;
                import org.vendor.web.BaseController;
                public class Controller extends BaseController {}
                """).index();

        assertThat(usage(result, "com.acme.Controller", UsageKind.EXTENDS, "org.vendor.web.BaseController")
                .confidence()).isEqualTo(Confidence.NAME_ONLY);
    }

    @Test
    void rejectsNonPositiveOptions() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new IndexerOptions(0, 10));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new IndexerOptions(10, 0));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DeclarationVisitorTest`
Expected: BUILD FAILURE, `cannot find symbol` for `JavaRepositoryIndexer`, `IndexRequest`, `ModuleSource`, `IndexerOptions`.

- [ ] **Step 4: Write the public request types**

`src/main/java/com/graphify/indexer/IndexerOptions.java`:

```java
package com.graphify.indexer;

/** Tuning for one run. Values come from the caller (APP_SETTING in later plans), never from constants here. */
public record IndexerOptions(int parseBatchSize, int snippetMaxLength) {

    public IndexerOptions {
        if (parseBatchSize < 1) {
            throw new IllegalArgumentException("parseBatchSize must be >= 1 but was " + parseBatchSize);
        }
        if (snippetMaxLength < 1) {
            throw new IllegalArgumentException("snippetMaxLength must be >= 1 but was " + snippetMaxLength);
        }
    }
}
```

`src/main/java/com/graphify/indexer/ModuleSource.java`:

```java
package com.graphify.indexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One Maven module: the source roots whose files are indexed and the jars its bindings resolve against.
 * An empty classpath is valid (NONE mode): JDK and repo sources still resolve, everything else is NAME_ONLY.
 */
public record ModuleSource(String modulePath, List<Path> sourceRoots, List<Path> classpath) {

    public ModuleSource {
        Objects.requireNonNull(modulePath, "modulePath");
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
    }
}
```

`src/main/java/com/graphify/indexer/IndexRequest.java`:

```java
package com.graphify.indexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record IndexRequest(Path repoRoot, List<ModuleSource> modules, IndexerOptions options) {

    public IndexRequest {
        Objects.requireNonNull(repoRoot, "repoRoot");
        modules = List.copyOf(modules);
        Objects.requireNonNull(options, "options");
    }
}
```

- [ ] **Step 5: Write `ScopedVisitor` and `Overrides`**

`src/main/java/com/graphify/indexer/ScopedVisitor.java`:

```java
package com.graphify.indexer;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * Tracks the symbol that owns the code being visited: the nearest named method/constructor, else the class.
 * Lambdas and anonymous classes do not open a scope, so their code belongs to the enclosing named method.
 */
abstract class ScopedVisitor extends ASTVisitor {

    protected final FileContext ctx;
    private final List<String> scopes = new ArrayList<>();

    ScopedVisitor(FileContext ctx) {
        super(false);
        this.ctx = ctx;
    }

    protected final String currentScope() {
        return scopes.isEmpty() ? null : scopes.getLast();
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(EnumDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(RecordDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(AnnotationTypeDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        IMethodBinding binding = node.resolveBinding();
        boolean ownScope = binding != null && !binding.getDeclaringClass().isAnonymous();
        scopes.add(ownScope ? ctx.symbols.method(binding) : currentScope());
        return true;
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        exit();
    }

    private boolean enterType(ITypeBinding binding) {
        scopes.add(binding == null ? currentScope() : ctx.symbols.type(binding));
        return true;
    }

    private void exit() {
        scopes.removeLast();
    }
}
```

`src/main/java/com/graphify/indexer/Overrides.java`:

```java
package com.graphify.indexer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

/** Every method that a method overrides or implements, searched through all supertypes. */
final class Overrides {

    private Overrides() {
    }

    static List<IMethodBinding> of(IMethodBinding method) {
        int modifiers = method.getModifiers();
        if (method.isConstructor() || Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) {
            return List.of();
        }
        Map<String, IMethodBinding> found = new LinkedHashMap<>();
        Set<String> visitedTypes = new HashSet<>();
        Deque<ITypeBinding> queue = new ArrayDeque<>(supertypes(method.getDeclaringClass()));
        while (!queue.isEmpty()) {
            ITypeBinding type = queue.poll();
            if (!visitedTypes.add(SymbolKeys.typeKey(type))) {
                continue;
            }
            for (IMethodBinding candidate : type.getDeclaredMethods()) {
                if (method.overrides(candidate)) {
                    found.putIfAbsent(SymbolKeys.methodKey(candidate), candidate);
                }
            }
            queue.addAll(supertypes(type));
        }
        return List.copyOf(found.values());
    }

    private static List<ITypeBinding> supertypes(ITypeBinding type) {
        List<ITypeBinding> supertypes = new ArrayList<>();
        if (type.getSuperclass() != null) {
            supertypes.add(type.getSuperclass());
        }
        supertypes.addAll(Arrays.asList(type.getInterfaces()));
        return supertypes;
    }
}
```

- [ ] **Step 6: Write `DeclarationVisitor`**

`src/main/java/com/graphify/indexer/DeclarationVisitor.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/** Declarations of types, methods and fields, plus EXTENDS / IMPLEMENTS / OVERRIDES usages. */
final class DeclarationVisitor extends ScopedVisitor {

    DeclarationVisitor(FileContext ctx) {
        super(ctx);
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        super.visit(node);
        declareType(node);
        if (node.getSuperclassType() != null) {
            supertype(node.getSuperclassType(), UsageKind.EXTENDS);
        }
        UsageKind interfaceKind = node.isInterface() ? UsageKind.EXTENDS : UsageKind.IMPLEMENTS;
        for (Object type : node.superInterfaceTypes()) {
            supertype((Type) type, interfaceKind);
        }
        return true;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        super.visit(node);
        declareType(node);
        for (Object type : node.superInterfaceTypes()) {
            supertype((Type) type, UsageKind.IMPLEMENTS);
        }
        return true;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        super.visit(node);
        declareType(node);
        for (Object type : node.superInterfaceTypes()) {
            supertype((Type) type, UsageKind.IMPLEMENTS);
        }
        return true;
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        super.visit(node);
        declareType(node);
        return true;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        super.visit(node);
        IMethodBinding binding = node.resolveBinding();
        if (binding == null) {
            return true;
        }
        String from = currentScope();
        if (!binding.getDeclaringClass().isAnonymous()) {
            ctx.declaration(from, node.getName());
        }
        for (IMethodBinding overridden : Overrides.of(binding)) {
            ctx.usage(from, ctx.symbols.method(overridden), UsageKind.OVERRIDES,
                    ctx.confidence.of(binding, node.getName()), node.getName());
        }
        return true;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        for (Object item : node.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) item;
            IVariableBinding binding = fragment.resolveBinding();
            if (binding != null && binding.isField() && !binding.getDeclaringClass().isAnonymous()) {
                ctx.declaration(ctx.symbols.field(binding), fragment.getName());
            }
        }
        return true;
    }

    @Override
    public boolean visit(EnumConstantDeclaration node) {
        IVariableBinding binding = node.resolveVariable();
        if (binding != null) {
            ctx.declaration(ctx.symbols.field(binding), node.getName());
        }
        return true;
    }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        ITypeBinding anonymous = node.resolveBinding();
        if (anonymous == null || !(node.getParent() instanceof ClassInstanceCreation creation)) {
            return true;
        }
        boolean implementsInterface = anonymous.getInterfaces().length > 0;
        ITypeBinding base = implementsInterface ? anonymous.getInterfaces()[0] : anonymous.getSuperclass();
        UsageKind kind = implementsInterface ? UsageKind.IMPLEMENTS : UsageKind.EXTENDS;
        if (base != null && !base.isRecovered()) {
            ctx.usage(currentScope(), ctx.symbols.type(base), kind, ctx.confidence.of(base, creation.getType()),
                    creation.getType());
        } else {
            ctx.nameOnly.typeClass(creation.getType()).ifPresent(fqn -> ctx.usage(currentScope(),
                    ctx.symbols.nameOnlyType(fqn), kind, Confidence.NAME_ONLY, creation.getType()));
        }
        return true;
    }

    private void declareType(AbstractTypeDeclaration node) {
        if (node.resolveBinding() != null) {
            ctx.declaration(currentScope(), node.getName());
        }
    }

    private void supertype(Type type, UsageKind kind) {
        String from = currentScope();
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && !binding.isRecovered()) {
            ctx.usage(from, ctx.symbols.type(binding), kind, ctx.confidence.of(binding, type), type);
            return;
        }
        ctx.nameOnly.typeClass(type).ifPresentOrElse(
                fqn -> ctx.usage(from, ctx.symbols.nameOnlyType(fqn), kind, Confidence.NAME_ONLY, type),
                () -> ctx.warning(type, "Unresolved supertype " + type));
    }
}
```

- [ ] **Step 7: Write the façade `JavaRepositoryIndexer`**

`src/main/java/com/graphify/indexer/JavaRepositoryIndexer.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.IndexWarning;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Indexes one repository checkout: every module's sources, with bindings from its own classpath. */
public class JavaRepositoryIndexer {

    private final JdtParser parser = new JdtParser();

    public IndexResult index(IndexRequest request) {
        Path repoRoot = request.repoRoot().toAbsolutePath().normalize();
        SymbolRegistry symbols = new SymbolRegistry();
        IndexCollector out = new IndexCollector();
        List<Path> sourcepath = request.modules().stream()
                .flatMap(module -> existingRoots(module).stream())
                .distinct()
                .toList();
        for (ModuleSource module : request.modules()) {
            parser.parse(javaFiles(existingRoots(module)), module.classpath(), sourcepath,
                    request.options().parseBatchSize(),
                    (file, unit) -> indexFile(repoRoot, request.options(), module, file, unit, symbols, out));
        }
        return new IndexResult(symbols.all(), out.declarations(), out.usages(), out.warnings());
    }

    private void indexFile(Path repoRoot, IndexerOptions options, ModuleSource module, Path file,
            CompilationUnit unit, SymbolRegistry symbols, IndexCollector out) {
        String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
        try {
            FileContext ctx = new FileContext(module.modulePath(), relativePath, unit, SourceLines.read(file),
                    symbols, new ConfidenceClassifier(unit), new NameOnlyResolver(new ImportResolver(unit)), out,
                    options.snippetMaxLength());
            unit.accept(new DeclarationVisitor(ctx));
        } catch (IOException | RuntimeException e) {
            out.warning(new IndexWarning(module.modulePath(), relativePath, 0, "File skipped: " + e));
        }
    }

    private static List<Path> existingRoots(ModuleSource module) {
        return module.sourceRoots().stream()
                .map(root -> root.toAbsolutePath().normalize())
                .filter(Files::isDirectory)
                .toList();
    }

    private static List<Path> javaFiles(List<Path> roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java") && Files.isRegularFile(path))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw test -Dtest=DeclarationVisitorTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 9: Run the whole suite**

Run: `./mvnw test`
Expected: all tests pass, BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/graphify/indexer src/test/java/com/graphify/indexer
git commit -m "feat(indexer): index declarations, hierarchy and overrides" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Calls, instantiations and method references

**Files:**
- Create: `src/main/java/com/graphify/indexer/ReferenceVisitor.java`
- Modify: `src/main/java/com/graphify/indexer/JavaRepositoryIndexer.java` (run `ReferenceVisitor` after `DeclarationVisitor`)
- Test: `src/test/java/com/graphify/indexer/TestJars.java`
- Test: `src/test/java/com/graphify/indexer/ReferenceVisitorCallsTest.java`

**Interfaces:**
- Consumes: `ScopedVisitor`, `FileContext`, `SymbolRegistry`, `NameOnlyResolver`, `TempRepo`, `IndexResults` (Tasks 2–5).
- Produces:
  - `final class ReferenceVisitor extends ScopedVisitor` with constructor `ReferenceVisitor(FileContext)`. This task covers `CALL`, `INSTANTIATION` and `METHOD_REF`; Task 7 adds field, type and annotation usages to the same class.
  - Test helper `TestJars.jar(Path workDir, String name, Map<String, String> sources, Set<String> omittedClassFiles)`, which compiles `sources` (keys are paths like `com/vendor/http/RestClient.java`) with the JDK compiler and returns a jar that leaves out the listed entries (e.g. `com/vendor/http/TypeRef.class`).

- [ ] **Step 1: Write the test jar helper**

`src/test/java/com/graphify/indexer/TestJars.java`:

```java
package com.graphify.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles a small library into a jar so tests can exercise third-party classpath resolution offline. */
final class TestJars {

    private TestJars() {
    }

    static Path jar(Path workDir, String name, Map<String, String> sources, Set<String> omittedClassFiles)
            throws IOException {
        Path sourceDir = workDir.resolve(name + "-src");
        Path classesDir = workDir.resolve(name + "-classes");
        Files.createDirectories(classesDir);
        List<String> arguments = new ArrayList<>(List.of("-d", classesDir.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac.run(null, null, null, arguments.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("javac failed for test jar " + name);
        }
        Path jar = workDir.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> classes = Files.walk(classesDir)) {
            for (Path classFile : classes.filter(Files::isRegularFile).sorted().toList()) {
                String entry = classesDir.relativize(classFile).toString().replace('\\', '/');
                if (omittedClassFiles.contains(entry)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry));
                Files.copy(classFile, out);
                out.closeEntry();
            }
        }
        return jar;
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphify/indexer/ReferenceVisitorCallsTest.java`:

```java
package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static com.graphify.indexer.IndexResults.usages;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceVisitorCallsTest {

    private static final String MONEY_UTIL = """
            package com.corp.common;
            public class MoneyUtil {
                public String format(String amount) { return amount; }
                public String format(int amount) { return String.valueOf(amount); }
                public static MoneyUtil instance() { return new MoneyUtil(); }
            }
            """;

    private static final Map<String, String> VENDOR_LIBRARY = Map.of(
            "com/vendor/http/RestClient.java", """
                    package com.vendor.http;
                    public class RestClient {
                        public <T> T exchange(String url, Class<T> type) { return null; }
                        public <T> T exchange(String url, TypeRef<T> type) { return null; }
                    }
                    """,
            "com/vendor/http/TypeRef.java", """
                    package com.vendor.http;
                    public abstract class TypeRef<T> {}
                    """);

    private static final String API = """
            package com.corp.order;
            import com.vendor.http.RestClient;
            class Api {
                String get() {
                    RestClient client = new RestClient();
                    return client.exchange("u", String.class);
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void resolvesOverloadsVarChainedStaticImportedCallsAndLambdas() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("lib", "com/corp/common/MoneyUtil.java", MONEY_UTIL)
                .java("app", "com/corp/order/Calls.java", """
                        package com.corp.order;
                        import static com.corp.common.MoneyUtil.instance;
                        import com.corp.common.MoneyUtil;
                        import java.util.List;
                        class Calls {
                            private final MoneyUtil money = new MoneyUtil();
                            void a() { money.format("10"); }
                            void b() { money.format(5); }
                            void c() { var m = new MoneyUtil(); m.format(1); }
                            void d() { MoneyUtil.instance().format("x"); }
                            void e(List<String> xs) { xs.forEach(s -> money.format(s)); }
                            void h() { instance(); }
                        }
                        """)
                .index();

        String formatString = "com.corp.common.MoneyUtil#format(java.lang.String)";
        String formatInt = "com.corp.common.MoneyUtil#format(int)";
        assertThat(usage(result, "com.corp.order.Calls#a()", UsageKind.CALL, formatString))
                .extracting(Usage::confidence, Usage::line, Usage::snippet)
                .containsExactly(Confidence.EXACT, 7, "void a() { money.format(\"10\"); }");
        usage(result, "com.corp.order.Calls#h()", UsageKind.CALL, "com.corp.common.MoneyUtil#instance()");
        usage(result, "com.corp.order.Calls#b()", UsageKind.CALL, formatInt);
        usage(result, "com.corp.order.Calls#c()", UsageKind.CALL, formatInt);
        usage(result, "com.corp.order.Calls#c()", UsageKind.INSTANTIATION, "com.corp.common.MoneyUtil#<init>()");
        usage(result, "com.corp.order.Calls#d()", UsageKind.CALL, "com.corp.common.MoneyUtil#instance()");
        usage(result, "com.corp.order.Calls#d()", UsageKind.CALL, formatString);
        usage(result, "com.corp.order.Calls#e(java.util.List)", UsageKind.CALL, formatString);
        usage(result, "com.corp.order.Calls", UsageKind.INSTANTIATION, "com.corp.common.MoneyUtil#<init>()");
    }

    @Test
    void usagesAreAttributedToNearestNamedMemberOrClass() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Scopes.java", """
                package p;
                class Scopes {
                    static final int SIZE = compute();
                    static { compute(); }
                    static int compute() { return 1; }
                    void run() {
                        Runnable r = new Runnable() { public void run() { compute(); } };
                        java.util.function.IntSupplier s = () -> compute();
                    }
                }
                """).index();

        assertThat(usages(result, "p.Scopes", UsageKind.CALL, "p.Scopes#compute()"))
                .extracting(Usage::line).containsExactlyInAnyOrder(3, 4);
        assertThat(usages(result, "p.Scopes#run()", UsageKind.CALL, "p.Scopes#compute()"))
                .extracting(Usage::line).containsExactlyInAnyOrder(7, 8);
    }

    @Test
    void recordsMethodReferencesAndConstructorChaining() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("lib", "com/corp/common/MoneyUtil.java", MONEY_UTIL)
                .java("app", "com/corp/order/Refs.java", """
                        package com.corp.order;
                        import com.corp.common.MoneyUtil;
                        import java.util.function.Function;
                        import java.util.function.Supplier;
                        class Refs {
                            Refs() { this(1); }
                            Refs(int x) { super(); }
                            void refs(MoneyUtil money) {
                                Function<String, String> f = money::format;
                                Supplier<MoneyUtil> s = MoneyUtil::new;
                            }
                        }
                        """)
                .index();

        String refs = "com.corp.order.Refs#refs(com.corp.common.MoneyUtil)";
        usage(result, refs, UsageKind.METHOD_REF, "com.corp.common.MoneyUtil#format(java.lang.String)");
        usage(result, refs, UsageKind.METHOD_REF, "com.corp.common.MoneyUtil#<init>()");
        usage(result, "com.corp.order.Refs#<init>()", UsageKind.CALL, "com.corp.order.Refs#<init>(int)");
        usage(result, "com.corp.order.Refs#<init>(int)", UsageKind.CALL, "java.lang.Object#<init>()");
    }

    @Test
    void thirdPartyCallIsExactWhenTheJarIsOnTheClasspath() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "vendor", VENDOR_LIBRARY, Set.of());

        IndexResult result = TempRepo.at(dir.resolve("repo"))
                .java("app", "com/corp/order/Api.java", API)
                .classpath("app", jar)
                .index();

        assertThat(usage(result, "com.corp.order.Api#get()", UsageKind.CALL,
                "com.vendor.http.RestClient#exchange(java.lang.String,java.lang.Class)").confidence())
                .isEqualTo(Confidence.EXACT);
    }

    @Test
    void thirdPartyCallIsRecoveredWhenTheJarIsIncomplete() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "vendor", VENDOR_LIBRARY,
                Set.of("com/vendor/http/TypeRef.class"));

        IndexResult result = TempRepo.at(dir.resolve("repo"))
                .java("app", "com/corp/order/Api.java", API)
                .classpath("app", jar)
                .index();

        assertThat(result.usages())
                .filteredOn(u -> u.kind() == UsageKind.CALL && u.toKey().startsWith("com.vendor.http.RestClient#exchange("))
                .singleElement()
                .extracting(Usage::confidence)
                .isEqualTo(Confidence.RECOVERED);
    }

    @Test
    void thirdPartyCallIsNameOnlyThroughImportsWhenTheJarIsMissing() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/order/Api.java", API).index();

        assertThat(usage(result, "com.corp.order.Api#get()", UsageKind.CALL,
                "com.vendor.http.RestClient#exchange/2").confidence()).isEqualTo(Confidence.NAME_ONLY);
        assertThat(usage(result, "com.corp.order.Api#get()", UsageKind.INSTANTIATION,
                "com.vendor.http.RestClient#<init>/0").confidence()).isEqualTo(Confidence.NAME_ONLY);
        assertThat(result.usages()).noneMatch(u -> u.toKey().startsWith("com.corp.order.RestClient"));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ReferenceVisitorCallsTest`
Expected: tests compile, then FAIL. For example `resolvesOverloadsVarChainedStaticImportedCallsAndLambdas` fails with `AssertionError: No CALL usage com.corp.order.Calls#a() -> com.corp.common.MoneyUtil#format(java.lang.String)`, because nothing emits calls yet.

- [ ] **Step 4: Write `ReferenceVisitor` (calls part)**

`src/main/java/com/graphify/indexer/ReferenceVisitor.java`:

```java
package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;

/** Usages other than declarations and hierarchy: calls, instantiations, method references. */
final class ReferenceVisitor extends ScopedVisitor {

    ReferenceVisitor(FileContext ctx) {
        super(ctx);
    }

    @Override
    public boolean visit(MethodInvocation node) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (isResolved(binding)) {
            call(UsageKind.CALL, binding, node);
        } else {
            nameOnlyCall(node.getExpression(), node.getName().getIdentifier(), node.arguments().size(), node);
        }
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        resolvedOrWarn(UsageKind.CALL, node.resolveMethodBinding(), node, "Unresolved super call " + node.getName());
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        if (node.getAnonymousClassDeclaration() != null) {
            return true;
        }
        IMethodBinding binding = node.resolveConstructorBinding();
        if (isResolved(binding)) {
            call(UsageKind.INSTANTIATION, binding, node);
            return true;
        }
        ctx.nameOnly.typeClass(node.getType()).ifPresentOrElse(
                fqn -> ctx.usage(currentScope(), ctx.symbols.nameOnlyConstructor(fqn, node.arguments().size()),
                        UsageKind.INSTANTIATION, Confidence.NAME_ONLY, node),
                () -> ctx.warning(node, "Unresolved constructor " + node.getType()));
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        resolvedOrWarn(UsageKind.CALL, node.resolveConstructorBinding(), node, "Unresolved this(...) call");
        return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        resolvedOrWarn(UsageKind.CALL, node.resolveConstructorBinding(), node, "Unresolved super(...) call");
        return true;
    }

    @Override
    public boolean visit(ExpressionMethodReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(CreationReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    /** A binding whose declaring class is recovered carries JDT's guessed (wrong) package, so it is not used. */
    private static boolean isResolved(IMethodBinding binding) {
        return binding != null && !binding.getDeclaringClass().isRecovered();
    }

    private void call(UsageKind kind, IMethodBinding binding, ASTNode node) {
        ctx.usage(currentScope(), ctx.symbols.method(binding), kind, ctx.confidence.of(binding, node), node);
    }

    private void resolvedOrWarn(UsageKind kind, IMethodBinding binding, ASTNode node, String warning) {
        if (isResolved(binding)) {
            call(kind, binding, node);
        } else {
            ctx.warning(node, warning);
        }
    }

    private void nameOnlyCall(Expression receiver, String methodName, int argumentCount, ASTNode node) {
        ctx.nameOnly.receiverClass(receiver).ifPresentOrElse(
                fqn -> ctx.usage(currentScope(), ctx.symbols.nameOnlyMethod(fqn, methodName, argumentCount),
                        UsageKind.CALL, Confidence.NAME_ONLY, node),
                () -> ctx.warning(node, "Unresolved call " + methodName + "/" + argumentCount));
    }
}
```

- [ ] **Step 5: Run `ReferenceVisitor` from the façade**

In `src/main/java/com/graphify/indexer/JavaRepositoryIndexer.java`, method `indexFile`, replace:

```java
            unit.accept(new DeclarationVisitor(ctx));
```

with:

```java
            unit.accept(new DeclarationVisitor(ctx));
            unit.accept(new ReferenceVisitor(ctx));
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ReferenceVisitorCallsTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 7: Run the whole suite**

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/graphify/indexer src/test/java/com/graphify/indexer
git commit -m "feat(indexer): index calls, instantiations and method references" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Field accesses, type references and annotations

**Files:**
- Modify: `src/main/java/com/graphify/indexer/ReferenceVisitor.java`
- Test: `src/test/java/com/graphify/indexer/ReferenceVisitorTypesTest.java`

**Interfaces:**
- Consumes: `ReferenceVisitor` (Task 6), `TempRepo`, `IndexResults` (Task 5).
- Produces: `ReferenceVisitor` additionally emits `FIELD_READ`, `FIELD_WRITE`, `TYPE_REF` and `ANNOTATION`. An `ANNOTATION` usage's `snippet` is the annotation's source text.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/indexer/ReferenceVisitorTypesTest.java`:

```java
package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static com.graphify.indexer.IndexResults.usages;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceVisitorTypesTest {

    @TempDir
    Path dir;

    @Test
    void distinguishesFieldReadsFromWrites() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Counter.java", """
                package p;
                class Counter {
                    static final int LIMIT = 10;
                    int count;
                    Counter other;
                    void touch() {
                        count++;
                        this.count = LIMIT;
                        other.count += 1;
                        int seen = other.count;
                        int max = Counter.LIMIT;
                    }
                }
                """).index();

        String touch = "p.Counter#touch()";
        assertThat(usages(result, touch, UsageKind.FIELD_WRITE, "p.Counter.count"))
                .extracting(Usage::line).containsExactlyInAnyOrder(7, 8, 9);
        assertThat(usages(result, touch, UsageKind.FIELD_READ, "p.Counter.count"))
                .extracting(Usage::line).containsExactly(10);
        assertThat(usages(result, touch, UsageKind.FIELD_READ, "p.Counter.LIMIT"))
                .extracting(Usage::line).containsExactlyInAnyOrder(8, 11);
        assertThat(usages(result, touch, UsageKind.FIELD_READ, "p.Counter.other"))
                .extracting(Usage::line).containsExactlyInAnyOrder(9, 10);
    }

    @Test
    void typeReferencesSkipVarConstructedTypesAndSupertypes() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/Types.java", """
                package p;
                import java.util.ArrayList;
                import java.util.List;
                class Types extends Base implements Marker {
                    List<Item> items = new ArrayList<Item>();
                    Item first(List<Item> in) { var copy = new ArrayList<>(in); return (Item) copy.get(0); }
                }
                class Base {}
                interface Marker {}
                class Item {}
                """).index();

        assertThat(usages(result, "p.Types", UsageKind.TYPE_REF, "java.util.List")).extracting(Usage::line)
                .containsExactly(5);
        assertThat(usages(result, "p.Types", UsageKind.TYPE_REF, "p.Item")).hasSize(2);
        String first = "p.Types#first(java.util.List)";
        assertThat(usages(result, first, UsageKind.TYPE_REF, "p.Item")).hasSize(3);
        assertThat(usages(result, first, UsageKind.TYPE_REF, "java.util.List")).hasSize(1);
        assertThat(result.usages()).filteredOn(u -> u.kind() == UsageKind.TYPE_REF)
                .extracting(Usage::toKey)
                .doesNotContain("java.util.ArrayList", "p.Base", "p.Marker");
    }

    @Test
    void annotationsKeepTheirSourceTextAsSnippet() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Route.java", """
                        package p;
                        public @interface Route { String value(); }
                        """)
                .java("app", "p/C.java", """
                        package p;
                        class C {
                            @Route("/orders")
                            void list() {}
                        }
                        """)
                .index();

        assertThat(usage(result, "p.C#list()", UsageKind.ANNOTATION, "p.Route"))
                .extracting(Usage::confidence, Usage::snippet)
                .containsExactly(Confidence.EXACT, "@Route(\"/orders\")");
    }

    @Test
    void missingAnnotationTypeIsNameOnlyThroughImports() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "p/D.java", """
                package p;
                import org.springframework.web.bind.annotation.PostMapping;
                class D {
                    @PostMapping("/orders")
                    void create() {}
                }
                """).index();

        Usage annotation = usage(result, "p.D#create()", UsageKind.ANNOTATION,
                "org.springframework.web.bind.annotation.PostMapping");
        assertThat(annotation.confidence()).isEqualTo(Confidence.NAME_ONLY);
        assertThat(annotation.snippet()).contains("@PostMapping").contains("/orders");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ReferenceVisitorTypesTest`
Expected: 4 failures such as `AssertionError: No ANNOTATION usage p.C#list() -> p.Route`, and empty-list mismatches for the field and type assertions.

- [ ] **Step 3: Add field, type and annotation handling to `ReferenceVisitor`**

Add these imports to `src/main/java/com/graphify/indexer/ReferenceVisitor.java`:

```java
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
```

Update the class Javadoc to `/** Usages other than declarations and hierarchy: calls, instantiations, method references, fields, types, annotations. */`, then add these methods inside the class, after `visit(CreationReference)`:

```java
    @Override
    public boolean visit(SimpleName node) {
        if (node.isDeclaration()) {
            return true;
        }
        if (node.resolveBinding() instanceof IVariableBinding variable && variable.isField()) {
            UsageKind kind = isWriteTarget(node) ? UsageKind.FIELD_WRITE : UsageKind.FIELD_READ;
            ctx.usage(currentScope(), ctx.symbols.field(variable), kind, ctx.confidence.of(variable, node), node);
        }
        return true;
    }

    @Override
    public boolean visit(SimpleType node) {
        typeReference(node);
        return true;
    }

    @Override
    public boolean visit(QualifiedType node) {
        typeReference(node);
        return true;
    }

    @Override
    public boolean visit(MarkerAnnotation node) {
        annotation(node);
        return true;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        annotation(node);
        return true;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        annotation(node);
        return true;
    }

    private static boolean isWriteTarget(SimpleName name) {
        ASTNode target = name;
        while (true) {
            ASTNode parent = target.getParent();
            boolean nameOfAccess = (parent instanceof FieldAccess access && access.getName() == target)
                    || (parent instanceof SuperFieldAccess superAccess && superAccess.getName() == target)
                    || (parent instanceof QualifiedName qualified && qualified.getName() == target);
            if (nameOfAccess || parent instanceof ParenthesizedExpression) {
                target = parent;
            } else {
                break;
            }
        }
        ASTNode parent = target.getParent();
        if (parent instanceof Assignment assignment) {
            return assignment.getLeftHandSide() == target;
        }
        if (parent instanceof PostfixExpression) {
            return true;
        }
        return parent instanceof PrefixExpression prefix
                && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                        || prefix.getOperator() == PrefixExpression.Operator.DECREMENT);
    }

    private void typeReference(Type type) {
        if (type.isVar() || isConstructedOrSupertype(type)
                || type.getLocationInParent() == QualifiedType.QUALIFIER_PROPERTY) {
            return;
        }
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && binding.isTypeVariable()) {
            return;
        }
        if (binding != null && !binding.isRecovered()) {
            ctx.usage(currentScope(), ctx.symbols.type(binding), UsageKind.TYPE_REF,
                    ctx.confidence.of(binding, type), type);
            return;
        }
        ctx.nameOnly.typeClass(type).ifPresent(fqn -> ctx.usage(currentScope(), ctx.symbols.nameOnlyType(fqn),
                UsageKind.TYPE_REF, Confidence.NAME_ONLY, type));
    }

    /** {@code new Foo<>()} is an INSTANTIATION and {@code extends Foo} is hierarchy; neither is a TYPE_REF. */
    private static boolean isConstructedOrSupertype(Type type) {
        ASTNode node = type;
        while (node.getLocationInParent() == ParameterizedType.TYPE_PROPERTY) {
            node = node.getParent();
        }
        StructuralPropertyDescriptor location = node.getLocationInParent();
        return location == ClassInstanceCreation.TYPE_PROPERTY
                || location == TypeDeclaration.SUPERCLASS_TYPE_PROPERTY
                || location == TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || location == EnumDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || location == RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY;
    }

    /** The snippet is the annotation's own text so later plans can read e.g. endpoint paths from it. */
    private void annotation(Annotation node) {
        IAnnotationBinding binding = node.resolveAnnotationBinding();
        ITypeBinding type = binding == null ? null : binding.getAnnotationType();
        String snippet = node.toString();
        if (type != null && !type.isRecovered()) {
            ctx.usage(currentScope(), ctx.symbols.type(type), UsageKind.ANNOTATION, ctx.confidence.of(type, node),
                    node, snippet);
            return;
        }
        ctx.nameOnly.annotationClass(node.getTypeName()).ifPresent(fqn -> ctx.usage(currentScope(),
                ctx.symbols.nameOnlyType(fqn), UsageKind.ANNOTATION, Confidence.NAME_ONLY, node, snippet));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ReferenceVisitorTypesTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw test`
Expected: all tests pass. If `ReferenceVisitorCallsTest` now fails on an exact-count assertion, the new `TYPE_REF`/`FIELD_*` usages are the cause. Each test there filters by kind, so no change should be needed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/indexer/ReferenceVisitor.java src/test/java/com/graphify/indexer/ReferenceVisitorTypesTest.java
git commit -m "feat(indexer): index field accesses, type references and annotations" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Fixture acceptance test and robustness

**Files:**
- Create: `src/test/resources/fixtures/corp-repo/common-lib/src/main/java/com/corp/common/MoneyUtil.java`
- Create: `src/test/resources/fixtures/corp-repo/common-lib/src/main/java/com/corp/common/DateUtil.java`
- Create: `src/test/resources/fixtures/corp-repo/common-lib/src/main/java/com/corp/common/PaymentGateway.java`
- Create: `src/test/resources/fixtures/corp-repo/order-service/src/main/java/com/corp/order/OrderService.java`
- Create: `src/test/resources/fixtures/corp-repo/order-service/src/main/java/com/corp/order/CardPaymentGateway.java`
- Test: `src/test/java/com/graphify/indexer/CorpRepoFixtureTest.java`
- Test: `src/test/java/com/graphify/indexer/RobustnessTest.java`

**Interfaces:**
- Consumes: `JavaRepositoryIndexer`, `IndexRequest`, `ModuleSource`, `IndexerOptions` (Task 5); `TempRepo.rawJava` (Task 5); `IndexResults` (Task 5).
- Produces: the fixture repo `fixtures/corp-repo` (two modules: `common-lib` and `order-service`). Plan 4's end-to-end test reuses it as two git repositories.

- [ ] **Step 1: Create the fixture repository**

`src/test/resources/fixtures/corp-repo/common-lib/src/main/java/com/corp/common/MoneyUtil.java`:

```java
package com.corp.common;

public class MoneyUtil {
    public String format(String amount) { return amount; }
    public String format(int amount) { return String.valueOf(amount); }
    public static MoneyUtil instance() { return new MoneyUtil(); }
}
```

`src/test/resources/fixtures/corp-repo/common-lib/src/main/java/com/corp/common/DateUtil.java`:

```java
package com.corp.common;

public class DateUtil {
    public String format(String date) { return date; }
}
```

`src/test/resources/fixtures/corp-repo/common-lib/src/main/java/com/corp/common/PaymentGateway.java`:

```java
package com.corp.common;

public interface PaymentGateway {
    String pay(int amount);
}
```

`src/test/resources/fixtures/corp-repo/order-service/src/main/java/com/corp/order/OrderService.java` (the line numbers matter, keep the blank lines):

```java
package com.corp.order;

import com.corp.common.DateUtil;
import com.corp.common.MoneyUtil;
import com.corp.common.PaymentGateway;
import java.util.List;

public class OrderService {
    private final MoneyUtil money = new MoneyUtil();
    private final DateUtil date = new DateUtil();
    private final PaymentGateway gateway;

    public OrderService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public void a() { money.format("10"); }
    public void b() { money.format(5); }
    public void c() { var m = new MoneyUtil(); m.format(1); }
    public void d() { MoneyUtil.instance().format("x"); }
    public void e(List<String> xs) { xs.forEach(s -> money.format(s)); }
    public void f() { date.format("2020"); }
    public void g() { gateway.pay(5); }
}
```

`src/test/resources/fixtures/corp-repo/order-service/src/main/java/com/corp/order/CardPaymentGateway.java`:

```java
package com.corp.order;

import com.corp.common.PaymentGateway;

public class CardPaymentGateway implements PaymentGateway {
    @Override
    public String pay(int amount) { return "card:" + amount; }
}
```

- [ ] **Step 2: Write the acceptance test**

`src/test/java/com/graphify/indexer/CorpRepoFixtureTest.java`:

```java
package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The cases graphify missed or merged in the spike (spec §2) must all resolve exactly. */
class CorpRepoFixtureTest {

    private static final Path REPO = fixture();
    private static final String SERVICE = "com.corp.order.OrderService";
    private static final String FORMAT_STRING = "com.corp.common.MoneyUtil#format(java.lang.String)";
    private static final String FORMAT_INT = "com.corp.common.MoneyUtil#format(int)";

    @Test
    void resolvesEveryCaseGraphifyMissed() {
        IndexResult result = index(50);

        assertExact(usage(result, SERVICE + "#a()", UsageKind.CALL, FORMAT_STRING), 17);
        assertExact(usage(result, SERVICE + "#b()", UsageKind.CALL, FORMAT_INT), 18);
        assertExact(usage(result, SERVICE + "#c()", UsageKind.CALL, FORMAT_INT), 19);
        assertExact(usage(result, SERVICE + "#d()", UsageKind.CALL, "com.corp.common.MoneyUtil#instance()"), 20);
        assertExact(usage(result, SERVICE + "#d()", UsageKind.CALL, FORMAT_STRING), 20);
        assertExact(usage(result, SERVICE + "#e(java.util.List)", UsageKind.CALL, FORMAT_STRING), 21);
        assertExact(usage(result, SERVICE + "#f()", UsageKind.CALL, "com.corp.common.DateUtil#format(java.lang.String)"), 22);
        assertExact(usage(result, SERVICE + "#g()", UsageKind.CALL, "com.corp.common.PaymentGateway#pay(int)"), 23);
    }

    @Test
    void recordsInterfaceDispatchFieldWritesAndClassLevelInstantiation() {
        IndexResult result = index(50);

        usage(result, "com.corp.order.CardPaymentGateway", UsageKind.IMPLEMENTS, "com.corp.common.PaymentGateway");
        usage(result, "com.corp.order.CardPaymentGateway#pay(int)", UsageKind.OVERRIDES,
                "com.corp.common.PaymentGateway#pay(int)");
        usage(result, "com.corp.order.CardPaymentGateway#pay(int)", UsageKind.ANNOTATION, "java.lang.Override");
        usage(result, SERVICE + "#<init>(com.corp.common.PaymentGateway)", UsageKind.FIELD_WRITE,
                SERVICE + ".gateway");
        assertExact(usage(result, SERVICE, UsageKind.INSTANTIATION, "com.corp.common.MoneyUtil#<init>()"), 9);
        assertThat(result.declarations()).anySatisfy(d -> {
            assertThat(d.symbolKey()).isEqualTo(FORMAT_INT);
            assertThat(d.modulePath()).isEqualTo("common-lib");
            assertThat(d.filePath()).isEqualTo("common-lib/src/main/java/com/corp/common/MoneyUtil.java");
            assertThat(d.line()).isEqualTo(5);
        });
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void resultsDoNotDependOnParseBatchSize() {
        IndexResult oneFilePerBatch = index(1);
        IndexResult allInOneBatch = index(50);

        assertThat(oneFilePerBatch.usages()).containsExactlyInAnyOrderElementsOf(allInOneBatch.usages());
        assertThat(oneFilePerBatch.symbols()).containsExactlyInAnyOrderElementsOf(allInOneBatch.symbols());
        assertThat(oneFilePerBatch.declarations()).containsExactlyInAnyOrderElementsOf(allInOneBatch.declarations());
    }

    private static void assertExact(Usage usage, int line) {
        assertThat(usage.confidence()).isEqualTo(Confidence.EXACT);
        assertThat(usage.line()).isEqualTo(line);
        assertThat(usage.modulePath()).isEqualTo("order-service");
    }

    private static IndexResult index(int parseBatchSize) {
        List<ModuleSource> modules = List.of(module("common-lib"), module("order-service"));
        return new JavaRepositoryIndexer().index(
                new IndexRequest(REPO, modules, new IndexerOptions(parseBatchSize, 300)));
    }

    private static ModuleSource module(String name) {
        return new ModuleSource(name, List.of(REPO.resolve(name).resolve("src/main/java")), List.of());
    }

    private static Path fixture() {
        try {
            return Path.of(CorpRepoFixtureTest.class.getResource("/fixtures/corp-repo").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Write the robustness test**

`src/test/java/com/graphify/indexer/RobustnessTest.java`:

```java
package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.UsageKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobustnessTest {

    @TempDir
    Path dir;

    @Test
    void brokenFileDoesNotStopIndexing() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Broken.java", """
                        package p;
                        class Broken {
                            void f() { int x = ; }
                            int g() { return "a".length(); }
                        }
                        """)
                .java("app", "p/Clean.java", """
                        package p;
                        class Clean { int ok() { return "b".length(); } }
                        """)
                .index();

        assertThat(usage(result, "p.Clean#ok()", UsageKind.CALL, "java.lang.String#length()").confidence())
                .isEqualTo(Confidence.EXACT);
        usage(result, "p.Broken#g()", UsageKind.CALL, "java.lang.String#length()");
    }

    @Test
    void nonUtf8FileIsIndexed() throws Exception {
        ByteArrayOutputStream iso88599 = new ByteArrayOutputStream();
        iso88599.writeBytes("package p;\n// ".getBytes(StandardCharsets.US_ASCII));
        iso88599.write(0xFE); // 'ş' in ISO-8859-9, invalid as UTF-8
        iso88599.writeBytes("\nclass Enc { int f() { return \"a\".length(); } }\n".getBytes(StandardCharsets.US_ASCII));

        IndexResult result = TempRepo.at(dir).rawJava("app", "p/Enc.java", iso88599.toByteArray()).index();

        assertThat(usage(result, "p.Enc#f()", UsageKind.CALL, "java.lang.String#length()").line()).isEqualTo(3);
    }

    @Test
    void missingSourceRootsAndEmptyModuleListsAreHarmless() {
        JavaRepositoryIndexer indexer = new JavaRepositoryIndexer();
        IndexerOptions options = new IndexerOptions(10, 100);

        IndexResult missingRoot = indexer.index(new IndexRequest(dir,
                List.of(new ModuleSource("ghost", List.of(dir.resolve("ghost/src/main/java")), List.of())), options));
        IndexResult noModules = indexer.index(new IndexRequest(dir, List.of(), options));

        assertThat(missingRoot.usages()).isEmpty();
        assertThat(missingRoot.symbols()).isEmpty();
        assertThat(noModules.usages()).isEmpty();
    }
}
```

- [ ] **Step 4: Run both tests**

Run: `./mvnw test -Dtest='CorpRepoFixtureTest,RobustnessTest'`
Expected: `Tests run: 6, Failures: 0, Errors: 0`. These tests pin behaviour that Tasks 1–7 already implement. If one fails, read the full usage list that `IndexResults` prints, fix the visitor that owns that usage kind, and rerun. Do not edit the fixture line numbers to make an assertion pass.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw test`
Expected: all tests pass, BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/fixtures src/test/java/com/graphify/indexer/CorpRepoFixtureTest.java src/test/java/com/graphify/indexer/RobustnessTest.java
git commit -m "test(indexer): add corp-repo acceptance fixture and robustness tests" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

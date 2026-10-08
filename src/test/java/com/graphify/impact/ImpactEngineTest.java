package com.graphify.impact;

import static com.graphify.indexer.model.Confidence.EXACT;
import static com.graphify.indexer.model.Confidence.NAME_ONLY;
import static com.graphify.indexer.model.SymbolKind.CLASS;
import static com.graphify.indexer.model.SymbolKind.CONSTRUCTOR;
import static com.graphify.indexer.model.SymbolKind.INTERFACE;
import static com.graphify.indexer.model.SymbolKind.METHOD;
import static com.graphify.indexer.model.UsageKind.CALL;
import static com.graphify.indexer.model.UsageKind.OVERRIDES;
import static com.graphify.indexer.model.UsageKind.TYPE_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.common.exception.NotFoundException;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImpactEngineTest {

    static final ImpactLimits LIMITS = new ImpactLimits(3, 10, 5000);
    static final long MODULE = 1;
    static final long LEGACY = 2;

    /** Mirrors the V3 seed rows of impact_relation_rule. */
    static Map<UsageKind, ImpactRule> defaultRules() {
        Map<UsageKind, ImpactRule> rules = new EnumMap<>(UsageKind.class);
        for (UsageKind kind : UsageKind.values()) {
            boolean propagates = switch (kind) {
                case CALL, INSTANTIATION, METHOD_REF, OVERRIDES, EXTENDS, IMPLEMENTS -> true;
                default -> false;
            };
            rules.put(kind, new ImpactRule(kind, propagates, true));
        }
        return rules;
    }

    InMemoryImpactGraph graph;
    ImpactEngine engine;

    @BeforeEach
    void setUp() {
        graph = new InMemoryImpactGraph();
        graph.module(MODULE, "shop-api", "shop-api", "FULL");
        graph.module(LEGACY, "shop-api", "shop-legacy", "NONE");
        engine = new ImpactEngine(graph);
    }

    ImpactResult run(List<Long> ids, ChangeType type, Integer depth, Set<Confidence> confidences, Boolean dispatch) {
        return engine.analyze(new ImpactRequest(ids, type, depth, confidences, dispatch), LIMITS, defaultRules(), Map.of());
    }

    @Test
    void behaviorChangeFollowsCallersLevelByLevel() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        long endpoint = graph.symbol("api.C#post()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(checkout, label, CALL, EXACT, MODULE);
        graph.usage(endpoint, checkout, CALL, EXACT, MODULE);

        ImpactResult three = run(List.of(format), null, null, null, null);
        ImpactResult two = run(List.of(format), ChangeType.BEHAVIOR, 2, null, null);

        assertThat(three.nodes()).extracting(ImpactNode::key, ImpactNode::level, ImpactNode::role).containsExactly(
                tuple("lib.F#format(int)", 0, NodeRole.SEED),
                tuple("api.S#label(int)", 1, NodeRole.AFFECTED),
                tuple("api.S#checkout(int)", 2, NodeRole.AFFECTED),
                tuple("api.C#post()", 3, NodeRole.AFFECTED));
        assertThat(three.edges()).extracting(ImpactEdge::level).containsExactly(1, 2, 3);
        assertThat(three.summary().usagesByLevel()).containsExactly(Map.entry(1, 1), Map.entry(2, 1), Map.entry(3, 1));
        assertThat(two.nodes()).extracting(ImpactNode::key).doesNotContain("api.C#post()");
        assertThat(three.truncated()).isFalse();
    }

    @Test
    void nonPropagatingKindsShowAtLevelOneOnly() {
        long type = graph.symbol("lib.T", CLASS, null);
        long holder = graph.symbol("api.H#hold(lib.T)", METHOD, null);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(holder, type, TYPE_REF, EXACT, MODULE);
        graph.usage(caller, holder, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(type), null, 3, null, null);

        assertThat(result.edges()).extracting(ImpactEdge::kind, ImpactEdge::level).containsExactly(tuple(TYPE_REF, 1));
        assertThat(result.nodes()).extracting(ImpactNode::key).doesNotContain("api.X#run()");
    }

    @Test
    void aSymbolFirstSeenThroughATypeRefStillPropagatesWhenAlsoCalled() {
        long type = graph.symbol("lib.T", CLASS, null);
        long ctor = graph.symbol("lib.T#<init>()", CONSTRUCTOR, type);
        long user = graph.symbol("api.U#make()", METHOD, null);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(user, type, TYPE_REF, EXACT, MODULE);
        graph.usage(user, ctor, UsageKind.INSTANTIATION, EXACT, MODULE);
        graph.usage(caller, user, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(type), null, 2, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key).contains("api.X#run()");
    }

    @Test
    void typeTargetSeedsItsMembers() {
        long type = graph.symbol("lib.T", CLASS, null);
        long member = graph.symbol("lib.T#go()", METHOD, type);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(caller, member, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(type), null, 1, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::role).contains(
                tuple("lib.T#go()", NodeRole.SEED), tuple("api.X#run()", NodeRole.AFFECTED));
    }

    @Test
    void dispatchReportsCallersOfTheOverriddenMethodButNotSiblings() {
        long iface = graph.symbol("lib.G", INTERFACE, null);
        long ifaceCharge = graph.symbol("lib.G#charge(int)", METHOD, iface);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long cash = graph.symbol("api.Cash#charge(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        graph.usage(card, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(cash, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(checkout, ifaceCharge, CALL, EXACT, MODULE);

        ImpactResult withDispatch = run(List.of(card), null, 1, null, null);
        ImpactResult without = run(List.of(card), null, 1, null, false);

        assertThat(withDispatch.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::viaDispatch)
                .containsExactly(tuple(checkout, true));
        assertThat(withDispatch.nodes()).extracting(ImpactNode::key, ImpactNode::role).contains(
                tuple("lib.G#charge(int)", NodeRole.DISPATCH), tuple("api.S#checkout(int)", NodeRole.AFFECTED));
        assertThat(withDispatch.nodes()).extracting(ImpactNode::key).doesNotContain("api.Cash#charge(int)");
        assertThat(without.edges()).isEmpty();
    }

    @Test
    void binaryOverriddenMethodsAreNotDispatchTargets() {
        long object = graph.symbol("java.lang.Object", CLASS, null, SymbolOrigin.BINARY);
        long objectToString = graph.symbol("java.lang.Object#toString()", METHOD, object, SymbolOrigin.BINARY);
        long seed = graph.symbol("api.Money#toString()", METHOD, null);
        long unrelated = graph.symbol("other.Log#write(java.lang.Object)", METHOD, null);
        graph.usage(seed, objectToString, OVERRIDES, EXACT, MODULE);
        graph.usage(unrelated, objectToString, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(seed), null, 2, null, null);

        assertThat(result.edges()).isEmpty();
        assertThat(result.nodes()).extracting(ImpactNode::role).doesNotContain(NodeRole.DISPATCH);
        assertThat(result.nodes()).extracting(ImpactNode::key).containsExactly("api.Money#toString()");
    }

    @Test
    void signatureChangeStopsAtLevelOneWithoutDispatchButKeepsOverrides() {
        long ifaceCharge = graph.symbol("lib.G#charge(int)", METHOD, null);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        long endpoint = graph.symbol("api.C#post()", METHOD, null);
        graph.usage(card, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(checkout, ifaceCharge, CALL, EXACT, MODULE);
        graph.usage(endpoint, checkout, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(ifaceCharge), ChangeType.SIGNATURE, 5, null, true);

        assertThat(result.edges()).extracting(ImpactEdge::kind, ImpactEdge::level)
                .containsExactlyInAnyOrder(tuple(OVERRIDES, 1), tuple(CALL, 1));
    }

    @Test
    void anInterfaceMethodAlsoSeedsItsImplementations() {
        long iface = graph.symbol("lib.G", INTERFACE, null);
        long ifaceCharge = graph.symbol("lib.G#charge(int)", METHOD, iface);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long cardTwin = graph.symbol("api.Card#charge/1", METHOD, null);
        long terminal = graph.symbol("api.Terminal#pay()", METHOD, null);
        long legacy = graph.symbol("old.T#pay()", METHOD, null);
        graph.usage(card, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(terminal, card, CALL, EXACT, MODULE);
        graph.usage(legacy, cardTwin, CALL, NAME_ONLY, LEGACY);

        ImpactResult result = run(List.of(ifaceCharge), ChangeType.BEHAVIOR, 1, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::role).contains(
                tuple("api.Card#charge(int)", NodeRole.SEED), tuple("api.Card#charge/1", NodeRole.TWIN),
                tuple("api.Terminal#pay()", NodeRole.AFFECTED), tuple("old.T#pay()", NodeRole.AFFECTED));
        assertThat(result.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::level)
                .contains(tuple(terminal, 1), tuple(legacy, 1));
    }

    @Test
    void aConcreteMethodSeedsItsOverridersOnlyForASignatureChange() {
        long base = graph.symbol("lib.Base", CLASS, null);
        long baseRun = graph.symbol("lib.Base#run()", METHOD, base);
        long sub = graph.symbol("api.Sub#run()", METHOD, null);
        long caller = graph.symbol("api.X#go()", METHOD, null);
        graph.usage(sub, baseRun, OVERRIDES, EXACT, MODULE);
        graph.usage(caller, sub, CALL, EXACT, MODULE);

        ImpactResult behavior = run(List.of(baseRun), ChangeType.BEHAVIOR, 1, null, null);
        ImpactResult signature = run(List.of(baseRun), ChangeType.SIGNATURE, null, null, null);

        assertThat(behavior.nodes()).extracting(ImpactNode::key, ImpactNode::role).containsExactly(
                tuple("lib.Base#run()", NodeRole.SEED), tuple("api.Sub#run()", NodeRole.AFFECTED));
        assertThat(signature.nodes()).extracting(ImpactNode::key, ImpactNode::role).containsExactly(
                tuple("lib.Base#run()", NodeRole.SEED), tuple("api.Sub#run()", NodeRole.SEED),
                tuple("api.X#go()", NodeRole.AFFECTED));
        assertThat(signature.edges()).extracting(ImpactEdge::kind, ImpactEdge::level)
                .containsExactlyInAnyOrder(tuple(OVERRIDES, 1), tuple(CALL, 1));
    }

    @Test
    void nameOnlyTwinOfAMethodIsSeededAndFilterable() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long twin = graph.symbol("lib.F#format/1", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        graph.usage(legacy, twin, CALL, NAME_ONLY, LEGACY);

        ImpactResult all = run(List.of(format), null, 1, null, null);
        ImpactResult exactOnly = run(List.of(format), null, 1, Set.of(EXACT), null);

        assertThat(all.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::confidence)
                .containsExactly(tuple(legacy, NAME_ONLY));
        assertThat(all.summary().partialClasspathRepositories()).extracting(RepositoryRef::projectKey, RepositoryRef::slug)
                .containsExactly(tuple("TEST", "shop-api"));
        assertThat(exactOnly.edges()).isEmpty();
    }

    @Test
    void nameOnlyTwinsOfAffectedMethodsAreFollowedToo() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long labelTwin = graph.symbol("api.S#label/1", METHOD, null);
        long legacy = graph.symbol("old.X#y()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(legacy, labelTwin, CALL, NAME_ONLY, LEGACY);

        ImpactResult result = run(List.of(format), null, 2, null, null);

        assertThat(result.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::toSymbolId, ImpactEdge::level,
                ImpactEdge::confidence).containsExactly(tuple(label, format, 1, EXACT),
                tuple(legacy, labelTwin, 2, NAME_ONLY));
        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::level, ImpactNode::role).containsExactly(
                tuple("lib.F#format(int)", 0, NodeRole.SEED),
                tuple("api.S#label(int)", 1, NodeRole.AFFECTED),
                tuple("old.X#y()", 2, NodeRole.AFFECTED),
                tuple("api.S#label/1", 1, NodeRole.TWIN));
        assertThat(result.summary().methods()).isEqualTo(2);
    }

    @Test
    void nameOnlyTwinOfADispatchTargetIsFollowedViaDispatch() {
        long ifaceCharge = graph.symbol("lib.G#charge(int)", METHOD, null);
        long ifaceTwin = graph.symbol("lib.G#charge/1", METHOD, null, SymbolOrigin.BINARY);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long legacy = graph.symbol("old.R#pay()", METHOD, null);
        graph.usage(card, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(legacy, ifaceTwin, CALL, NAME_ONLY, LEGACY);

        ImpactResult result = run(List.of(card), null, 1, null, null);

        assertThat(result.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::toSymbolId, ImpactEdge::viaDispatch)
                .containsExactly(tuple(legacy, ifaceTwin, true));
        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::role, ImpactNode::nameOnly)
                .contains(tuple("lib.G#charge/1", NodeRole.TWIN, true));
    }

    @Test
    void noDispatchNodeIsReportedBeyondTheLastLevel() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long ifaceLabel = graph.symbol("api.L#label(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(label, ifaceLabel, OVERRIDES, EXACT, MODULE);
        graph.usage(caller, ifaceLabel, CALL, EXACT, MODULE);

        ImpactResult one = run(List.of(format), null, 1, null, null);
        ImpactResult two = run(List.of(format), null, 2, null, null);

        assertThat(one.nodes()).extracting(ImpactNode::role).doesNotContain(NodeRole.DISPATCH);
        assertThat(two.nodes()).extracting(ImpactNode::key, ImpactNode::level, ImpactNode::role).contains(
                tuple("api.L#label(int)", 1, NodeRole.DISPATCH), tuple("api.X#run()", 2, NodeRole.AFFECTED));
    }

    @Test
    void cyclesTerminateAndKeepTheFirstLevel() {
        long a = graph.symbol("p.A#a()", METHOD, null);
        long b = graph.symbol("p.B#b()", METHOD, null);
        graph.usage(b, a, CALL, EXACT, MODULE);
        graph.usage(a, b, CALL, EXACT, MODULE);
        graph.usage(a, a, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(a), null, 10, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::level)
                .containsExactly(tuple("p.A#a()", 0), tuple("p.B#b()", 1));
    }

    @Test
    void resultLimitTruncatesInsteadOfGrowingWithoutBound() {
        long target = graph.symbol("lib.F#hot()", METHOD, null);
        for (int i = 0; i < 10; i++) {
            graph.usage(graph.symbol("api.C" + i + "#m()", METHOD, null), target, CALL, EXACT, MODULE);
        }

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(target), null, 2, null, null),
                new ImpactLimits(3, 10, 3), defaultRules(), Map.of());

        assertThat(result.truncated()).isTrue();
        assertThat(result.nodes()).filteredOn(n -> n.role() == NodeRole.AFFECTED).hasSize(3);
        assertThat(result.edges()).hasSize(3);
    }

    @Test
    void usageDetailsAreReadOnceForTheKeptEdgesOnly() {
        List<Collection<Long>> detailCalls = new ArrayList<>();
        InMemoryImpactGraph counting = new InMemoryImpactGraph() {
            @Override
            public Map<Long, UsageDetail> usageDetails(Collection<Long> usageIds) {
                detailCalls.add(List.copyOf(usageIds));
                return super.usageDetails(usageIds);
            }
        };
        counting.module(MODULE, "shop-api", "shop-api", "FULL");
        long target = counting.symbol("lib.F#hot()", METHOD, null);
        for (int i = 0; i < 10; i++) {
            counting.usage(counting.symbol("api.C" + i + "#m()", METHOD, null), target, CALL, EXACT, MODULE);
        }

        ImpactResult result = new ImpactEngine(counting).analyze(new ImpactRequest(List.of(target), null, 2, null, null),
                new ImpactLimits(3, 10, 3), defaultRules(), Map.of());

        assertThat(detailCalls).singleElement().satisfies(ids -> assertThat(ids).hasSize(3));
        assertThat(result.edges()).allSatisfy(edge -> {
            assertThat(edge.filePath()).isEqualTo("F.java");
            assertThat(edge.snippet()).isEqualTo("snippet");
        });
    }

    @Test
    void rulesComeFromTheCaller() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(checkout, label, CALL, EXACT, MODULE);
        Map<UsageKind, ImpactRule> noCallPropagation = defaultRules();
        noCallPropagation.put(CALL, new ImpactRule(CALL, false, true));

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(format), null, 3, null, null), LIMITS,
                noCallPropagation, Map.of());

        assertThat(result.nodes()).extracting(ImpactNode::key).containsExactly("lib.F#format(int)", "api.S#label(int)");
    }

    @Test
    void summaryCountsWhatWasReached() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(legacy, format, CALL, Confidence.RECOVERED, LEGACY);

        ImpactSummary summary = run(List.of(format), null, 1, null, null).summary();

        assertThat(summary.repositories()).isEqualTo(1);
        assertThat(summary.modules()).isEqualTo(2);
        assertThat(summary.classes()).isEqualTo(2);
        assertThat(summary.methods()).isEqualTo(2);
        assertThat(summary.usages()).isEqualTo(2);
        assertThat(summary.usagesByConfidence()).containsEntry(EXACT, 1).containsEntry(Confidence.RECOVERED, 1);
    }

    @Test
    void aNodeIsNoMoreCertainThanThePathThatReachedIt() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long twin = graph.symbol("lib.F#format/1", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        long report = graph.symbol("old.R#report()", METHOD, null);
        graph.usage(legacy, twin, CALL, NAME_ONLY, LEGACY);
        graph.usage(report, legacy, CALL, EXACT, LEGACY);

        ImpactResult result = run(List.of(format), null, 2, null, null);

        assertThat(result.nodes())
                .extracting(ImpactNode::key, ImpactNode::role, ImpactNode::confidence, ImpactNode::nameOnly)
                .containsExactly(
                        tuple("lib.F#format(int)", NodeRole.SEED, EXACT, false),
                        tuple("lib.F#format/1", NodeRole.TWIN, EXACT, true),
                        tuple("old.R#print()", NodeRole.AFFECTED, NAME_ONLY, false),
                        tuple("old.R#report()", NodeRole.AFFECTED, NAME_ONLY, false));
        assertThat(result.summary().nodesByConfidence())
                .containsExactly(Map.entry(EXACT, 0), Map.entry(Confidence.RECOVERED, 0), Map.entry(NAME_ONLY, 2));
        assertThat(result.summary().usagesByConfidence())
                .containsExactly(Map.entry(EXACT, 1), Map.entry(Confidence.RECOVERED, 0), Map.entry(NAME_ONLY, 1));
    }

    @Test
    void aKnownNodeKeepsTheStrongerConfidence() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        graph.usage(label, format, CALL, Confidence.RECOVERED, MODULE);
        graph.usage(label, format, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(format), null, 1, null, null);

        assertThat(result.nodes()).filteredOn(n -> n.role() == NodeRole.AFFECTED)
                .extracting(ImpactNode::confidence).containsExactly(EXACT);
    }

    @Test
    void aDispatchTargetTakesTheConfidenceOfItsOverrider() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long ifaceLabel = graph.symbol("api.L#label(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(label, format, CALL, Confidence.RECOVERED, MODULE);
        graph.usage(label, ifaceLabel, OVERRIDES, EXACT, MODULE);
        graph.usage(caller, ifaceLabel, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(format), null, 2, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::role, ImpactNode::confidence).contains(
                tuple("api.L#label(int)", NodeRole.DISPATCH, Confidence.RECOVERED),
                tuple("api.X#run()", NodeRole.AFFECTED, Confidence.RECOVERED));
    }

    @Test
    void invalidRequestsAreRejected() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);

        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(), null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> run(null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(format), null, 0, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(format), null, 11, null, null))
                .withMessageContaining("10");
        Set<Confidence> withNull = new HashSet<>();
        withNull.add(EXACT);
        withNull.add(null);
        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(format), null, null, withNull, null))
                .withMessageContaining("confidences");
        assertThatThrownBy(() -> run(List.of(99L), null, null, null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void seedOrderFollowsTheRequestNotTheGraph() {
        InMemoryImpactGraph reversed = new InMemoryImpactGraph() {
            @Override
            public Map<Long, ImpactSymbol> symbols(Collection<Long> ids) {
                List<Map.Entry<Long, ImpactSymbol>> entries = new ArrayList<>(super.symbols(ids).entrySet());
                Map<Long, ImpactSymbol> out = new LinkedHashMap<>();
                for (int i = entries.size() - 1; i >= 0; i--) {
                    out.put(entries.get(i).getKey(), entries.get(i).getValue());
                }
                return out;
            }
        };
        long a = reversed.symbol("p.A#a()", METHOD, null);
        long b = reversed.symbol("p.B#b()", METHOD, null);

        ImpactResult result = new ImpactEngine(reversed).analyze(
                new ImpactRequest(List.of(b, a), null, 1, null, null), LIMITS, defaultRules(), Map.of());

        assertThat(result.nodes()).extracting(ImpactNode::key).startsWith("p.B#b()", "p.A#a()");
    }

    @Test
    void typeTargetAlsoSeedsNameOnlyTwinsOfItsMembers() {
        long type = graph.symbol("lib.T", CLASS, null);
        graph.symbol("lib.T#go(int)", METHOD, type);
        long twin = graph.symbol("lib.T#go/1", METHOD, null);
        long legacy = graph.symbol("old.R#x()", METHOD, null);
        graph.usage(legacy, twin, CALL, NAME_ONLY, LEGACY);

        ImpactResult result = run(List.of(type), null, 1, null, null);

        assertThat(result.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::toSymbolId, ImpactEdge::confidence)
                .containsExactly(tuple(legacy, twin, NAME_ONLY));
    }

    static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    static final Map<String, String> LABELS = Map.of(POST_MAPPING, "HTTP", REQUEST_MAPPING, "HTTP",
            SCHEDULED, "Zamanlanmış görev");

    @Test
    void reachedEndpointsAndJobsAreReportedWithTheirHttpPath() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long controller = graph.symbol("api.OrderController", CLASS, null);
        long post = graph.symbol("api.OrderController#checkout()", METHOD, controller);
        long job = graph.symbol("api.NightlyJob#run()", METHOD, null);
        graph.usage(post, format, CALL, EXACT, MODULE);
        graph.usage(job, format, CALL, EXACT, MODULE);
        graph.annotation(controller, REQUEST_MAPPING, "@RequestMapping(\"/orders\")", MODULE);
        graph.annotation(post, POST_MAPPING, "@PostMapping(value = \"/checkout\")", MODULE);
        graph.annotation(job, SCHEDULED, "@Scheduled(cron = \"0 0 1 * * *\")", MODULE);

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(format), null, 1, null, null), LIMITS,
                defaultRules(), LABELS);

        assertThat(result.entryPoints())
                .extracting(EntryPoint::key, EntryPoint::label, EntryPoint::httpMethod, EntryPoint::httpPath,
                        e -> e.repository().slug())
                .containsExactly(
                        tuple("api.NightlyJob#run()", "Zamanlanmış görev", null, null, "shop-api"),
                        tuple("api.OrderController#checkout()", "HTTP", "POST", "/orders/checkout", "shop-api"));
    }

    @Test
    void anEndpointDeclaredOnAnInterfaceIsReportedOnTheImplementation() {
        String getMapping = "org.springframework.web.bind.annotation.GetMapping";
        Map<String, String> labels = Map.of(getMapping, "HTTP", REQUEST_MAPPING, "HTTP");
        graph.module(3, "orders-api", "orders-contract", "FULL");
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long api = graph.symbol("api.OrdersApi", INTERFACE, null);
        long apiGet = graph.symbol("api.OrdersApi#get(java.lang.String)", METHOD, api);
        long controller = graph.symbol("api.OrdersApiController", CLASS, null);
        long get = graph.symbol("api.OrdersApiController#get(java.lang.String)", METHOD, controller);
        graph.declaration(get, MODULE);
        graph.usage(get, format, CALL, EXACT, MODULE);
        graph.usage(get, apiGet, OVERRIDES, EXACT, MODULE);
        graph.annotation(api, REQUEST_MAPPING, "@RequestMapping(\"/v2/orders\")", 3);
        graph.annotation(apiGet, getMapping, "@GetMapping(\"/{id}\")", 3);

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(format), null, 1, null, null), LIMITS,
                defaultRules(), labels);

        assertThat(result.entryPoints())
                .extracting(EntryPoint::symbolId, EntryPoint::httpMethod, EntryPoint::httpPath, EntryPoint::http,
                        e -> e.repository().slug(), EntryPoint::modulePath)
                .containsExactly(tuple(get, "GET", "/v2/orders/{id}", true, "shop-api", "shop-api"));
    }

    @Test
    void aChangedEndpointIsItsOwnEntryPointAndDisabledLabelsAreIgnored() {
        long post = graph.symbol("api.C#post()", METHOD, null);
        graph.annotation(post, POST_MAPPING, "@PostMapping(\"/p\")", MODULE);

        ImpactResult enabled = engine.analyze(new ImpactRequest(List.of(post), null, 1, null, null), LIMITS,
                defaultRules(), LABELS);
        ImpactResult disabled = engine.analyze(new ImpactRequest(List.of(post), null, 1, null, null), LIMITS,
                defaultRules(), Map.of(SCHEDULED, "Zamanlanmış görev"));

        assertThat(enabled.entryPoints()).extracting(EntryPoint::httpPath).containsExactly("/p");
        assertThat(disabled.entryPoints()).isEmpty();
    }

    @Test
    void unresolvablePathsAreReportedAsNullNotGuessed() {
        String getMapping = "org.springframework.web.bind.annotation.GetMapping";
        Map<String, String> labels = Map.of(getMapping, "HTTP");
        long constant = graph.symbol("api.A#constant()", METHOD, null);
        graph.annotation(constant, getMapping, "@GetMapping(Paths.ORDERS)", MODULE);
        long unresolvedClass = graph.symbol("api.B", CLASS, null);
        long underConstant = graph.symbol("api.B#x()", METHOD, unresolvedClass);
        graph.annotation(unresolvedClass, REQUEST_MAPPING, "@RequestMapping(Paths.BASE)", MODULE);
        graph.annotation(underConstant, getMapping, "@GetMapping(\"/x\")", MODULE);
        long literalClass = graph.symbol("api.D", CLASS, null);
        long underLiteral = graph.symbol("api.D#x()", METHOD, literalClass);
        graph.annotation(literalClass, REQUEST_MAPPING, "@RequestMapping(\"/d\")", MODULE);
        graph.annotation(underLiteral, getMapping, "@GetMapping(\"/x\")", MODULE);

        ImpactResult result = engine.analyze(
                new ImpactRequest(List.of(constant, underConstant, underLiteral), null, 1, null, null), LIMITS,
                defaultRules(), labels);

        assertThat(result.entryPoints())
                .extracting(EntryPoint::key, EntryPoint::httpMethod, EntryPoint::httpPath)
                .containsExactly(tuple("api.A#constant()", "GET", null), tuple("api.B#x()", "GET", null),
                        tuple("api.D#x()", "GET", "/d/x"));
    }

    @Test
    void everyEdgeEndpointIsANodeAndTwinsAreMarked() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long formatTwin = graph.symbol("lib.F#format/1", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long labelTwin = graph.symbol("api.S#label/1", METHOD, null);
        long iface = graph.symbol("lib.G#charge(int)", METHOD, null);
        long ifaceTwin = graph.symbol("lib.G#charge/1", METHOD, null);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        long legacyLabel = graph.symbol("old.R#label()", METHOD, null);
        long legacyCharge = graph.symbol("old.R#charge()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(legacy, formatTwin, CALL, NAME_ONLY, LEGACY);
        graph.usage(legacyLabel, labelTwin, CALL, NAME_ONLY, LEGACY);
        graph.usage(card, iface, OVERRIDES, EXACT, MODULE);
        graph.usage(legacyCharge, ifaceTwin, CALL, NAME_ONLY, LEGACY);

        ImpactResult byFormat = run(List.of(format), null, 2, null, null);
        ImpactResult byCard = run(List.of(card), null, 1, null, null);

        for (ImpactResult result : List.of(byFormat, byCard)) {
            Set<Long> nodeIds = result.nodes().stream().map(ImpactNode::symbolId).collect(java.util.stream.Collectors.toSet());
            assertThat(result.edges()).allSatisfy(e -> {
                assertThat(nodeIds).contains(e.fromSymbolId());
                assertThat(nodeIds).contains(e.toSymbolId());
            });
        }
        assertThat(byFormat.nodes()).extracting(ImpactNode::key, ImpactNode::role, ImpactNode::level, ImpactNode::nameOnly)
                .contains(
                        tuple("lib.F#format/1", NodeRole.TWIN, 0, true),
                        tuple("api.S#label/1", NodeRole.TWIN, 1, true));
        assertThat(byCard.nodes()).extracting(ImpactNode::key, ImpactNode::role)
                .contains(tuple("lib.G#charge/1", NodeRole.TWIN));
        assertThat(byFormat.summary().methods()).isEqualTo(3);
    }

    @Test
    void aRequestedNameOnlySymbolStaysASeed() {
        long twin = graph.symbol("lib.F#format/1", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        graph.usage(legacy, twin, CALL, NAME_ONLY, LEGACY);

        assertThat(run(List.of(twin), null, 1, null, null).nodes())
                .extracting(ImpactNode::key, ImpactNode::role)
                .contains(tuple("lib.F#format/1", NodeRole.SEED));
    }
}

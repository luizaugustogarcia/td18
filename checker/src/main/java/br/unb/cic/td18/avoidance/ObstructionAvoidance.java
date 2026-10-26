package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.permutation.PermutationGroups;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.search.CheckedSearchResult;
import br.unb.cic.td18.search.CommonOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Reconstructs preimages of terminal misses using the avoidance-preimage
 * method implemented for TD(16) in {@code ../td16}. Each distinct
 * intermediate predecessor is first checked for a CPU prefix closure; only
 * a prefix miss is propagated to the next inverse wave. Root preimages
 * are independently checked on the full 19-symbol domain.
 */
@Slf4j
public final class ObstructionAvoidance {
    private static final List<ReductionTypeEnumerator.Obligation> TABLE_OBLIGATIONS =
            ReductionTypeEnumerator.enumerate();

    private ObstructionAvoidance() {
    }

    public record AvoidanceResult(
            long terminalResiduals,
            long reconstructionApplications,
            long intermediatePrefixObligations,
            long propagatedPrefixMisses,
            long rootSearchObligations,
            long trivialClosures,
            long certificates,
            long unresolved,
            CyclicTargetPair firstUnresolved
    ) {
        public AvoidanceResult {
            if (terminalResiduals < 0 || reconstructionApplications < 0
                    || intermediatePrefixObligations < 0
                    || propagatedPrefixMisses < 0
                    || rootSearchObligations < 0 || trivialClosures < 0
                    || certificates < 0 || unresolved < 0
                    || propagatedPrefixMisses > intermediatePrefixObligations
                    || trivialClosures + certificates + propagatedPrefixMisses
                    + unresolved != intermediatePrefixObligations
                    + rootSearchObligations
                    || (unresolved == 0) != (firstUnresolved == null)) {
                throw new IllegalArgumentException(
                        "Inconsistent obstruction-avoidance result");
            }
        }

        public boolean complete() {
            return unresolved == 0;
        }

        public long screenedObligations() {
            return Math.addExact(
                    intermediatePrefixObligations, rootSearchObligations);
        }
    }

    public record ReconstructionBatch(long applications,
                               List<CyclicTargetPair> parents) {
        public ReconstructionBatch {
            parents = List.copyOf(parents);
        }
    }

    private record StateKey(int maximumMoves,
                            ExtendedToricClassKey classKey) {
    }

    public record SearchState(int maximumMoves, CyclicTargetPair pair) {
    }

    record RestoredState(CyclicTargetPair pair, int[] fixedPoints) {
        RestoredState {
            fixedPoints = fixedPoints.clone();
        }
    }

    private record AnnotatedType(int[] lengths, boolean[] oriented) {
        private AnnotatedType {
            lengths = lengths.clone();
            oriented = oriented.clone();
        }
    }

    @FunctionalInterface
    public interface PairSearch {
        CheckedSearchResult search(
                CyclicTargetPair pair, float minimumRate, int maximumMoves,
                boolean fullDomain);
    }

    public static AvoidanceResult resolve(
            final Collection<ResidualObligation> residuals,
            final PairSearch search,
            final Consumer<AvoidanceSearchMiss> missSink
    ) {
        Objects.requireNonNull(residuals);
        Objects.requireNonNull(search);
        Objects.requireNonNull(missSink);

        final var hardByBudget = new java.util.TreeMap<Integer,
                ConcurrentHashMap<StateKey, SearchState>>();
        for (final var residual : residuals) {
            validateTerminalResidual(residual);
            addState(hardByBudget.computeIfAbsent(
                            residual.maximumMoves(), ignored ->
                                    new ConcurrentHashMap<>()),
                    residual.maximumMoves(), residual.pair());
        }

        long reconstructionApplications = 0;
        final var roots = new ConcurrentHashMap<StateKey, SearchState>();
        final var intermediateTrivial = new LongAdder();
        final var intermediatePrefixObligations = new LongAdder();
        final var propagatedPrefixMisses = new LongAdder();
        for (var maximumMoves = ReductionTypeEnumerator.MAX_BUDGET - 2;
             maximumMoves < ReductionTypeEnumerator.MAX_BUDGET;
             maximumMoves++) {
            final var current = hardByBudget.computeIfAbsent(
                    maximumMoves, ignored -> new ConcurrentHashMap<>());
            if (current.isEmpty()) {
                continue;
            }
            final var parents = new ConcurrentHashMap<StateKey, SearchState>();
            final var applications = new LongAdder();
            runReconstructionTasks(List.copyOf(current.values()),
                    parents, applications);
            reconstructionApplications = Math.addExact(
                    reconstructionApplications, applications.sum());
            log.info("Avoidance reconstruction wave {}: hardInputs={}, "
                            + "reconstructionApplications={}, "
                            + "predecessorClasses={}",
                    maximumMoves, current.size(), applications.sum(),
                    parents.size());

            final int parentMaximumMoves = maximumMoves + 1;
            final var alreadyHard = hardByBudget.computeIfAbsent(
                    parentMaximumMoves, ignored -> new ConcurrentHashMap<>());
            parents.keySet().removeAll(alreadyHard.keySet());
            if (parentMaximumMoves == ReductionTypeEnumerator.MAX_BUDGET) {
                parents.values().forEach(state -> addState(
                        roots, state.maximumMoves(), state.pair()));
                continue;
            }

            final var orderedCandidates = orderedStates(parents.values());
            final var propagated = new ConcurrentHashMap<StateKey, SearchState>();
            final var result = searchStates(orderedCandidates, search,
                    null, propagated, false);
            intermediatePrefixObligations.add(orderedCandidates.size());
            intermediateTrivial.add(result.trivialClosures());
            propagatedPrefixMisses.add(result.residuals());
            propagated.values().forEach(state -> addState(
                    alreadyHard, state.maximumMoves(), state.pair()));
            log.info("Avoidance intermediate prefix screen {}: candidates={}, "
                            + "triviallySettled={}, propagatedPrefixMisses={}",
                    parentMaximumMoves, orderedCandidates.size(),
                    result.trivialClosures(), result.residuals());
        }

        hardByBudget.getOrDefault(ReductionTypeEnumerator.MAX_BUDGET,
                        new ConcurrentHashMap<>()).values()
                .forEach(state -> addState(
                        roots, state.maximumMoves(), state.pair()));
        final var orderedRoots = orderedStates(roots.values());
        roots.clear();
        final var unresolved = new ConcurrentHashMap<StateKey, SearchState>();
        final var rootResult = searchStates(
                orderedRoots, search, missSink, unresolved, true);
        log.info("Avoidance root search: rootClasses={}, triviallySettled={}, "
                        + "certificates={}, unresolved={}",
                orderedRoots.size(), rootResult.trivialClosures(),
                rootResult.certificates(), rootResult.residuals());
        final var orderedUnresolved = unresolved.values().stream()
                .map(state -> state.pair().canonicalRepresentative())
                .sorted(ObstructionAvoidance::compareCertificateKeys)
                .toList();
        return new AvoidanceResult(residuals.size(),
                reconstructionApplications, intermediatePrefixObligations.sum(),
                propagatedPrefixMisses.sum(), orderedRoots.size(),
                intermediateTrivial.sum() + rootResult.trivialClosures(),
                rootResult.certificates(),
                orderedUnresolved.size(),
                orderedUnresolved.isEmpty()
                ? null : orderedUnresolved.getFirst());
    }

    private static List<SearchState> orderedStates(
            final Collection<SearchState> states
    ) {
        return states.stream()
                .map(state -> new SearchState(state.maximumMoves(),
                        state.pair().canonicalRepresentative()))
                .sorted(Comparator.comparing(SearchState::pair,
                        ObstructionAvoidance::compareCertificateKeys))
                .toList();
    }

    private static void requireRoot(final SearchState state) {
        if (state.maximumMoves() != ReductionTypeEnumerator.MAX_BUDGET
                || state.pair().getBeta().size()
                != ReductionTypeEnumerator.ROOT_SYMBOLS) {
            throw new IllegalStateException(
                    "Avoidance search requires an S_18 root preimage");
        }
    }

    public static ReconstructionBatch reconstructParents(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        final var parents = new ConcurrentHashMap<StateKey, SearchState>();
        final long applications = reconstructParentsInto(
                new SearchState(maximumMoves, pair), parents);
        final var ordered = parents.values().stream()
                .map(state -> state.pair().canonicalRepresentative())
                .sorted(ObstructionAvoidance::compareCertificateKeys)
                .toList();
        return new ReconstructionBatch(applications, ordered);
    }

    public static void validateTerminalResidual(
            final ResidualObligation residual
    ) {
        final int movesUsed = ReductionTypeEnumerator.MAX_BUDGET
                - residual.maximumMoves();
        if (movesUsed < 0 || movesUsed > 2) {
            throw new IllegalArgumentException(
                    "Residual obligation lies outside the two-reduction computation");
        }
        final var type = annotatedType(residual.pair());
        if (residual.row() > TABLE_OBLIGATIONS.size()) {
            throw new IllegalArgumentException(
                    "Residual obligation has no Table 2 row");
        }
        final var tableType = TABLE_OBLIGATIONS.get(residual.row() - 1);
        final var recordedType = new AnnotatedType(
                tableType.lengths(), tableType.oriented());
        if (tableType.budget() != residual.maximumMoves()
                || !formatType(recordedType).equals(formatType(type))
                || !ReductionTypeEnumerator.isReachableOpenState(
                type.lengths(), type.oriented(), movesUsed)) {
            throw new IllegalArgumentException(
                    "Residual obligation does not match its reachable Table 2 type");
        }
    }

    private static void runReconstructionTasks(
            final List<SearchState> states,
            final ConcurrentHashMap<StateKey, SearchState> parents,
            final LongAdder applications
    ) {
        final var cursor = new AtomicInteger();
        final var stop = new AtomicBoolean();
        final int workers = Math.min(states.size(),
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        final var futures = new ArrayList<Future<?>>(workers);
        final ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("td18-avoidance-reconstruct-", 0)
                        .factory());
        var completed = false;
        try {
            for (var worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    try {
                        while (!stop.get()
                                && !Thread.currentThread().isInterrupted()) {
                            final int index = cursor.getAndIncrement();
                            if (index >= states.size()) {
                                return;
                            }
                            applications.add(reconstructParentsInto(
                                    states.get(index), parents));
                        }
                    } catch (final RuntimeException | Error throwable) {
                        stop.set(true);
                        throw throwable;
                    }
                }));
            }
            awaitAll(futures, "avoidance-preimage reconstruction");
            completed = true;
        } finally {
            if (!completed) {
                stop.set(true);
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
        }
    }

    private static long reconstructParentsInto(
            final SearchState successor,
            final ConcurrentHashMap<StateKey, SearchState> parents
    ) {
        return reconstructParentsInto(successor, state -> addState(
                parents, state.maximumMoves(), state.pair()));
    }

    public static long reconstructParentsInto(
            final SearchState successor,
            final Consumer<SearchState> parents
    ) {
        final int parentMovesUsed = ReductionTypeEnumerator.MAX_BUDGET
                - successor.maximumMoves() - 1;
        if (parentMovesUsed < 0) {
            return 0;
        }
        final int childSymbols = successor.pair().getBeta().size();
        final var applications = new LongAdder();
        for (var fixedPointCount = 1; fixedPointCount <= 3; fixedPointCount++) {
            final int parentSymbols = childSymbols + fixedPointCount;
            final int earlierContraction = ReductionTypeEnumerator.ROOT_SYMBOLS
                    - parentSymbols;
            if (parentSymbols > ReductionTypeEnumerator.ROOT_SYMBOLS
                    || earlierContraction < parentMovesUsed
                    || earlierContraction > 3 * parentMovesUsed
                    || ReductionTypeEnumerator.knownDiameter(parentSymbols)
                    <= successor.maximumMoves() + 1) {
                continue;
            }
            final int restoredCount = fixedPointCount;
            forEachRestoration(successor.pair(), restoredCount, restored -> {
                switch (restoredCount) {
                    case 1 -> {
                        reconstructOddSplit(successor, restored, parents, applications);
                        reconstructEvenPair(successor, restored, parents, applications);
                    }
                    case 2 -> reconstructOddShrink(
                            successor, restored, parents, applications);
                    case 3 -> reconstructOrientedThree(
                            successor, restored, parents, applications);
                    default -> throw new IllegalStateException(
                            "Unsupported fixed-point restoration count");
                }
            });
        }
        return applications.sum();
    }

    private static void reconstructOddSplit(
            final SearchState successor,
            final RestoredState restored,
            final Consumer<SearchState> parents,
            final LongAdder applications
    ) {
        final int fixedPoint = restored.fixedPoints()[0];
        final var cycles = successor.pair().getOmega().stream()
                .filter(cycle -> cycle.size() > 1 && cycle.size() % 2 == 1)
                .toList();
        for (var firstCycle = 0; firstCycle < cycles.size(); firstCycle++) {
            for (var secondCycle = firstCycle + 1;
                 secondCycle < cycles.size(); secondCycle++) {
                for (final int a : cycles.get(firstCycle).getSymbols()) {
                    for (final int b : cycles.get(secondCycle).getSymbols()) {
                        applications.increment();
                        considerParent(successor, restored,
                                orderedCycle(restored.pair().getBeta(),
                                        a, b, fixedPoint),
                                ReductionTypeEnumerator.ReductionKind.ORIENTED_ODD,
                                parents);
                    }
                }
            }
        }
    }

    private static void reconstructEvenPair(
            final SearchState successor,
            final RestoredState restored,
            final Consumer<SearchState> parents,
            final LongAdder applications
    ) {
        final int fixedPoint = restored.fixedPoints()[0];
        for (final var joinedCycle : successor.pair().getOmega()) {
            if (joinedCycle.size() <= 1 || joinedCycle.size() % 2 == 0) {
                continue;
            }
            final int[] symbols = joinedCycle.getSymbols();
            for (var first = 0; first < symbols.length - 1; first++) {
                for (var second = first + 1; second < symbols.length; second++) {
                    applications.increment();
                    considerParent(successor, restored,
                            orderedCycle(restored.pair().getBeta(),
                                    symbols[first], symbols[second], fixedPoint),
                            ReductionTypeEnumerator.ReductionKind.EVEN_PAIR,
                            parents);
                }
            }
        }
    }

    private static void reconstructOddShrink(
            final SearchState successor,
            final RestoredState restored,
            final Consumer<SearchState> parents,
            final LongAdder applications
    ) {
        final int firstFixed = restored.fixedPoints()[0];
        final int secondFixed = restored.fixedPoints()[1];
        for (final var childCycle : successor.pair().getOmega()) {
            if (childCycle.size() <= 1 || childCycle.size() % 2 == 0) {
                continue;
            }
            for (final int symbol : childCycle.getSymbols()) {
                applications.increment();
                considerParent(successor, restored,
                        orderedCycle(restored.pair().getBeta(),
                                symbol, firstFixed, secondFixed),
                        ReductionTypeEnumerator.ReductionKind.ORIENTED_ODD,
                        parents);
            }
        }
    }

    private static void reconstructOrientedThree(
            final SearchState successor,
            final RestoredState restored,
            final Consumer<SearchState> parents,
            final LongAdder applications
    ) {
        applications.increment();
        considerParent(successor, restored,
                orderedCycle(restored.pair().getBeta(),
                        restored.fixedPoints()[0], restored.fixedPoints()[1],
                        restored.fixedPoints()[2]),
                ReductionTypeEnumerator.ReductionKind.ORIENTED_THREE, parents);
    }

    private static void considerParent(
            final SearchState successor,
            final RestoredState restored,
            final Cycle reconstructionMove,
            final ReductionTypeEnumerator.ReductionKind expectedKind,
            final Consumer<SearchState> parents
    ) {
        final var omega = PermutationGroups.computeProduct(
                restored.pair().getOmega(), reconstructionMove.getInverse());
        final var beta = CommonOperations.applyTranspositionOptimized(
                restored.pair().getBeta(), reconstructionMove);
        final var parent = CyclicTargetPair.of(omega, beta);
        requireForwardReplay(parent, restored.pair(), reconstructionMove);
        if (omega.stream().anyMatch(cycle -> cycle.size() == 1)) {
            return;
        }

        final var parentType = annotatedType(parent);
        final int parentMovesUsed = ReductionTypeEnumerator.MAX_BUDGET
                - successor.maximumMoves() - 1;
        if (!ReductionTypeEnumerator.isReachableOpenState(
                parentType.lengths(), parentType.oriented(), parentMovesUsed)) {
            return;
        }

        final int[] childLengths = cycleLengths(successor.pair());
        final boolean matchesChild = ReductionTypeEnumerator.allowsTransition(
                parentType.lengths(), parentType.oriented(), parentMovesUsed, expectedKind, childLengths);
        if (!matchesChild) {
            return;
        }
        parents.accept(new SearchState(successor.maximumMoves() + 1, parent));
    }

    private static void requireForwardReplay(
            final CyclicTargetPair parent,
            final CyclicTargetPair restoredSuccessor,
            final Cycle reconstructionMove
    ) {
        final int[] support = reconstructionMove.getSymbols();
        final var forwardMove = orderedCycle(parent.getBeta(),
                support[0], support[1], support[2]);
        final var replayedOmega = PermutationGroups.computeProduct(
                parent.getOmega(), forwardMove.getInverse());
        final var replayedBeta = CommonOperations.applyTranspositionOptimized(
                parent.getBeta(), forwardMove);
        if (!Arrays.equals(replayedOmega.getOneLineNotation(),
                restoredSuccessor.getOmega().getOneLineNotation())
                || !replayedBeta.equals(restoredSuccessor.getBeta())) {
            throw new IllegalStateException(
                    "Inverse reconstruction does not replay to its restored successor");
        }
    }

    /** Independently selects canonical labels from sequential insertions. */
    static void forEachRestoration(
            final CyclicTargetPair pair,
            final int count,
            final Consumer<RestoredState> consumer
    ) {
        if (count < 1 || count > 3) {
            throw new IllegalArgumentException(
                    "A restoration requires one to three fixed points");
        }
        restoreRecursively(pair, count, new int[count], 0, consumer);
    }

    private static void restoreRecursively(
            final CyclicTargetPair pair,
            final int remaining,
            final int[] fixedPoints,
            final int offset,
            final Consumer<RestoredState> consumer
    ) {
        if (remaining == 0) {
            // Keep exactly one labeling for each gap-multiplicity vector.
            // This deliberately retains the original insertion enumeration
            // rather than reusing the emitter's gap recursion.
            for (var index = 1; index < fixedPoints.length; index++) {
                if (pair.getBeta().indexOf(fixedPoints[index - 1])
                        > pair.getBeta().indexOf(fixedPoints[index])) {
                    return;
                }
            }
            consumer.accept(new RestoredState(pair, fixedPoints));
            return;
        }
        final int fixedPoint = pair.getBeta().size();
        fixedPoints[offset] = fixedPoint;
        final var omega = new MulticyclePermutation(pair.getOmega());
        omega.add(Cycle.of(fixedPoint));
        for (final int predecessor : pair.getBeta().getSymbols()) {
            final var beta = insertAfter(
                    pair.getBeta(), predecessor, fixedPoint);
            final var restored = CyclicTargetPair.of(omega, beta);
            if (restored.getRho().image(predecessor) != fixedPoint) {
                throw new IllegalStateException(
                        "Restored fixed point is not a common current/target edge");
            }
            restoreRecursively(restored, remaining - 1, fixedPoints,
                    offset + 1, consumer);
        }
    }

    private static Cycle insertAfter(
            final Cycle beta,
            final int predecessor,
            final int fixedPoint
    ) {
        final int[] symbols = new int[beta.size() + 1];
        var destination = 0;
        for (final int symbol : beta.getSymbols()) {
            symbols[destination++] = symbol;
            if (symbol == predecessor) {
                symbols[destination++] = fixedPoint;
            }
        }
        return Cycle.of(symbols);
    }

    private static Cycle orderedCycle(
            final Cycle beta,
            final int first,
            final int second,
            final int third
    ) {
        return CommonOperations.areSymbolsInCyclicOrder(
                beta, first, second, third)
                ? Cycle.of(first, second, third)
                : Cycle.of(first, third, second);
    }

    public static String annotatedTypeOf(final CyclicTargetPair pair) {
        return formatType(annotatedType(pair));
    }

    private static AnnotatedType annotatedType(final CyclicTargetPair pair) {
        final var cycles = pair.getOmega().stream()
                .filter(cycle -> cycle.size() > 1)
                .sorted(Comparator.comparingInt(Cycle::size).reversed()
                        .thenComparing(cycle -> cycle.size() > 2
                                && CommonOperations.isOriented(
                                pair.getBeta(), cycle)))
                .toList();
        final int[] lengths = new int[cycles.size()];
        final boolean[] oriented = new boolean[cycles.size()];
        for (var index = 0; index < cycles.size(); index++) {
            lengths[index] = cycles.get(index).size();
            oriented[index] = cycles.get(index).size() > 2
                    && CommonOperations.isOriented(
                    pair.getBeta(), cycles.get(index));
        }
        return new AnnotatedType(lengths, oriented);
    }

    private static int[] cycleLengths(final CyclicTargetPair pair) {
        return pair.getOmega().stream()
                .filter(cycle -> cycle.size() > 1)
                .mapToInt(Cycle::size)
                .boxed()
                .sorted(Comparator.reverseOrder())
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private static String formatType(final AnnotatedType type) {
        final var result = new StringBuilder("(");
        for (var index = 0; index < type.lengths().length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(type.lengths()[index]).append('-')
                    .append(type.oriented()[index] ? 'O' : 'U');
        }
        return result.append(')').toString();
    }

    private static void addState(
            final ConcurrentHashMap<StateKey, SearchState> states,
            final int maximumMoves,
            final CyclicTargetPair pair
    ) {
        final var key = new StateKey(
                maximumMoves, pair.extendedToricClassKey());
        states.putIfAbsent(key, new SearchState(maximumMoves, pair));
    }

    private record SearchBatchResult(long trivialClosures,
                                     long certificates,
                                     long residuals) {
    }

    private static SearchBatchResult searchStates(
            final List<SearchState> states,
            final PairSearch search,
            final Consumer<AvoidanceSearchMiss> missSink,
            final ConcurrentHashMap<StateKey, SearchState> residualStates,
            final boolean requireFullDomain
    ) {
        final var trivial = new LongAdder();
        final var certificates = new LongAdder();
        final var residuals = new LongAdder();
        final var cursor = new AtomicInteger();
        final var stop = new AtomicBoolean();
        final int workers = Math.min(states.size(),
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        final var futures = new ArrayList<Future<?>>();
        final ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("td18-avoidance-search-", 0).factory());
        var completed = false;
        try {
            for (var worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    try {
                        while (!stop.get()
                                && !Thread.currentThread().isInterrupted()) {
                            final int index = cursor.getAndIncrement();
                            if (index >= states.size()) {
                                return;
                            }
                            final var state = states.get(index);
                            if (requireFullDomain) {
                                requireRoot(state);
                            } else if (state.maximumMoves()
                                    >= ReductionTypeEnumerator.MAX_BUDGET
                                    || state.pair().getBeta().size()
                                    >= ReductionTypeEnumerator.ROOT_SYMBOLS) {
                                throw new IllegalStateException(
                                        "Intermediate avoidance search received a root state");
                            }
                            final int norm = state.pair().getOmega().stream()
                                    .mapToInt(cycle -> cycle.size() / 2).sum();
                            final float minimumRate = state.maximumMoves()
                                    / (2 * (float) norm);
                            final var result = search.search(
                                    state.pair(), minimumRate,
                                    state.maximumMoves(), requireFullDomain);
                            switch (result.disposition()) {
                                case TRIVIALLY_SETTLED -> trivial.increment();
                                case CERTIFICATE -> {
                                    if (!requireFullDomain) {
                                        throw new IllegalStateException(
                                                "Intermediate avoidance screening must not use a certificate");
                                    }
                                    certificates.increment();
                                }
                                case RESIDUAL -> {
                                    if (requireFullDomain) {
                                        final var miss = new AvoidanceSearchMiss(
                                                state.maximumMoves(), state.pair());
                                        Objects.requireNonNull(missSink)
                                                .accept(miss);
                                    }
                                    residuals.increment();
                                    addState(residualStates, state.maximumMoves(),
                                            state.pair());
                                }
                            }
                        }
                    } catch (final RuntimeException | Error throwable) {
                        stop.set(true);
                        throw throwable;
                    }
                }));
            }
            awaitAll(futures, "avoidance-preimage screening");
            completed = true;
        } finally {
            if (!completed) {
                stop.set(true);
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
        }
        return new SearchBatchResult(trivial.sum(), certificates.sum(),
                residuals.sum());
    }

    private static int compareCertificateKeys(
            final CyclicTargetPair first,
            final CyclicTargetPair second
    ) {
        return Arrays.compareUnsigned(
                CertificateCodec.keyFor(first), CertificateCodec.keyFor(second));
    }

    private static void awaitAll(
            final List<? extends Future<?>> futures,
            final String operation
    ) {
        try {
            for (final var future : futures) {
                future.get();
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
            throw new IllegalStateException(
                    "Interrupted during " + operation, exception);
        } catch (final ExecutionException exception) {
            futures.forEach(future -> future.cancel(true));
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    operation + " failed", exception.getCause());
        }
    }
}

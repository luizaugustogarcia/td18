package br.unb.cic.td18.reduction;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finite enumeration of the annotated cycle types that survive the recursive
 * reduction policy of the TD(18) two-reduction method.
 *
 * <p>States are pairs {@code (T, s)}: a contracted, fixed-point-free annotated
 * cycle type {@code T} on at most {@link #ROOT_SYMBOLS} symbols, and the number
 * {@code s} of transposition moves already spent. The remaining budget is
 * {@code b = MAX_BUDGET - s}. Each recursive edge applies one reduction
 * specified by that method and spends one move:
 * oriented 3-cycle clearing, a qualifying 2-move in the odd-length-cycle
 * reduction, or the even-pair reduction. Support-clearing alternatives that
 * fit the global budget close their branches without a recursive edge. Fixed points created by a reduction are contracted, and the
 * resulting cycle-length partition is re-annotated afresh. Totally unoriented
 * types stop before every structural reduction. The recursion also stops
 * after two reductions; unclosed states are recorded as generation
 * obligations.</p>
 */
public final class ReductionTypeEnumerator {
    /** Transposition-diameter budget targeted for order 18. */
    public static final int MAX_BUDGET = 10;
    /** Symbol count of an unreduced order-18 cyclic-target domain, {@code N = 19}. */
    public static final int ROOT_SYMBOLS = 19;
    private static final int DEPTH_CAP = 2;
    public static final int EXPECTED_OBLIGATIONS = 292;
    public static final Map<Integer, Integer> EXPECTED_DEPTH_COUNTS = Map.of(0, 59, 1, 45, 2, 188);

    /** Known transposition diameters {@code TD(0..17)}. */
    private static final int[] TD = {0, 0, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 9, 10};
    private static final int UNBOUNDED = Integer.MAX_VALUE;

    enum Td16Disposition {
        NOT_COVERED,
        SETTLED,
        PARTIAL
    }

    public enum ReductionKind {
        ORIENTED_THREE,
        ORIENTED_ODD,
        EVEN_PAIR,
        NONE
    }

    /** A possible transposition branch; an oriented odd-length branch requires a qualifying move. */
    public record ReductionOption(ReductionKind kind, int cycleLength, int[] childLengths) {
        public ReductionOption {
            childLengths = childLengths.clone();
        }

        @Override
        public int[] childLengths() {
            return childLengths.clone();
        }
    }

    /** Packed transition accepted by the checked reduction policy. */
    public record ReconstructionTransition(long parentType, long childAndKind) {
    }

    private static volatile Set<String> reachableOpenStates;

    private ReductionTypeEnumerator() {
    }

    /** Known diameter {@code D(N) = TD(N-1)} for {@code 2 <= N <= 18}; 0 for {@code N <= 1}; unbounded for {@code N >= 19}. */
    public static int knownDiameter(final int nonfixedSymbols) {
        if (nonfixedSymbols <= 1) {
            return 0;
        }
        if (nonfixedSymbols >= ROOT_SYMBOLS) {
            return UNBOUNDED;
        }
        return TD[nonfixedSymbols - 1];
    }

    /** True when state {@code (T, s)} closes before entering the terminal inventory. */
    private static boolean closes(final int nonfixedSymbols, final int movesUsed) {
        final int diameter = knownDiameter(nonfixedSymbols);
        return diameter != UNBOUNDED && movesUsed + diameter <= MAX_BUDGET;
    }

    private static int nonfixedSymbols(final int[] lengths) {
        return Arrays.stream(lengths).sum();
    }

    private static int threeNorm(final int[] lengths) {
        return Arrays.stream(lengths).map(length -> length / 2).sum();
    }

    /** An annotated cycle type recorded as a generation obligation. */
    public record Obligation(int[] lengths, boolean[] oriented, int movesUsed) {
        public Obligation {
            lengths = lengths.clone();
            oriented = oriented.clone();
        }

        public int nonfixedSymbols() {
            return ReductionTypeEnumerator.nonfixedSymbols(lengths);
        }

        public int threeNorm() {
            return ReductionTypeEnumerator.threeNorm(lengths);
        }

        public int budget() {
            return MAX_BUDGET - movesUsed;
        }

        /** Canonical key over the annotated type, ignoring the move count. */
        public String typeKey() {
            final var builder = new StringBuilder();
            for (var i = 0; i < lengths.length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(lengths[i]).append(oriented[i] ? 'O' : 'U');
            }
            return builder.toString();
        }
    }

    /**
     * Classifies the cases on 16 nonfixed symbols covered by the checked
     * TD(16) successor computation. Types containing an oriented 3-cycle close
     * directly; the other types are the 52 exhaustive eight-move searches.
     */
    static Td16Disposition td16Disposition(final Obligation obligation) {
        if (!td16CoveredDomain(obligation)) {
            return Td16Disposition.NOT_COVERED;
        }
        if (td16DirectClosure(obligation)) {
            return Td16Disposition.SETTLED;
        }
        return td16ResidualClasses(obligation) == 0
                ? Td16Disposition.SETTLED
                : Td16Disposition.PARTIAL;
    }

    private static boolean td16CoveredDomain(final Obligation obligation) {
        return obligation.nonfixedSymbols() == 16 && obligation.movesUsed() == 2;
    }

    /** True for the 52 covered types closed by clearing an oriented 3-cycle. */
    static boolean td16DirectClosure(final Obligation obligation) {
        if (!td16CoveredDomain(obligation)) {
            return false;
        }
        final int[] lengths = obligation.lengths();
        final boolean[] oriented = obligation.oriented();
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] == 3 && oriented[i]) {
                return true;
            }
        }
        return false;
    }

    /** True for the 52 covered types checked by exhaustive eight-move search. */
    static boolean td16SuccessorSearch(final Obligation obligation) {
        return td16CoveredDomain(obligation) && !td16DirectClosure(obligation);
    }

    /**
     * Residual class count reported by a checked eight-move successor search.
     * The two nonzero counts are recorded in {@code ../td16/checker.log}.
     */
    static int td16ResidualClasses(final Obligation obligation) {
        if (!td16SuccessorSearch(obligation)) {
            throw new IllegalArgumentException(
                    "TD(16) successor search requires a two-move type on 16 nonfixed symbols"
                            + " without an oriented 3-cycle");
        }
        return switch (obligation.typeKey()) {
            case "11O,5O" -> 495;
            case "9O,7O" -> 1_541;
            default -> 0;
        };
    }

    /**
     * Returns the row's emitted class obligation after applying the checked
     * TD(16) results. A partially settled TD(16) row remains a full obligation:
     * its bounded-search misses must enter the common TD(18) obstruction phase.
     */
    public static long remainingClassObligations(
            final Obligation obligation,
            final long realizableClasses
    ) {
        if (realizableClasses < 0) {
            throw new IllegalArgumentException("Realizable-class count must be nonnegative");
        }
        return switch (td16Disposition(obligation)) {
            case NOT_COVERED -> realizableClasses;
            case SETTLED -> 0L;
            case PARTIAL -> {
                if (td16ResidualClasses(obligation) > realizableClasses) {
                    throw new IllegalArgumentException(
                            "TD(16) residual exceeds the realizable-class count");
                }
                yield realizableClasses;
            }
        };
    }

    /**
     * Enumerates every generation obligation. Each distinct annotated type is
     * returned once, keeping the stricter (smaller) remaining budget when the
     * recursion reaches it at more than one depth.
     */
    public static List<Obligation> enumerate() {
        final var byType = new LinkedHashMap<String, Obligation>();
        for (final var partition : Partitions.generateValidPartitions(ROOT_SYMBOLS)) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            reverse(descending);
            for (final var root : AnnotatedPartitions.of(descending)) {
                recurse(root.partition(), root.oriented(), 0, byType);
            }
        }
        final var obligations = new ArrayList<>(byType.values());
        obligations.sort(tableOrder());
        return obligations;
    }

    /** Distinct {@code (type, budget)} pairs reached, before merging a repeated type's budgets. */
    public static int distinctStatePairs() {
        final var pairs = new java.util.HashSet<String>();
        for (final var partition : Partitions.generateValidPartitions(ROOT_SYMBOLS)) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            reverse(descending);
            for (final var root : AnnotatedPartitions.of(descending)) {
                collectPairs(root.partition(), root.oriented(), 0, pairs);
            }
        }
        return pairs.size();
    }

    private static void collectPairs(final int[] lengths, final boolean[] oriented,
                                     final int movesUsed, final java.util.Set<String> pairs) {
        final int nonfixedSymbols = nonfixedSymbols(lengths);
        if (closes(nonfixedSymbols, movesUsed)) {
            return;
        }
        final var children = reduce(lengths, oriented, movesUsed);
        if (movesUsed >= DEPTH_CAP || mayHaveNoAvailableReduction(lengths, oriented, movesUsed)) {
            pairs.add(key(lengths, oriented) + "@" + movesUsed);
        }
        if (movesUsed >= DEPTH_CAP || children == null) {
            return;
        }
        for (final int[] childLengths : children) {
            for (final var child : AnnotatedPartitions.of(childLengths)) {
                collectPairs(child.partition(), child.oriented(), movesUsed + 1, pairs);
            }
        }
    }

    private static void recurse(final int[] lengths, final boolean[] oriented,
                                final int movesUsed, final Map<String, Obligation> byType) {
        final int nonfixedSymbols = nonfixedSymbols(lengths);
        if (closes(nonfixedSymbols, movesUsed)) {
            return;
        }
        final var children = reduce(lengths, oriented, movesUsed);
        if (movesUsed >= DEPTH_CAP || mayHaveNoAvailableReduction(lengths, oriented, movesUsed)) {
            final var obligation = new Obligation(lengths, oriented, movesUsed);
            byType.merge(obligation.typeKey(), obligation,
                    (existing, candidate) -> existing.movesUsed() >= candidate.movesUsed() ? existing : candidate);
        }
        if (movesUsed >= DEPTH_CAP || children == null) {
            return;
        }
        for (final int[] childLengths : children) {
            for (final var child : AnnotatedPartitions.of(childLengths)) {
                recurse(child.partition(), child.oriented(), movesUsed + 1, byType);
            }
        }
    }

    /**
     * Enumerates possible qualifying branches and the even-pair fallback.
     * An orientation annotation alone never proves availability of an odd-cycle move.
     * Returns the child cycle-length
     * partitions (each non-increasing), re-annotated afresh by the caller.
     * Returns {@code null} when no reduction applies and the state is retained.
     */
    private static List<int[]> reduce(final int[] lengths, final boolean[] oriented, final int movesUsed) {
        final var options = reductionOptions(lengths, oriented, movesUsed);
        return options.isEmpty() ? null : options.stream().map(ReductionOption::childLengths).toList();
    }

    /** The type-level policy stops when every orientation annotation is U. */
    public static boolean isTotallyUnoriented(final boolean[] oriented) {
        for (final boolean cycleIsOriented : oriented) {
            if (cycleIsOriented) return false;
        }
        return true;
    }

    /**
     * Totally unoriented types are retained before testing any reduction.
     * Otherwise a retained obligation is needed if every qualifying 2-move is unavailable,
     * no even pair remains, and no established clearing alternative fits the budget.
     */
    public static boolean mayHaveNoAvailableReduction(final int[] lengths, final boolean[] oriented, final int movesUsed) {
        if (isTotallyUnoriented(oriented)) {
            return true;
        }
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] == 3 && oriented[i]) {
                return false;
            }
        }
        return twoLargestEvenIndices(lengths) == null
                && closingOddCycleLength(lengths, oriented, movesUsed) == 0;
    }

    /**
     * First oriented odd length whose non-qualifying alternative closes the state.
     * For 5, 7, 9, 11, 13, 17 the shared paper proves a support-clearing
     * alternative in (k+1)/2 moves, except for the exceptional local 9-cycle.
     * Its 19-symbol extensions and the two exceptional oriented 19-cycles have
     * separate certificate obligations in this repository. The independently
     * checked length-15 classification supplies a qualifying move or the
     * exceptional local candidate, whose interleavings are separate certificate
     * obligations at (N,s) = (19,0) and (18,1).
     */
    public static int closingOddCycleLength(final int[] lengths, final boolean[] oriented, final int movesUsed) {
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] == 3 && oriented[i]) {
                return 0;
            }
        }
        var selected = Integer.MAX_VALUE;
        for (var i = 0; i < lengths.length; i++) {
            final int k = lengths[i];
            if (!oriented[i]) continue;
            final boolean classified = k == 5 || k == 7 || k == 9 || k == 11 || k == 13 || k == 17;
            final boolean exceptionalFifteen = k == 15
                    && (movesUsed == 0 && nonfixedSymbols(lengths) == 19
                        || movesUsed == 1 && nonfixedSymbols(lengths) == 18);
            final boolean exceptionalRoot = k == 19 && movesUsed == 0 && nonfixedSymbols(lengths) == 19;
            if ((classified && (k != 9 || movesUsed == 0 && nonfixedSymbols(lengths) == 19)
                    && movesUsed + (k + 1) / 2 + knownDiameter(nonfixedSymbols(lengths) - k) <= MAX_BUDGET)
                    || exceptionalRoot || exceptionalFifteen) {
                selected = Math.min(selected, k);
            }
        }
        return selected == Integer.MAX_VALUE ? 0 : selected;
    }

    /**
     * Every type-level branch needed when smaller oriented odd-length cycles fail
     * their qualifying-move test. Equal-length cycles are represented once.
     */
    public static List<ReductionOption> reductionOptions(final int[] lengths, final boolean[] oriented, final int movesUsed) {
        // Totally unoriented types are direct obligations before any structural
        // reduction, including the even-pair fallback, at every depth.
        if (isTotallyUnoriented(oriented)) {
            return List.of();
        }
        // Reduction R2: clear an oriented 3-cycle.
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] == 3 && oriented[i]) {
                return List.of(new ReductionOption(ReductionKind.ORIENTED_THREE, 3, withoutIndex(lengths, i)));
            }
        }
        final var children = new ArrayList<ReductionOption>();
        final var oddLengths = new java.util.TreeSet<Integer>();
        for (var i = 0; i < lengths.length; i++) {
            if (oriented[i] && lengths[i] >= 5 && lengths[i] % 2 == 1) {
                oddLengths.add(lengths[i]);
            }
        }
        for (final int oddLength : oddLengths) {
            final int index = firstIndexOfLength(lengths, oddLength);
            final int[] others = withoutIndex(lengths, index);
            children.add(new ReductionOption(ReductionKind.ORIENTED_ODD, oddLength,
                    sortedWith(others, oddLength - 2)));
            for (var a = 3; a + a <= oddLength - 1; a += 2) {           // k -> a + b, a <= b, both odd >= 3
                final int b = oddLength - 1 - a;
                if (b >= 3 && b % 2 == 1) {
                    children.add(new ReductionOption(ReductionKind.ORIENTED_ODD, oddLength,
                            sortedWith(others, a, b)));
                }
            }
            if (oddLength == closingOddCycleLength(lengths, oriented, movesUsed)) {
                break;
            }
        }
        if (closingOddCycleLength(lengths, oriented, movesUsed) != 0) {
            return List.copyOf(children);
        }
        // The no-qualifying branch reaches the even-pair reduction only if
        // no earlier support-clearing alternative fits the remaining budget.
        // Reduction R3: even-pair merge of the two largest even cycles.
        final int[] evenIndices = twoLargestEvenIndices(lengths);
        if (evenIndices != null) {
            final int x = lengths[evenIndices[0]];
            final int y = lengths[evenIndices[1]];
            int[] others = withoutIndex(lengths, evenIndices[0]);
            // Remove the second even cycle from the already-shortened array.
            final int secondInOthers = firstIndexOfLength(others, y);
            others = withoutIndex(others, secondInOthers);
            children.add(new ReductionOption(ReductionKind.EVEN_PAIR, 0, sortedWith(others, x + y - 1)));
        }
        return List.copyOf(children);
    }

    public static boolean allowsTransition(final int[] lengths, final boolean[] oriented, final int movesUsed,
                                           final ReductionKind kind, final int[] childLengths) {
        return reductionOptions(lengths, oriented, movesUsed).stream().anyMatch(option ->
                option.kind() == kind && Arrays.equals(option.childLengths(), childLengths));
    }

    public static List<int[]> selectedChildPartitions(
            final int[] lengths,
            final boolean[] oriented,
            final int movesUsed
    ) {
        final var children = reduce(lengths, oriented, movesUsed);
        if (children == null) {
            return List.of();
        }
        return children.stream().map(int[]::clone).toList();
    }

    public static boolean isReachableOpenState(
            final int[] lengths,
            final boolean[] oriented,
            final int movesUsed
    ) {
        if (movesUsed < 0 || movesUsed > DEPTH_CAP) {
            return false;
        }
        var states = reachableOpenStates;
        if (states == null) {
            synchronized (ReductionTypeEnumerator.class) {
                states = reachableOpenStates;
                if (states == null) {
                    final var collected = new java.util.HashSet<String>();
                    for (final var partition : Partitions.generateValidPartitions(
                            ROOT_SYMBOLS)) {
                        final int[] descending = partition.clone();
                        Arrays.sort(descending);
                        reverse(descending);
                        for (final var root : AnnotatedPartitions.of(descending)) {
                            collectReachableOpenStates(root.partition(),
                                    root.oriented(), 0, collected);
                        }
                    }
                    states = Set.copyOf(collected);
                    reachableOpenStates = states;
                }
            }
        }
        return states.contains(key(lengths, oriented) + '@' + movesUsed);
    }

    /**
     * Returns the complete transition table used to check inverse
     * reconstruction on the GPU. The table is derived from the same finite
     * enumerator as the CPU predicate; it is not a second implementation of
     * the reduction policy.
     */
    public static List<ReconstructionTransition> reconstructionTransitions(
            final int movesUsed
    ) {
        if (movesUsed < 0 || movesUsed > DEPTH_CAP) {
            throw new IllegalArgumentException(
                    "Reconstruction depth lies outside the two-reduction computation");
        }
        final var transitions = new java.util.HashSet<ReconstructionTransition>();
        for (var symbols = 2; symbols <= ROOT_SYMBOLS; symbols++) {
            for (final var partition : Partitions.generateValidPartitions(symbols)) {
                for (final var annotated : AnnotatedPartitions.of(partition)) {
                    final int[] lengths = annotated.partition();
                    final boolean[] oriented = annotated.oriented();
                    if (!isReachableOpenState(lengths, oriented, movesUsed)) {
                        continue;
                    }
                    final long parentType = packAnnotatedType(lengths, oriented);
                    for (final var option : reductionOptions(lengths, oriented, movesUsed)) {
                        transitions.add(new ReconstructionTransition(parentType,
                                (packPartition(option.childLengths()) << 2) | option.kind().ordinal()));
                    }
                }
            }
        }
        return transitions.stream()
                .sorted(Comparator.comparingLong(
                                ReconstructionTransition::parentType)
                        .thenComparingLong(
                                ReconstructionTransition::childAndKind))
                .toList();
    }

    static long packAnnotatedType(
            final int[] lengths,
            final boolean[] oriented
    ) {
        if (lengths.length != oriented.length || lengths.length > 9) {
            throw new IllegalArgumentException(
                    "Annotated reconstruction type does not fit its packed form");
        }
        long packed = lengths.length;
        for (var index = 0; index < lengths.length; index++) {
            if (lengths[index] < 2 || lengths[index] > ROOT_SYMBOLS) {
                throw new IllegalArgumentException(
                        "Reconstruction cycle length lies outside 2..19");
            }
            final long entry = lengths[index] | (oriented[index] ? 0x20L : 0L);
            packed |= entry << (4 + 6 * index);
        }
        return packed;
    }

    static long packPartition(final int[] lengths) {
        if (lengths.length > 9) {
            throw new IllegalArgumentException(
                    "Reconstruction partition does not fit its packed form");
        }
        long packed = lengths.length;
        for (var index = 0; index < lengths.length; index++) {
            if (lengths[index] < 2 || lengths[index] > ROOT_SYMBOLS) {
                throw new IllegalArgumentException(
                        "Reconstruction partition length lies outside 2..19");
            }
            packed |= (long) lengths[index] << (4 + 5 * index);
        }
        return packed;
    }

    private static void collectReachableOpenStates(
            final int[] lengths,
            final boolean[] oriented,
            final int movesUsed,
            final Set<String> result
    ) {
        if (closes(nonfixedSymbols(lengths), movesUsed)) {
            return;
        }
        result.add(key(lengths, oriented) + '@' + movesUsed);
        final var children = reduce(lengths, oriented, movesUsed);
        if (movesUsed >= DEPTH_CAP || children == null) {
            return;
        }
        for (final var childLengths : children) {
            for (final var child : AnnotatedPartitions.of(childLengths)) {
                collectReachableOpenStates(child.partition(), child.oriented(),
                        movesUsed + 1, result);
            }
        }
    }

    /** Smallest oriented odd length {@code >= 5}, or 0 when none exists. */
    private static int selectOrientedOdd(final int[] lengths, final boolean[] oriented) {
        int selected = 0;
        for (var i = 0; i < lengths.length; i++) {
            final int k = lengths[i];
            if (!oriented[i] || k < 5 || k % 2 == 0) {
                continue;
            }
            if (selected == 0 || k < selected) {
                selected = k;
            }
        }
        return selected;
    }

    private static int[] twoLargestEvenIndices(final int[] lengths) {
        int first = -1;
        int second = -1;
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] % 2 != 0) {
                continue;
            }
            if (first < 0 || lengths[i] > lengths[first]) {
                second = first;
                first = i;
            } else if (second < 0 || lengths[i] > lengths[second]) {
                second = i;
            }
        }
        return (first >= 0 && second >= 0) ? new int[]{first, second} : null;
    }

    private static int firstIndexOfLength(final int[] lengths, final int length) {
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] == length) {
                return i;
            }
        }
        throw new IllegalArgumentException("Length " + length + " absent from " + Arrays.toString(lengths));
    }

    private static int[] withoutIndex(final int[] lengths, final int index) {
        final int[] result = new int[lengths.length - 1];
        for (int i = 0, j = 0; i < lengths.length; i++) {
            if (i != index) {
                result[j++] = lengths[i];
            }
        }
        return result;
    }

    private static int[] sortedWith(final int[] lengths, final int... extra) {
        final int[] result = Arrays.copyOf(lengths, lengths.length + extra.length);
        System.arraycopy(extra, 0, result, lengths.length, extra.length);
        final Integer[] boxed = Arrays.stream(result).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, Comparator.reverseOrder());
        for (var i = 0; i < result.length; i++) {
            result[i] = boxed[i];
        }
        return result;
    }

    private static void reverse(final int[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            final int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static String key(final int[] lengths, final boolean[] oriented) {
        final var builder = new StringBuilder();
        for (var i = 0; i < lengths.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(lengths[i]).append(oriented[i] ? 'O' : 'U');
        }
        return builder.toString();
    }

    /** Table order: depth, 3-norm, nonfixed symbols descending, then type. */
    private static Comparator<Obligation> tableOrder() {
        return Comparator
                .comparing(ReductionTypeEnumerator::isFallbackObligation)
                .thenComparingInt(Obligation::movesUsed)
                .thenComparingInt(Obligation::threeNorm)
                .thenComparing(Comparator.comparingInt(Obligation::nonfixedSymbols).reversed())
                .thenComparing(ReductionTypeEnumerator::compareLengthsDescending)
                .thenComparing(ReductionTypeEnumerator::compareOrientations);
    }

    /** Append newly retained early types; preserve the existing 204 row numbers. */
    private static boolean isFallbackObligation(final Obligation obligation) {
        return obligation.movesUsed() < DEPTH_CAP
                && (selectOrientedOdd(obligation.lengths(), obligation.oriented()) > 0
                    || isTotallyUnoriented(obligation.oriented())
                       && Arrays.stream(obligation.lengths()).anyMatch(length -> length % 2 == 0));
    }

    private static int compareLengthsDescending(final Obligation a, final Obligation b) {
        final int[] x = a.lengths();
        final int[] y = b.lengths();
        for (var i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i] != y[i]) {
                return Integer.compare(y[i], x[i]);
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int compareOrientations(final Obligation a, final Obligation b) {
        final boolean[] x = a.oriented();
        final boolean[] y = b.oriented();
        for (var i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i] != y[i]) {
                return x[i] ? 1 : -1; // unoriented before oriented
            }
        }
        return 0;
    }

    // ---- Rendering and reporting -------------------------------------------

    private static String renderType(final Obligation obligation) {
        final var builder = new StringBuilder("\\ctype{");
        final int[] lengths = obligation.lengths();
        final boolean[] oriented = obligation.oriented();
        for (var i = 0; i < lengths.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("\\cyc{").append(lengths[i]).append("}{")
                    .append(oriented[i] ? 'O' : 'U').append('}');
        }
        return builder.append('}').toString();
    }

    private static String groupThousands(final long value) {
        final String digits = Long.toString(value);
        final var builder = new StringBuilder();
        final int firstGroup = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
        builder.append(digits, 0, firstGroup);
        for (var i = firstGroup; i < digits.length(); i += 3) {
            builder.append("\\,").append(digits, i, i + 3);
        }
        return builder.toString();
    }

    /**
     * Renders the complete type inventory. R is the realizable-class count and
     * Q is the emitted class obligation after checked TD(16) reuse.
     */
    public static String renderTable(final List<Obligation> obligations) {
        final var rows = new StringBuilder();
        long totalRealizable = 0L;
        long totalRemaining = 0L;
        try {
            var number = 1;
            for (final var obligation : obligations) {
                final long classes = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                        obligation.lengths(), obligation.oriented());
                final long remaining = remainingClassObligations(obligation, classes);
                totalRealizable = Math.addExact(totalRealizable, classes);
                totalRemaining = Math.addExact(totalRemaining, remaining);
                final var renderedRemaining = groupThousands(remaining)
                        + (td16Disposition(obligation) == Td16Disposition.PARTIAL
                        ? "\\textsuperscript{*}" : "");
                rows.append(String.format(Locale.ROOT,
                        "  %d & %s & %d & %d & %d & %d & %d & %s & %s \\\\%n",
                        number++, renderType(obligation), obligation.nonfixedSymbols(),
                        ROOT_SYMBOLS - obligation.nonfixedSymbols(),
                        obligation.movesUsed(), obligation.budget(), obligation.threeNorm(),
                        groupThousands(classes), renderedRemaining));
            }
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
        rows.append("  \\midrule\n");
        rows.append(String.format(Locale.ROOT,
                "  \\multicolumn{7}{r}{\\textbf{Total}} & \\textbf{%s} & \\textbf{%s} \\\\%n",
                groupThousands(totalRealizable), groupThousands(totalRemaining)));
        return rows.toString();
    }

    public static void main(final String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("--validate")) {
            validate();
            return;
        }
        if (args.length > 0 && args[0].equals("--summary")) {
            summarize();
            return;
        }
        if (args.length > 0 && args[0].startsWith("--write-table=")) {
            final var path = Path.of(args[0].substring("--write-table=".length())).toAbsolutePath();
            writeTable(path);
            return;
        }
        System.out.println("ReductionTypeEnumerator [--validate | --summary | --write-table=rows.tex]");
    }

    private static void validate() throws IOException {
        final var partitions = Partitions.generateValidPartitions(ROOT_SYMBOLS);
        var annotatedRoots = 0;
        for (final var partition : partitions) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            reverse(descending);
            annotatedRoots += AnnotatedPartitions.of(descending).size();
        }
        final int pairs = distinctStatePairs();
        final var obligations = enumerate();
        final var depth = new TreeMap<Integer, Integer>();
        for (final var obligation : obligations) {
            depth.merge(obligation.movesUsed(), 1, Integer::sum);
        }
        if (partitions.size() != 53 || annotatedRoots != 360
                || pairs != EXPECTED_OBLIGATIONS || obligations.size() != EXPECTED_OBLIGATIONS
                || !depth.equals(EXPECTED_DEPTH_COUNTS)) {
            throw new IllegalStateException("Reduction inventory differs from the availability-aware route");
        }
        final var document = br.unb.cic.td18.runtime.ProjectPaths.root(Path.of(""))
                .resolve("docs/td18-reduction-count.tex");
        final var rows = br.unb.cic.td18.table.Table2.read(document);
        for (var index = 0; index < obligations.size(); index++) {
            final var expected = obligations.get(index);
            final var actual = rows.get(index);
            if (!Arrays.equals(expected.lengths(), actual.partition())
                    || !Arrays.equals(expected.oriented(), actual.orientedByPart())
                    || expected.nonfixedSymbols() != actual.nonfixedSymbols()
                    || ROOT_SYMBOLS - expected.nonfixedSymbols() != actual.fixedSymbols()
                    || expected.movesUsed() != actual.movesUsed()
                    || expected.budget() != actual.remainingMoves()
                    || expected.threeNorm() != actual.threeNorm()) {
                throw new IllegalStateException("Reduction inventory disagrees with Table 2 row "
                        + actual.number());
            }
        }
        System.out.println("admissiblePartitions=" + partitions.size() + " (expected 53)");
        System.out.println("annotatedRoots=" + annotatedRoots + " (expected 360)");
        System.out.println("distinctStatePairs=" + pairs + " (expected " + EXPECTED_OBLIGATIONS + ')');
        System.out.println("distinctTypes=" + obligations.size() + " (expected " + EXPECTED_OBLIGATIONS + ')');
        System.out.println("depthDistribution=" + depth + " (expected " + EXPECTED_DEPTH_COUNTS + ')');
    }

    private static void summarize() {
        final var obligations = enumerate();
        final var depth = new TreeMap<Integer, Integer>();
        final var budgetByDepth = new TreeMap<String, Integer>();
        for (final var obligation : obligations) {
            depth.merge(obligation.movesUsed(), 1, Integer::sum);
            budgetByDepth.merge(obligation.movesUsed() + "/" + obligation.budget() + "/" + obligation.threeNorm(),
                    1, Integer::sum);
        }
        System.out.println("distinctStatePairs=" + distinctStatePairs());
        System.out.println("distinctTypes=" + obligations.size());
        System.out.println("depthDistribution=" + depth);
        System.out.println("budget/threeNorm breakdown (movesUsed/budget/threeNorm -> count):");
        budgetByDepth.forEach((k, v) -> System.out.println("  " + k + " -> " + v));
    }

    private static void writeTable(final Path path) throws IOException {
        final var obligations = enumerate();
        final var body = renderTable(obligations);
        Files.writeString(path, body, StandardCharsets.UTF_8);
        System.out.println("Wrote " + obligations.size() + " rows to " + path);
    }
}

package br.unb.cic.td18;

import br.unb.cic.tdp.DirectTwistedBraceletGenerator;
import br.unb.cic.tdp.Partitions;

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
import java.util.TreeMap;

/**
 * Finite enumeration of the annotated cycle types that survive the recursive
 * reduction policy of the TD(18) reduction note.
 *
 * <p>States are pairs {@code (T, s)}: a contracted, fixed-point-free annotated
 * cycle type {@code T} on at most {@link #ROOT_SYMBOLS} symbols, and the number
 * {@code s} of transposition moves already spent. The remaining budget is
 * {@code b = MAX_BUDGET - s}. Each recursive edge applies one forward reduction
 * imported from the TD(16) framework and spends one move:
 * oriented 3-cycle clearing, the oriented-odd qualifying 2-move, or the
 * even-pair merge. Fixed points created by a reduction are contracted, and the
 * resulting cycle-length partition is re-annotated afresh. Obstruction handling
 * (the inverse-family propagation of the upstream TD(16) framework) is downstream work and is
 * not an enumeration edge here; unclosed states are recorded as obligations.</p>
 *
 * <p>The {@link Policy} record selects between the historical baseline (depth
 * three, combined diameter/approximation closure, support-clearing-gated odd
 * selection) used to reproduce the published 106-type count, and the current
 * policy (depth two, known-diameter-only closure, ungated odd selection).</p>
 */
public final class ReductionTypeEnumerator {
    /** Transposition-diameter budget targeted for order 18. */
    public static final int MAX_BUDGET = 10;
    /** Symbol count of an unreduced order-18 cyclic-target domain, {@code N = 19}. */
    public static final int ROOT_SYMBOLS = 19;

    /** Known transposition diameters {@code TD(0..17)}. */
    private static final int[] TD = {0, 0, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 9, 10};
    private static final int UNBOUNDED = Integer.MAX_VALUE;

    /**
     * Reduction policy. The baseline reproduces the published count; the current
     * policy is the risk-taking two-reduction recursion.
     */
    public record Policy(int depthCap, boolean diameterOnlyClosure, boolean supportClearingGate) {
        public static final Policy BASELINE = new Policy(3, false, true);
        public static final Policy CURRENT = new Policy(2, true, false);
    }

    private ReductionTypeEnumerator() {
    }

    /** Known diameter {@code D(M) = TD(M-1)} for {@code 2 <= M <= 18}; 0 for {@code M <= 1}; unbounded for {@code M >= 19}. */
    static int knownDiameter(final int mass) {
        if (mass <= 1) {
            return 0;
        }
        if (mass >= ROOT_SYMBOLS) {
            return UNBOUNDED;
        }
        return TD[mass - 1];
    }

    /** True when state {@code (T, s)} closes without generating an obligation. */
    private static boolean closes(final int mass, final int threeNorm, final int movesUsed, final Policy policy) {
        final int diameter = knownDiameter(mass);
        if (policy.diameterOnlyClosure()) {
            return diameter != UNBOUNDED && movesUsed + diameter <= MAX_BUDGET;
        }
        final int bound = Math.min(diameter, ApproximationBound.guaranteedMoves(threeNorm));
        return movesUsed + bound <= MAX_BUDGET;
    }

    private static int mass(final int[] lengths) {
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

        public int mass() {
            return ReductionTypeEnumerator.mass(lengths);
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
     * Enumerates every generation obligation under {@code policy}. Each distinct
     * annotated type is returned once, keeping the stricter (smaller) remaining
     * budget when the recursion reaches it at more than one depth.
     */
    public static List<Obligation> enumerate(final Policy policy) {
        final var byType = new LinkedHashMap<String, Obligation>();
        for (final var partition : Partitions.generateValidPartitions(ROOT_SYMBOLS)) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            reverse(descending);
            for (final var root : AnnotatedPartitions.of(descending)) {
                recurse(root.partition(), root.oriented(), 0, policy, byType);
            }
        }
        final var obligations = new ArrayList<>(byType.values());
        obligations.sort(tableOrder());
        return obligations;
    }

    /** Distinct {@code (type, budget)} pairs reached, before merging a repeated type's budgets. */
    public static int distinctStatePairs(final Policy policy) {
        final var pairs = new java.util.HashSet<String>();
        for (final var partition : Partitions.generateValidPartitions(ROOT_SYMBOLS)) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            reverse(descending);
            for (final var root : AnnotatedPartitions.of(descending)) {
                collectPairs(root.partition(), root.oriented(), 0, policy, pairs);
            }
        }
        return pairs.size();
    }

    private static void collectPairs(final int[] lengths, final boolean[] oriented, final int movesUsed,
                                     final Policy policy, final java.util.Set<String> pairs) {
        final int mass = mass(lengths);
        if (closes(mass, threeNorm(lengths), movesUsed, policy)) {
            return;
        }
        final var children = reduce(lengths, oriented, movesUsed, policy);
        if (movesUsed >= policy.depthCap() || children == null) {
            pairs.add(key(lengths, oriented) + "@" + movesUsed);
            return;
        }
        for (final int[] childLengths : children) {
            for (final var child : AnnotatedPartitions.of(childLengths)) {
                collectPairs(child.partition(), child.oriented(), movesUsed + 1, policy, pairs);
            }
        }
    }

    private static void recurse(final int[] lengths, final boolean[] oriented, final int movesUsed,
                                final Policy policy, final Map<String, Obligation> byType) {
        final int mass = mass(lengths);
        if (closes(mass, threeNorm(lengths), movesUsed, policy)) {
            return;
        }
        final var children = reduce(lengths, oriented, movesUsed, policy);
        if (movesUsed >= policy.depthCap() || children == null) {
            final var obligation = new Obligation(lengths, oriented, movesUsed);
            byType.merge(obligation.typeKey(), obligation,
                    (existing, candidate) -> existing.movesUsed() >= candidate.movesUsed() ? existing : candidate);
            return;
        }
        for (final int[] childLengths : children) {
            for (final var child : AnnotatedPartitions.of(childLengths)) {
                recurse(child.partition(), child.oriented(), movesUsed + 1, policy, byType);
            }
        }
    }

    /**
     * Applies the single selected reduction and returns the child cycle-length
     * partitions (each non-increasing), re-annotated afresh by the caller.
     * Returns {@code null} when no reduction applies and the state is retained.
     */
    private static List<int[]> reduce(final int[] lengths, final boolean[] oriented, final int movesUsed,
                                      final Policy policy) {
        // Reduction R2: clear an oriented 3-cycle.
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] == 3 && oriented[i]) {
                return List.of(withoutIndex(lengths, i));
            }
        }
        // Reduction R4a: qualifying 2-move on the smallest oriented odd cycle of length >= 5.
        final int oddLength = selectOrientedOdd(lengths, oriented, movesUsed, policy);
        if (oddLength > 0) {
            final int index = firstIndexOfLength(lengths, oddLength);
            final int[] others = withoutIndex(lengths, index);
            final var children = new ArrayList<int[]>();
            children.add(sortedWith(others, oddLength - 2));            // k -> k-2, two fixed points
            for (var a = 3; a + a <= oddLength - 1; a += 2) {           // k -> a + b, a <= b, both odd >= 3
                final int b = oddLength - 1 - a;
                if (b >= 3 && b % 2 == 1) {
                    children.add(sortedWith(others, a, b));
                }
            }
            return children;
        }
        // Reduction R3: even-pair merge of the two largest even cycles.
        final int[] evenIndices = twoLargestEvenIndices(lengths);
        if (evenIndices != null) {
            final int x = lengths[evenIndices[0]];
            final int y = lengths[evenIndices[1]];
            int[] others = withoutIndex(lengths, evenIndices[0]);
            // Remove the second even cycle from the already-shortened array.
            final int secondInOthers = firstIndexOfLength(others, y);
            others = withoutIndex(others, secondInOthers);
            return List.of(sortedWith(others, x + y - 1));
        }
        return null;
    }

    /** Smallest oriented odd length {@code >= 5} eligible under the policy, or 0. */
    private static int selectOrientedOdd(final int[] lengths, final boolean[] oriented, final int movesUsed,
                                         final Policy policy) {
        int selected = 0;
        for (var i = 0; i < lengths.length; i++) {
            final int k = lengths[i];
            if (!oriented[i] || k < 5 || k % 2 == 0) {
                continue;
            }
            if (policy.supportClearingGate() && !supportClearingCloses(lengths, i, movesUsed)) {
                continue;
            }
            if (selected == 0 || k < selected) {
                selected = k;
            }
        }
        return selected;
    }

    /** Baseline gate: the support-clearing alternative for the length-{@code k} cycle closes. */
    private static boolean supportClearingCloses(final int[] lengths, final int index, final int movesUsed) {
        final int k = lengths[index];
        final int[] companion = withoutIndex(lengths, index);
        final int companionBound = Math.min(knownDiameter(mass(companion)),
                ApproximationBound.guaranteedMoves(threeNorm(companion)));
        return movesUsed + (k + 1) / 2 + companionBound <= MAX_BUDGET;
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

    /** Table order: depth, then 3-norm, then mass descending, then lengths and orientations. */
    private static Comparator<Obligation> tableOrder() {
        return Comparator
                .comparingInt(Obligation::movesUsed)
                .thenComparingInt(Obligation::threeNorm)
                .thenComparing(Comparator.comparingInt(Obligation::mass).reversed())
                .thenComparing(ReductionTypeEnumerator::compareLengthsDescending)
                .thenComparing(ReductionTypeEnumerator::compareOrientations);
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

    /** Renders the full {@code longtable} body (rows and total) with Burnside class counts. */
    public static String renderTable(final List<Obligation> obligations) {
        final var rows = new StringBuilder();
        long total = 0L;
        try {
            var number = 1;
            for (final var obligation : obligations) {
                final long classes = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                        obligation.lengths(), obligation.oriented());
                total = Math.addExact(total, classes);
                rows.append(String.format(Locale.ROOT, "  %d & %s & %d & %d & %d & %d & %d & %s \\\\%n",
                        number++, renderType(obligation), obligation.mass(), ROOT_SYMBOLS - obligation.mass(),
                        obligation.movesUsed(), obligation.budget(), obligation.threeNorm(),
                        groupThousands(classes)));
            }
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
        rows.append("  \\midrule\n");
        rows.append(String.format(Locale.ROOT,
                "  \\multicolumn{7}{r}{\\textbf{Total}} & \\textbf{%s} \\\\%n", groupThousands(total)));
        return rows.toString();
    }

    public static void main(final String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("--validate")) {
            validateBaseline();
            return;
        }
        if (args.length > 0 && args[0].equals("--summary")) {
            summarize(Policy.CURRENT);
            return;
        }
        if (args.length > 0 && args[0].startsWith("--write-table=")) {
            final var path = Path.of(args[0].substring("--write-table=".length())).toAbsolutePath();
            writeTable(path);
            return;
        }
        System.out.println("ReductionTypeEnumerator [--validate | --summary | --write-table=rows.tex]");
    }

    private static void validateBaseline() {
        final var partitions = Partitions.generateValidPartitions(ROOT_SYMBOLS);
        var annotatedRoots = 0;
        for (final var partition : partitions) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            reverse(descending);
            annotatedRoots += AnnotatedPartitions.of(descending).size();
        }
        final int pairs = distinctStatePairs(Policy.BASELINE);
        final var obligations = enumerate(Policy.BASELINE);
        final var depth = new TreeMap<Integer, Integer>();
        for (final var obligation : obligations) {
            depth.merge(obligation.movesUsed(), 1, Integer::sum);
        }
        System.out.println("admissiblePartitions=" + partitions.size() + " (expected 53)");
        System.out.println("annotatedRoots=" + annotatedRoots + " (expected 360)");
        System.out.println("distinctStatePairs=" + pairs + " (expected 107)");
        System.out.println("distinctTypes=" + obligations.size() + " (expected 106)");
        System.out.println("depthDistribution=" + depth + " (expected {0=10, 1=21, 2=18, 3=57})");
    }

    private static void summarize(final Policy policy) {
        final var obligations = enumerate(policy);
        final var depth = new TreeMap<Integer, Integer>();
        final var budgetByDepth = new TreeMap<String, Integer>();
        for (final var obligation : obligations) {
            depth.merge(obligation.movesUsed(), 1, Integer::sum);
            budgetByDepth.merge(obligation.movesUsed() + "/" + obligation.budget() + "/" + obligation.threeNorm(),
                    1, Integer::sum);
        }
        System.out.println("distinctStatePairs=" + distinctStatePairs(policy));
        System.out.println("distinctTypes=" + obligations.size());
        System.out.println("depthDistribution=" + depth);
        System.out.println("budget/threeNorm breakdown (movesUsed/budget/threeNorm -> count):");
        budgetByDepth.forEach((k, v) -> System.out.println("  " + k + " -> " + v));
    }

    private static void writeTable(final Path path) throws IOException {
        final var obligations = enumerate(Policy.CURRENT);
        final var body = renderTable(obligations);
        Files.writeString(path, body, StandardCharsets.UTF_8);
        System.out.println("Wrote " + obligations.size() + " rows to " + path);
    }
}

package br.unb.cic.td18.verification;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.model.TwistedBraceletKey;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Independent local DFS and complete support enumeration for the three
 * restricted direct root-lifting families. No certificate or persisted seed
 * inventory is an input to this enumeration.
 */
public final class RestrictedOddPairVerifier {
    private static final int DOMAIN = 18;
    private static final int FULL = (1 << DOMAIN) - 1;
    private static final Cycle BETA = Cycle.of(IntStream.range(0, DOMAIN).toArray());
    private static final Map<Integer, List<Integer>> EXPONENTS = Map.of(
            5, List.of(3), 7, List.of(4), 9, List.of(5, 7),
            11, List.of(6), 13, List.of(7, 10, 11));
    private static final Map<Integer, Long> DFS_NODES = Map.of(
            5, 14L, 7, 80L, 9, 506L, 11, 3_391L, 13, 23_619L);
    private static final Map<Integer, List<CyclicTargetPair>> CACHE = new TreeMap<>();

    private RestrictedOddPairVerifier() { }

    record LocalClassification(long nodes, List<int[]> cycles) { }

    /**
     * The half-turn and qualifying criteria are necessary conditions from
     * the shared paper (cor:half-turn and lem:qualifying-criterion). Wrapped
     * triples are checked at leaves. Every survivor is additionally checked
     * by direct multiplication of every applicable local transposition.
     */
    static LocalClassification classify(final int length) {
        if (!EXPONENTS.containsKey(length)) {
            throw new IllegalArgumentException("Unsupported local cycle length");
        }
        final int[] path = new int[length];
        final boolean[] used = new boolean[length];
        used[0] = true;
        final var retained = new ArrayList<int[]>();
        final long[] nodes = {0};
        enumerate(path, used, 1, retained, nodes);
        return new LocalClassification(nodes[0], List.copyOf(retained));
    }

    private static void enumerate(final int[] path, final boolean[] used, final int depth,
                                  final List<int[]> retained, final long[] nodes) {
        nodes[0]++;
        final int n = path.length;
        if (depth == n) {
            if (Math.floorMod(-path[n - 1], n) < (n + 1) / 2) return;
            for (int start = 0; start < n; start++) {
                for (int offset = 2; offset < n; offset += 2) {
                    if (ordered(path[start], path[(start + 1) % n],
                            path[(start + offset) % n], n)) return;
                }
            }
            if (hasQualifyingTransposition(path)) {
                throw new IllegalStateException("Local classification retained a qualifying move");
            }
            retained.add(path.clone());
            return;
        }
        for (int next = 1; next < n; next++) {
            if (used[next] || Math.floorMod(next - path[depth - 1], n) < (n + 1) / 2) continue;
            path[depth] = next;
            boolean forbidden = false;
            for (int offset = 2; offset <= depth; offset += 2) {
                if (ordered(path[depth - offset], path[depth - offset + 1], next, n)) {
                    forbidden = true;
                    break;
                }
            }
            if (forbidden) continue;
            used[next] = true;
            enumerate(path, used, depth + 1, retained, nodes);
            used[next] = false;
        }
    }

    private static boolean ordered(final int a, final int b, final int c, final int n) {
        return Math.floorMod(b - a, n) < Math.floorMod(c - a, n);
    }

    /** Direct product C * tau^{-1}; the local current cycle is (0,...,k-1). */
    static boolean hasQualifyingTransposition(final int[] path) {
        final int n = path.length;
        final int[] omega = new int[n];
        for (int i = 0; i < n; i++) omega[path[i]] = path[(i + 1) % n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                for (int c = b + 1; c < n; c++) {
                    final int[] product = omega.clone();
                    product[a] = omega[c];
                    product[b] = omega[a];
                    product[c] = omega[b];
                    if (product[a] != a && product[b] != b && product[c] != c) continue;
                    final boolean[] seen = new boolean[n];
                    int oddCycles = 0;
                    for (int start = 0; start < n; start++) {
                        if (seen[start]) continue;
                        int length = 0;
                        for (int x = start; !seen[x]; x = product[x]) {
                            seen[x] = true;
                            length++;
                        }
                        oddCycles += length % 2;
                    }
                    if (oddCycles == 3) return true;
                }
            }
        }
        return false;
    }

    public static void verifyInventory() {
        for (final int length : List.of(5, 7, 9, 11, 13)) {
            final var classified = classify(length);
            final var actual = classified.cycles().stream().map(Arrays::toString).sorted().toList();
            final var expected = new ArrayList<String>();
            for (final int exponent : EXPONENTS.get(length)) {
                expected.add(Arrays.toString(IntStream.range(0, length)
                        .map(i -> i * exponent % length).toArray()));
            }
            expected.add(Arrays.toString(IntStream.range(0, length)
                    .map(i -> Math.floorMod(-i, length)).toArray()));
            expected.sort(String::compareTo);
            if (classified.nodes() != DFS_NODES.get(length) || !actual.equals(expected)) {
                throw new IllegalStateException("Incomplete local classification at length " + length);
            }
        }
        // Preserve the qualifying branch on either local support, including
        // both equal 9-cycle supports (one structural child partition each).
        final var expectedChildren = Map.of(
                13, List.of("[13, 3]", "[11, 5]", "[9, 5, 3]", "[7, 5, 5]"),
                11, List.of("[11, 5]", "[11, 3, 3]", "[9, 7]", "[7, 7, 3]", "[7, 5, 5]"),
                9, List.of("[9, 7]", "[9, 5, 3]"));
        long classes = 0;
        for (final int first : List.of(13, 11, 9)) {
            final int[] partition = {first, DOMAIN - first};
            final boolean[] oriented = {true, true};
            final var children = ReductionTypeEnumerator.selectedChildPartitions(partition, oriented, 1)
                    .stream().map(Arrays::toString).sorted().toList();
            if (!children.equals(expectedChildren.get(first).stream().sorted().toList())
                    || !ReductionTypeEnumerator.mayHaveNoAvailableReduction(partition, oriented, 1)) {
                throw new IllegalStateException("Restricted-family qualifying coverage changed");
            }
            classes += representatives(partition, oriented).size();
        }
        if (classes != 1_884) throw new IllegalStateException("Incomplete restricted odd-pair inventory");
        System.out.println("restricted odd-pair classification verified: candidateOrbits=4585 classes=1884 "
                + "rows=267/270/274 directRootBudget=10");
    }

    /** All supports, all independently classified powers, then candidate-orbit deduplication. */
    public static synchronized List<CyclicTargetPair> representatives(
            final int[] partition, final boolean[] oriented) {
        if (!RestrictedOddPair.isType(partition, oriented)) {
            throw new IllegalArgumentException("Expected a restricted odd-pair type");
        }
        final int first = Math.max(partition[0], partition[1]);
        if (CACHE.containsKey(first)) return CACHE.get(first);
        final int second = DOMAIN - first;
        final var candidates = new HashSet<TwistedBraceletKey>();
        final var pairs = new TreeMap<ExtendedToricClassKey, CyclicTargetPair>(
                Comparator.comparing(ExtendedToricClassKey::twistedBracelet));
        for (final int a : EXPONENTS.get(first)) {
            for (final int b : EXPONENTS.get(second)) {
                if (first == second && a > b) continue;
                for (int mask = 0; mask <= FULL; mask++) {
                    if (Integer.bitCount(mask) != first
                            || first == second && a == b && mask > (FULL ^ mask)) continue;
                    final var candidate = new CyclicTargetCandidate(new MulticyclePermutation(List.of(
                            cycle(mask, a), cycle(FULL ^ mask, b))), BETA);
                    if (!candidates.add(candidate.twistedBraceletKey())) continue;
                    if (!RestrictedOddPair.contains(candidate)) {
                        throw new IllegalStateException("Local powers disagree with rank progression model");
                    }
                    candidate.asCyclicTargetPair().ifPresent(pair -> pairs.put(
                            pair.extendedToricClassKey(), pair.canonicalRepresentative()));
                }
            }
        }
        if (candidates.size() != RestrictedOddPair.expectedCandidateOrbits(partition, oriented)
                || pairs.size() != RestrictedOddPair.expectedClasses(partition, oriented)) {
            throw new IllegalStateException("Independent restricted-family count mismatch");
        }
        final var direct = new HashSet<ExtendedToricClassKey>();
        DirectTwistedBraceletGenerator.generateRestrictedOddPairRepresentatives(partition, oriented, pair -> {
            if (!direct.add(pair.extendedToricClassKey())) {
                throw new IllegalStateException("Duplicate restricted direct representative");
            }
        });
        if (!direct.equals(pairs.keySet())) {
            throw new IllegalStateException("Restricted direct generator disagrees with support enumeration");
        }
        final var result = List.copyOf(pairs.values());
        CACHE.put(first, result);
        return result;
    }

    private static Cycle cycle(final int mask, final int exponent) {
        final int[] support = IntStream.range(0, DOMAIN)
                .filter(symbol -> (mask & 1 << symbol) != 0).toArray();
        return Cycle.of(IntStream.range(0, support.length)
                .map(index -> support[index * exponent % support.length]).toArray());
    }
}

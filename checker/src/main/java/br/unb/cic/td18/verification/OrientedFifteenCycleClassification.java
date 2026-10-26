package br.unb.cic.td18.verification;

import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.search.CommonOperations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Independently enumerates local oriented 15-cycles without a qualifying
 * transposition, extending the shared paper's half-turn and qualifying-move
 * criteria to the length omitted from its TD(16) classification.
 *
 * <p>Every algebraic cycle has one writing beginning with zero after the
 * restricted current cycle has been relabeled to {@code (0, ..., 14)}.
 * Both pruning conditions are necessary for the absence of a qualifying
 * transposition, so every such local configuration survives this search.
 * The retained oriented configuration is a candidate, not a cyclic-target
 * pair: its product has three cycles. Thus it cannot be cleared by any
 * sequence supported there, because that product is invariant under
 * supported transpositions and the restricted current cycle stays a cycle.
 */
final class OrientedFifteenCycleClassification {
    static final int CYCLE_LENGTH = 15;
    static final long EXPECTED_VISITED_NODES = 169_355L;
    private OrientedFifteenCycleClassification() {
    }

    record Result(long visitedNodes, int retainedCycles,
                  int unorientedCycles, List<CyclicTargetCandidate> orientedCandidates) {
        Result {
            orientedCandidates = List.copyOf(orientedCandidates);
        }
    }

    static Result classify() {
        final var path = new int[CYCLE_LENGTH];
        final var used = new boolean[CYCLE_LENGTH];
        path[0] = 0;
        used[0] = true;
        final var retained = new ArrayList<int[]>();
        final long[] visited = {0};
        enumerate(path, used, 1, retained, visited);

        final var beta = Cycle.of(
                IntStream.range(0, CYCLE_LENGTH).toArray());
        final var orientedCandidates = new ArrayList<CyclicTargetCandidate>();
        var unoriented = 0;
        for (final var symbols : retained) {
            final var cycle = Cycle.of(symbols);
            if (!CommonOperations.isOriented(beta, cycle)) {
                unoriented++;
                continue;
            }
            final var candidate = new CyclicTargetCandidate(
                    new MulticyclePermutation(cycle), beta);
            orientedCandidates.add(candidate.canonicalRepresentative());
        }
        orientedCandidates.sort(Comparator.comparing(
                CyclicTargetCandidate::twistedBraceletKey));
        return new Result(visited[0], retained.size(), unoriented,
                orientedCandidates);
    }

    static List<CyclicTargetCandidate> expectedOrientedCandidates() {
        final var beta = Cycle.of(IntStream.range(0, CYCLE_LENGTH).toArray());
        final var gamma = Cycle.of(
                0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7);
        return List.of(new CyclicTargetCandidate(
                new MulticyclePermutation(gamma), beta).canonicalRepresentative());
    }

    private static void enumerate(final int[] path,
                                  final boolean[] used,
                                  final int depth,
                                  final List<int[]> retained,
                                  final long[] visited) {
        visited[0]++;
        final int halfTurn = (path.length + 1) / 2;
        if (depth == path.length) {
            if (forwardDistance(path[path.length - 1], path[0], path.length)
                    >= halfTurn && !violatesCriterion(path)) {
                retained.add(path.clone());
            }
            return;
        }

        final int previous = path[depth - 1];
        for (var symbol = 1; symbol < path.length; symbol++) {
            if (used[symbol]
                    || forwardDistance(previous, symbol, path.length)
                    < halfTurn) {
                continue;
            }
            path[depth] = symbol;
            if (violatesDeterminedCriterion(path, depth)) {
                continue;
            }
            used[symbol] = true;
            enumerate(path, used, depth + 1, retained, visited);
            used[symbol] = false;
        }
    }

    private static boolean violatesDeterminedCriterion(final int[] path,
                                                       final int depth) {
        for (var exponent = 2; exponent <= depth; exponent += 2) {
            final int start = depth - exponent;
            if (inCanonicalCyclicOrder(path[start], path[start + 1],
                    path[depth], path.length)) {
                return true;
            }
        }
        return false;
    }

    private static boolean violatesCriterion(final int[] path) {
        for (var start = 0; start < path.length; start++) {
            for (var exponent = 2; exponent < path.length; exponent += 2) {
                if (inCanonicalCyclicOrder(path[start],
                        path[(start + 1) % path.length],
                        path[(start + exponent) % path.length],
                        path.length)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inCanonicalCyclicOrder(final int first,
                                                  final int second,
                                                  final int third,
                                                  final int modulus) {
        return forwardDistance(first, second, modulus)
                < forwardDistance(first, third, modulus);
    }

    private static int forwardDistance(final int from, final int to,
                                       final int modulus) {
        return Math.floorMod(to - from, modulus);
    }
}

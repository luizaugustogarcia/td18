package br.unb.cic.td18.verification;

import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.search.CommonOperations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Independent reproduction of the oriented-19-cycle depth-first search. */
final class OrientedNineteenCycleClassification {
    static final int CYCLE_LENGTH = 19;
    static final long EXPECTED_VISITED_NODES = 9_304_845L;
    private static final int[][] EXPECTED_ORIENTED_SURVIVORS = {
            {
                    0, 10, 1, 11, 2, 12, 3, 13, 4, 14,
                    5, 15, 6, 16, 7, 17, 8, 18, 9
            },
            {
                    0, 16, 13, 10, 7, 4, 1, 17, 14, 11,
                    8, 5, 2, 18, 15, 12, 9, 6, 3
            }
    };

    private OrientedNineteenCycleClassification() {
    }

    record Result(long visitedNodes, int retainedCycles,
                  int unorientedCycles, List<CyclicTargetPair> orientedPairs) {
        Result {
            orientedPairs = List.copyOf(orientedPairs);
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
        final var orientedPairs = new ArrayList<CyclicTargetPair>();
        var unoriented = 0;
        for (final var symbols : retained) {
            final var cycle = Cycle.of(symbols);
            if (!CommonOperations.isOriented(beta, cycle)) {
                unoriented++;
                continue;
            }
            final var candidate = new CyclicTargetCandidate(
                    new MulticyclePermutation(cycle), beta);
            orientedPairs.add(candidate.requireCyclicTargetPair()
                    .canonicalRepresentative());
        }
        orientedPairs.sort(Comparator.comparing(
                pair -> pair.extendedToricClassKey().twistedBracelet()));
        return new Result(visited[0], retained.size(), unoriented,
                orientedPairs);
    }

    static List<CyclicTargetPair> expectedOrientedPairs() {
        final var beta = Cycle.of(
                IntStream.range(0, CYCLE_LENGTH).toArray());
        final var result = new ArrayList<CyclicTargetPair>(
                EXPECTED_ORIENTED_SURVIVORS.length);
        for (final var symbols : EXPECTED_ORIENTED_SURVIVORS) {
            final var candidate = new CyclicTargetCandidate(
                    new MulticyclePermutation(Cycle.of(symbols)), beta);
            result.add(candidate.requireCyclicTargetPair()
                    .canonicalRepresentative());
        }
        result.sort(Comparator.comparing(
                pair -> pair.extendedToricClassKey().twistedBracelet()));
        return List.copyOf(result);
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

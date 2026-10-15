package br.unb.cic.td18.base;

import br.unb.cic.td18.ReductionTypeEnumerator;
import br.unb.cic.td18.permutation.Cycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Cyclic-order operations shared by canonicalization and witness replay. */
public final class CommonOperations {
    static final double CPU_PREFIX_MIN_RATE = 1.6;
    static final int CPU_PREFIX_MAX_DEPTH = 3;

    private CommonOperations() {
    }

    public static Cycle applyTranspositionOptimized(
            final Cycle beta,
            final Cycle move
    ) {
        final var indexes = new int[]{
                beta.indexOf(move.get(0)),
                beta.indexOf(move.get(1)),
                beta.indexOf(move.get(2))
        };
        java.util.Arrays.sort(indexes);
        final var result = new int[beta.size()];
        System.arraycopy(beta.getSymbols(), 0, result, 0, indexes[0]);
        System.arraycopy(beta.getSymbols(), indexes[1], result, indexes[0],
                indexes[2] - indexes[1]);
        System.arraycopy(beta.getSymbols(), indexes[0], result,
                indexes[0] + indexes[2] - indexes[1],
                indexes[1] - indexes[0]);
        System.arraycopy(beta.getSymbols(), indexes[2], result, indexes[2],
                beta.size() - indexes[2]);
        return Cycle.of(result);
    }

    public static Cycle[] cycleIndex(
            final Collection<Cycle> cycles,
            final Cycle beta
    ) {
        final var result = new Cycle[beta.size()];
        for (final var cycle : cycles) {
            for (final var symbol : cycle.getSymbols()) {
                result[symbol] = cycle;
            }
        }
        return result;
    }

    public static boolean areSymbolsInCyclicOrder(
            final Cycle cycle,
            final int... symbols
    ) {
        if (symbols.length <= 1) {
            return true;
        }
        final var indexes = cycle.getSymbolIndexes();
        var wraps = 0;
        for (var index = 0; index < symbols.length; index++) {
            final var current = indexes[symbols[index]];
            final var next = indexes[symbols[(index + 1) % symbols.length]];
            if (current > next && ++wraps > 1) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOriented(final Cycle beta, final Cycle cycle) {
        return !areSymbolsInCyclicOrder(
                beta.getInverse(), cycle.getSymbols());
    }

    /**
     * Finds a short prefix that creates fixed points quickly enough for the
     * known diameter of the contracted remainder to close the supplied move
     * budget. The returned moves are a prefix, not a complete sorting witness.
     *
     * <p>The input may already contain fixed points. They are kept as
     * first-class data, excluded from candidate moves, and are not counted as
     * gain. Thus, if the prefix creates {@code r} new fixed points, it is
     * accepted only when {@code r / depth >= 1.6} and
     * {@code depth + D(N-r) <= maximumMoves}, where {@code N} is the initial
     * number of nonfixed symbols and {@code D(M) = TD(M-1)}.</p>
     */
    public static Optional<List<int[]>> searchForSorting(
            final byte[] omega,
            final byte[] beta,
            final int maximumMoves
    ) {
        if (omega.length != beta.length) {
            throw new IllegalArgumentException(
                    "omega and beta must have equal lengths");
        }
        validatePermutation(omega, "omega");
        validatePermutation(beta, "beta");
        if (maximumMoves <= 0) {
            return Optional.empty();
        }

        final long started = System.nanoTime();
        try {
            final byte[] workingOmega = omega.clone();
            final byte[] workingBeta = beta.clone();
            final int initialFixedPoints = countFixedPoints(workingOmega);
            final var result = searchForSorting(
                    workingOmega, workingBeta, new ArrayList<>(),
                    initialFixedPoints, maximumMoves);
            CpuPrefixSearchStats.instance().completed(
                    result.map(List::size).orElse(0),
                    System.nanoTime() - started);
            return result;
        } catch (final RuntimeException | Error throwable) {
            CpuPrefixSearchStats.instance().failed(
                    System.nanoTime() - started);
            throw throwable;
        }
    }

    private static Optional<List<int[]>> searchForSorting(
            final byte[] omega,
            final byte[] beta,
            final List<int[]> stack,
            final int initialFixedPoints,
            final int maximumMoves
    ) {
        final int fixedBefore = countFixedPoints(omega);
        final int candidateDepth = stack.size() + 1;

        for (var i = 0; i < beta.length - 2; i++) {
            final int a = Byte.toUnsignedInt(beta[i]);
            if (Byte.toUnsignedInt(omega[a]) == a) {
                continue;
            }

            for (var j = i + 1; j < beta.length - 1; j++) {
                final int b = Byte.toUnsignedInt(beta[j]);
                if (Byte.toUnsignedInt(omega[b]) == b) {
                    continue;
                }

                for (var k = j + 1; k < beta.length; k++) {
                    final int c = Byte.toUnsignedInt(beta[k]);
                    if (Byte.toUnsignedInt(omega[c]) == c) {
                        continue;
                    }

                    final byte imageA = omega[a];
                    final byte imageB = omega[b];
                    final byte imageC = omega[c];
                    var newlyFixedByCandidate = 0;
                    if (Byte.toUnsignedInt(imageC) == a) {
                        newlyFixedByCandidate++;
                    }
                    if (Byte.toUnsignedInt(imageA) == b) {
                        newlyFixedByCandidate++;
                    }
                    if (Byte.toUnsignedInt(imageB) == c) {
                        newlyFixedByCandidate++;
                    }

                    final int fixedAfter = fixedBefore + newlyFixedByCandidate;
                    final int createdFixedPoints =
                            fixedAfter - initialFixedPoints;
                    final int remainingSymbols = omega.length - fixedAfter;
                    final int[] move = {a, b, c};

                    if (qualifies(createdFixedPoints, candidateDepth,
                            remainingSymbols, maximumMoves)) {
                        final var result = copyMoves(stack);
                        result.add(move);
                        return Optional.of(List.copyOf(result));
                    }

                    if (!canStillQualify(createdFixedPoints, candidateDepth,
                            remainingSymbols, maximumMoves)) {
                        continue;
                    }

                    omega[a] = imageC;
                    omega[b] = imageA;
                    omega[c] = imageB;
                    stack.add(move);
                    final var result = searchForSorting(
                            omega, applyTransposition(beta, i, j, k), stack,
                            initialFixedPoints, maximumMoves);
                    stack.removeLast();
                    omega[a] = imageA;
                    omega[b] = imageB;
                    omega[c] = imageC;
                    if (result.isPresent()) {
                        return result;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean qualifies(final int createdFixedPoints,
                                     final int depth,
                                     final int remainingSymbols,
                                     final int maximumMoves) {
        if (createdFixedPoints < Math.ceil(CPU_PREFIX_MIN_RATE * depth)) {
            return false;
        }
        final int remainingDiameter =
                ReductionTypeEnumerator.knownDiameter(remainingSymbols);
        return remainingDiameter != Integer.MAX_VALUE
                && depth + remainingDiameter <= maximumMoves;
    }

    private static boolean canStillQualify(final int createdFixedPoints,
                                           final int depth,
                                           final int remainingSymbols,
                                           final int maximumMoves) {
        final int availableDepth = CPU_PREFIX_MAX_DEPTH - depth;
        for (var additionalMoves = 1;
             additionalMoves <= availableDepth;
             additionalMoves++) {
            final int futureDepth = depth + additionalMoves;
            final int futureCreated = createdFixedPoints
                    + Math.min(remainingSymbols, 3 * additionalMoves);
            final int futureRemaining = Math.max(
                    0, remainingSymbols - 3 * additionalMoves);
            if (futureCreated
                    < Math.ceil(CPU_PREFIX_MIN_RATE * futureDepth)) {
                continue;
            }
            final int diameter =
                    ReductionTypeEnumerator.knownDiameter(futureRemaining);
            if (diameter != Integer.MAX_VALUE
                    && futureDepth + diameter <= maximumMoves) {
                return true;
            }
        }
        return false;
    }

    static byte[] applyTransposition(final byte[] beta,
                                     final int i,
                                     final int j,
                                     final int k) {
        final byte[] result = Arrays.copyOf(beta, beta.length);
        System.arraycopy(beta, j, result, i, k - j);
        System.arraycopy(beta, i, result, i + k - j, j - i);
        return result;
    }

    static int countFixedPoints(final byte[] permutation) {
        var result = 0;
        for (var symbol = 0; symbol < permutation.length; symbol++) {
            if (Byte.toUnsignedInt(permutation[symbol]) == symbol) {
                result++;
            }
        }
        return result;
    }

    private static ArrayList<int[]> copyMoves(final List<int[]> moves) {
        final var result = new ArrayList<int[]>(moves.size() + 1);
        for (final var move : moves) {
            result.add(move.clone());
        }
        return result;
    }

    private static void validatePermutation(final byte[] permutation,
                                            final String name) {
        final var seen = new boolean[permutation.length];
        for (final byte value : permutation) {
            final int symbol = Byte.toUnsignedInt(value);
            if (symbol >= permutation.length || seen[symbol]) {
                throw new IllegalArgumentException(
                        name + " must be a permutation of [0, N)");
            }
            seen[symbol] = true;
        }
    }
}

package br.unb.cic.td18.model;

/**
 * The invariant no-qualifying-transposition families of rows 267, 270 and 274.
 *
 * <p>These are fixed-point-free candidates on 18 symbols with two oriented
 * odd-length cycles. Membership does not establish target realizability.
 * Their one-move obligations are lifted to roots, where the full budget of
 * ten transpositions applies. The local classification and exhaustive count
 * audits are specified in {@code docs/td18-reduction-count.tex}.</p>
 */
public final class RestrictedOddPair {
    private RestrictedOddPair() {
    }

    public static boolean isType(final int[] partition, final boolean[] oriented) {
        if (partition == null || oriented == null || partition.length != 2
                || oriented.length != 2 || !oriented[0] || !oriented[1]) {
            return false;
        }
        final int smaller = Math.min(partition[0], partition[1]);
        final int larger = Math.max(partition[0], partition[1]);
        return smaller == 5 && larger == 13
                || smaller == 7 && larger == 11
                || smaller == 9 && larger == 9;
    }

    public static boolean isObligation(final int[] partition, final boolean[] oriented,
                                       final int movesUsed) {
        return movesUsed == 1 && isType(partition, oriented);
    }

    public static long expectedClasses(final int[] partition, final boolean[] oriented) {
        requireType(partition, oriented);
        return switch (Math.max(partition[0], partition[1])) {
            case 13 -> 391L;
            case 11 -> 321L;
            default -> 1_172L;
        };
    }

    public static long expectedCandidateOrbits(final int[] partition, final boolean[] oriented) {
        requireType(partition, oriented);
        return switch (Math.max(partition[0], partition[1])) {
            case 13 -> 756L;
            case 11 -> 912L;
            default -> 2_917L;
        };
    }

    /** Zero-based cyclic-rank steps in induced current-cycle order. */
    public static int[] allowedRankSteps(final int cycleLength) {
        return switch (cycleLength) {
            case 5, 7, 11 -> new int[]{2};
            case 9 -> new int[]{2, 4};
            case 13 -> new int[]{2, 4, 6};
            default -> throw new IllegalArgumentException(
                    "No restricted odd-pair progression for length " + cycleLength);
        };
    }

    /**
     * Tests both induced local orders and rejects extra cycles or fixed points.
     * Twisted reflection reverses positions and inverts ranks, preserving the
     * allowed progressions; ordinary position reversal is not this action.
     */
    public static boolean contains(final CyclicTargetCandidate candidate) {
        if (candidate == null || candidate.getBeta().size() != 18) {
            return false;
        }
        final var word = candidate.standardizedEncodingWord();
        final int[] sizes = new int[2];
        final int[] occurrences = new int[2];
        final int[] steps = new int[2];
        for (var position = 0; position < word.length(); position++) {
            final int cycle = word.cycleLabelAt(position) - 1;
            if (cycle < 0 || cycle >= 2 || word.rankAt(position) == 0) {
                return false;
            }
            sizes[cycle]++;
        }
        if (!isType(sizes, new boolean[]{true, true})) {
            return false;
        }
        for (var position = 0; position < word.length(); position++) {
            final int cycle = word.cycleLabelAt(position) - 1;
            final int rank = word.rankAt(position) - 1;
            final int occurrence = occurrences[cycle]++;
            if (occurrence == 1) {
                steps[cycle] = rank;
                var allowed = false;
                for (final int step : allowedRankSteps(sizes[cycle])) {
                    allowed |= rank == step;
                }
                if (!allowed) {
                    return false;
                }
            }
            if (rank != steps[cycle] * occurrence % sizes[cycle]) {
                return false;
            }
        }
        return true;
    }

    private static void requireType(final int[] partition, final boolean[] oriented) {
        if (!isType(partition, oriented)) {
            throw new IllegalArgumentException(
                    "Expected restricted oriented odd pair (13,5), (11,7) or (9,9)");
        }
    }
}

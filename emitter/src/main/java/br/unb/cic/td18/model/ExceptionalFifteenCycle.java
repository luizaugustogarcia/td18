package br.unb.cic.td18.model;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.reduction.AnnotatedPartitions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** The local exceptional 15-cycle candidate; its target has three cycles. */
public final class ExceptionalFifteenCycle {
    public static final int CYCLE_LENGTH = 15;

    private ExceptionalFifteenCycle() {
    }

    public static CyclicTargetCandidate candidate() {
        return new CyclicTargetCandidate(
                new MulticyclePermutation(Cycle.of(0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7)),
                Cycle.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14));
    }

    /**
     * Complete restricted companion obligations for the two-reduction route.
     * An oriented 3-cycle companion at depth one clears immediately and needs
     * no separate exceptional-family search.
     */
    public static List<Obligation> obligations() {
        return List.of(
                new Obligation(new AnnotatedPartitions.AnnotatedPartition(
                        new int[]{15, 2, 2}, new boolean[]{true, false, false}), 19, 0, 10),
                new Obligation(new AnnotatedPartitions.AnnotatedPartition(
                        new int[]{15, 3}, new boolean[]{true, false}), 18, 1, 9));
    }

    public record Obligation(AnnotatedPartitions.AnnotatedPartition type,
                             int domain, int movesUsed, int budget) {
        public Obligation {
            Objects.requireNonNull(type, "type");
            final int[] sizes = type.partition();
            final boolean[] oriented = type.oriented();
            if (sizes.length != oriented.length || Arrays.stream(sizes).sum() != domain
                    || Arrays.stream(sizes).anyMatch(size -> size < 2)
                    || movesUsed < 0 || movesUsed > 1 || budget != 10 - movesUsed) {
                throw new IllegalArgumentException("Inconsistent exceptional 15-cycle obligation");
            }
            var fifteenCount = 0;
            for (var index = 0; index < sizes.length; index++) {
                if (sizes[index] == CYCLE_LENGTH) {
                    if (!oriented[index]) {
                        throw new IllegalArgumentException("The exceptional 15-cycle must be oriented");
                    }
                    fifteenCount++;
                }
            }
            if (fifteenCount != 1) {
                throw new IllegalArgumentException("Expected a unique exceptional 15-cycle");
            }
        }

        public String key() {
            return typeKey(type) + "@" + movesUsed;
        }
    }

    public static String typeKey(final AnnotatedPartitions.AnnotatedPartition type) {
        final int[] sizes = type.partition();
        final boolean[] oriented = type.oriented();
        final var result = new StringJoiner("-");
        for (var index = 0; index < sizes.length; index++) {
            result.add(sizes[index] + (oriented[index] ? "O" : "U"));
        }
        return result.toString();
    }

    /** Tests the induced cyclic order without deleting companions or fixed points. */
    public static boolean contains(final CyclicTargetCandidate candidate) {
        final var word = candidate.standardizedEncodingWord();
        final int[] sizes = new int[word.length() + 1];
        for (var position = 0; position < word.length(); position++) {
            sizes[word.cycleLabelAt(position)]++;
        }
        for (var label = 1; label < sizes.length; label++) {
            if (sizes[label] != CYCLE_LENGTH) {
                continue;
            }
            var occurrence = 0;
            var exceptional = true;
            for (var position = 0; position < word.length(); position++) {
                if (word.cycleLabelAt(position) == label) {
                    exceptional &= word.rankAt(position) == (2 * occurrence++) % CYCLE_LENGTH + 1;
                }
            }
            if (exceptional) {
                return true;
            }
        }
        return false;
    }
}

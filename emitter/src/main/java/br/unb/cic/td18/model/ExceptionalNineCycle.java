package br.unb.cic.td18.model;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.reduction.AnnotatedPartitions;
import br.unb.cic.td18.reduction.Partitions;

import java.util.List;

/** The local exceptional candidate; its target is not itself a full cycle. */
public final class ExceptionalNineCycle {
    public static final int DOMAIN = 19;
    public static final int MAXIMUM_MOVES = 10;

    private ExceptionalNineCycle() {
    }

    public static CyclicTargetCandidate candidate() {
        return new CyclicTargetCandidate(
                new MulticyclePermutation(Cycle.of(0, 5, 1, 6, 2, 7, 3, 8, 4)),
                Cycle.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
    }

    /** All 18 root annotations, including every orientation of the companions. */
    public static List<AnnotatedPartitions.AnnotatedPartition> rootTypes() {
        return Partitions.generateValidPartitions(DOMAIN).stream()
                .filter(partition -> java.util.Arrays.stream(partition).anyMatch(size -> size == 9))
                .flatMap(partition -> AnnotatedPartitions.of(partition).stream())
                .filter(type -> {
                    final var sizes = type.partition();
                    final var oriented = type.oriented();
                    for (var index = 0; index < sizes.length; index++) {
                        if (sizes[index] == 9) {
                            return oriented[index];
                        }
                    }
                    return false;
                }).toList();
    }

    public static String typeKey(final AnnotatedPartitions.AnnotatedPartition type) {
        final var sizes = type.partition();
        final var oriented = type.oriented();
        final var key = new java.util.StringJoiner("-");
        for (var index = 0; index < sizes.length; index++) {
            key.add(sizes[index] + (oriented[index] ? "O" : "U"));
        }
        return key.toString();
    }

    /** Tests the induced cyclic order without discarding companion or fixed-point data. */
    public static boolean contains(final CyclicTargetCandidate pair) {
        final var word = pair.standardizedEncodingWord();
        final var sizes = new int[word.length() + 1];
        for (var position = 0; position < word.length(); position++) {
            sizes[word.cycleLabelAt(position)]++;
        }
        for (var label = 1; label < sizes.length; label++) {
            if (sizes[label] != 9) {
                continue;
            }
            var occurrence = 0;
            var exceptional = true;
            for (var position = 0; position < word.length(); position++) {
                if (word.cycleLabelAt(position) == label) {
                    exceptional &= word.rankAt(position) == (2 * occurrence++) % 9 + 1;
                }
            }
            if (exceptional) {
                return true;
            }
        }
        return false;
    }
}

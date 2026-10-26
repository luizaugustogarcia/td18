package br.unb.cic.td18.verification;

import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.permutation.PermutationGroups;
import br.unb.cic.td18.search.CommonOperations;

import java.util.Arrays;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientedNineteenCycleClassificationTest {
    @Test
    void bothOrientedSurvivorsHaveTenMoveClearingSequences() {
        // Explicit sequences in docs/KNOWLEDGE.md, independent of the emitter.
        final int[][][] clearingSequences = {
                {{0, 1, 9}, {2, 9, 11}, {9, 8, 18}, {8, 12, 17},
                        {13, 17, 3}, {17, 16, 7}, {16, 4, 6}, {5, 6, 14},
                        {1, 6, 10}, {6, 10, 15}},
                {{0, 1, 3}, {1, 2, 4}, {2, 3, 5}, {3, 10, 12},
                        {11, 8, 14}, {12, 7, 15}, {14, 5, 17}, {10, 16, 13},
                        {9, 6, 3}, {15, 4, 18}}
        };
        final var beta = Cycle.of(IntStream.range(0, 19).toArray());
        for (var index = 0; index < clearingSequences.length; index++) {
            final int exponent = index == 0 ? 10 : 16;
            final var cycle = Cycle.of(IntStream.range(0, 19)
                    .map(symbol -> symbol * exponent % 19).toArray());
            final var pair = CyclicTargetPair.of(new MulticyclePermutation(cycle), beta);
            final var moves = Arrays.stream(clearingSequences[index])
                    .map(symbols -> Cycle.of(symbols.clone())).toList();
            assertEquals(10, moves.size());
            CertificateValidator.validate(pair, moves, 10);

            var omega = pair.getOmega();
            for (var step = 0; step < moves.size(); step++) {
                omega = PermutationGroups.computeProduct(omega, moves.get(step).getInverse());
                final long oddLengthCycles = omega.stream()
                        .filter(part -> part.size() % 2 == 1).count();
                assertEquals(1 + 2 * step, oddLengthCycles);
            }
            assertTrue(omega.isIdentity());
        }
    }

    @Test
    void neitherOrientedSurvivorAdmitsAQualifyingTwoMove() {
        final var beta = Cycle.of(IntStream.range(0, 19).toArray());
        for (final int exponent : new int[]{10, 16}) {
            final var cycle = Cycle.of(IntStream.range(0, 19)
                    .map(index -> index * exponent % 19).toArray());
            assertTrue(CommonOperations.isOriented(beta, cycle));
            final var pair = CyclicTargetPair.of(new MulticyclePermutation(cycle), beta);
            var applicable = 0;
            var twoMoves = 0;
            var qualifying = 0;
            for (var first = 0; first < 19; first++) {
                for (var second = first + 1; second < 19; second++) {
                    for (var third = second + 1; third < 19; third++) {
                        applicable++;
                        final var move = Cycle.of(first, second, third);
                        final var after = PermutationGroups.computeProduct(
                                pair.getOmega(), move.getInverse());
                        final long oddLengthCycles = after.stream()
                                .filter(part -> part.size() % 2 == 1).count();
                        if (oddLengthCycles == 3) {
                            twoMoves++;
                            if (after.stream().anyMatch(part -> part.size() == 1)) {
                                qualifying++;
                            }
                        }
                    }
                }
            }
            assertEquals(969, applicable);
            assertEquals(exponent == 10 ? 0 : 57, twoMoves);
            assertEquals(0, qualifying);
        }
    }

    @Test
    void reproducesTheCompleteLengthNineteenClassification() {
        final var result = OrientedNineteenCycleClassification.classify();

        assertEquals(9_304_845, result.visitedNodes());
        assertEquals(3, result.retainedCycles());
        assertEquals(1, result.unorientedCycles());
        assertEquals(2, result.orientedPairs().size());
        assertEquals(OrientedNineteenCycleClassification
                        .expectedOrientedPairs().stream()
                        .map(pair -> pair.extendedToricClassKey()).toList(),
                result.orientedPairs().stream()
                        .map(pair -> pair.extendedToricClassKey()).toList());
    }
}

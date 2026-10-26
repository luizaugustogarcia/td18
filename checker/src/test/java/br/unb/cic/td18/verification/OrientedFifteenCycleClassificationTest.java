package br.unb.cic.td18.verification;

import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.permutation.PermutationGroups;
import br.unb.cic.td18.search.CommonOperations;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientedFifteenCycleClassificationTest {
    @Test
    void reproducesTheCompleteLengthFifteenClassification() {
        final var result = OrientedFifteenCycleClassification.classify();

        assertEquals(169_355, result.visitedNodes());
        assertEquals(2, result.retainedCycles());
        assertEquals(1, result.unorientedCycles());
        assertEquals(1, result.orientedCandidates().size());
        assertEquals(OrientedFifteenCycleClassification
                        .expectedOrientedCandidates().stream()
                        .map(CyclicTargetCandidate::twistedBraceletKey).toList(),
                result.orientedCandidates().stream()
                        .map(CyclicTargetCandidate::twistedBraceletKey).toList());
        final var key = result.orientedCandidates().getFirst()
                .twistedBraceletKey().canonicalWord();
        for (var position = 0; position < 15; position++) {
            assertEquals(1, key.cycleLabelAt(position));
            assertEquals((2 * position) % 15 + 1, key.rankAt(position));
        }
    }

    @Test
    void localExceptionHasNoQualifyingMoveAndCannotHaveASupportClearingSequence() {
        final var beta = Cycle.of(IntStream.range(0, 15).toArray());
        final var local = new CyclicTargetCandidate(
                new MulticyclePermutation(exceptionalCycle()), beta);
        assertTrue(CommonOperations.isOriented(beta, exceptionalCycle()));
        assertFalse(local.formsCyclicTargetPair());
        assertEquals(new MulticyclePermutation(List.of(
                Cycle.of(0, 9, 3, 12, 6), Cycle.of(1, 10, 4, 13, 7),
                Cycle.of(2, 11, 5, 14, 8))), local.getProduct());

        var applicable = 0;
        for (var first = 0; first < 15; first++) {
            for (var second = first + 1; second < 15; second++) {
                for (var third = second + 1; third < 15; third++) {
                    applicable++;
                    final var move = Cycle.of(first, second, third);
                    final var after = PermutationGroups.computeProduct(
                            true, 15, local.getOmega(), move.getInverse());
                    assertEquals(1, after.stream()
                            .filter(cycle -> cycle.size() % 2 == 1).count());
                    final var currentAfter = PermutationGroups.computeProduct(
                            true, 15, move, beta);
                    assertEquals(1, currentAfter.size());
                    assertEquals(15, currentAfter.asNCycle().size());
                    // The invariant rules out clearing at every sequence length:
                    // the resulting identity would equate a current cycle with
                    // this three-cycle local target.
                    assertEquals(local.getProduct(), PermutationGroups.computeProduct(
                            true, 15, after, currentAfter));
                }
            }
        }
        assertEquals(455, applicable);
    }

    @Test
    void exceptionHasARealizableUnorientedThreeCompanionInterleaving() {
        final var beta = Cycle.of(
                0, 15, 1, 16, 2, 17, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        final var companion = Cycle.of(15, 17, 16);
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(List.of(exceptionalCycle(), companion)), beta);
        assertEquals(Cycle.of(
                0, 17, 11, 5, 14, 8, 2, 16, 10, 4, 13, 7, 1, 15, 9, 3, 12, 6),
                pair.getRho());
        assertTrue(CommonOperations.isOriented(beta, exceptionalCycle()));
        assertFalse(CommonOperations.isOriented(beta, companion));
        assertTrue(pair.getOmega().stream().noneMatch(cycle -> cycle.size() == 1));
        assertEquals(18, pair.getBeta().size());

        final var support = Arrays.stream(beta.getSymbols())
                .filter(symbol -> symbol < 15).toArray();
        assertEquals(Cycle.of(IntStream.range(0, 15).toArray()), Cycle.of(support));
        final var local = new CyclicTargetCandidate(
                new MulticyclePermutation(exceptionalCycle()), Cycle.of(support));
        assertEquals(OrientedFifteenCycleClassification.expectedOrientedCandidates()
                .getFirst().twistedBraceletKey(), local.twistedBraceletKey());
    }

    private static Cycle exceptionalCycle() {
        return Cycle.of(0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7);
    }
}

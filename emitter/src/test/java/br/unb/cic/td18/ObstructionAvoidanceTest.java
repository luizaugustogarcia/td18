package br.unb.cic.td18;

import br.unb.cic.td18.base.CommonOperations;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.ResidualObligation;
import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstructionAvoidanceTest {
    private static CyclicTargetPair controlResidual() {
        final var omega = new MulticyclePermutation(List.of(
                Cycle.of(0, 11, 13, 12, 2, 4, 9, 5, 7, 3, 14),
                Cycle.of(1, 10, 8, 15, 6)));
        final var beta = Cycle.of(0, 6, 5, 15, 14, 8, 4, 9,
                1, 12, 11, 13, 3, 2, 7, 10);
        for (final var cycle : omega) {
            assertTrue(CommonOperations.isOriented(beta, cycle));
        }
        return CyclicTargetPair.of(omega, beta).canonicalRepresentative();
    }

    @Test
    void enumeratesBothRestorationSizesBeforeReachabilityFiltering() {
        final var batch = ObstructionAvoidance.reconstructParents(
                controlResidual(), 8);

        assertEquals(6_272, batch.applications());
        assertFalse(batch.parents().isEmpty());
        assertTrue(batch.parents().stream().allMatch(pair ->
                pair.getBeta().size() == 17 || pair.getBeta().size() == 18));
        final var rootBatch = batch.parents().stream()
                .map(parent -> ObstructionAvoidance.reconstructParents(parent, 9))
                .filter(candidate -> !candidate.parents().isEmpty())
                .findFirst().orElseThrow();
        assertTrue(rootBatch.parents().stream().allMatch(pair ->
                pair.getBeta().size() == ReductionTypeEnumerator.ROOT_SYMBOLS));
    }

    @Test
    void closesTerminalMissesThroughIndependentlySortedPreimages() {
        final var residual = new ResidualObligation(
                130, 8, controlResidual());
        final var persistedMisses = new ConcurrentLinkedQueue<>();

        final var result = ObstructionAvoidance.resolve(List.of(residual),
                (pair, rate, maximumMoves) -> {
                    assertEquals(10, maximumMoves);
                    assertEquals(ReductionTypeEnumerator.ROOT_SYMBOLS,
                            pair.getBeta().size());
                    return WitnessSearch.SearchResult.triviallySettled();
                }, persistedMisses::add);

        assertTrue(result.complete());
        assertEquals(1, result.terminalResiduals());
        assertEquals(1_733_030, result.reconstructionApplications());
        assertEquals(623_283, result.searchObligations());
        assertEquals(result.searchObligations(), result.trivialClosures());
        assertEquals(0, result.certificates());
        assertTrue(persistedMisses.isEmpty());
    }

    @Test
    void rejectsAResidualAssignedToTheWrongTableRow() {
        final var residual = new ResidualObligation(
                129, 8, controlResidual());

        assertThrows(IllegalArgumentException.class,
                () -> ObstructionAvoidance.resolve(List.of(residual),
                        (pair, rate, moves) ->
                                WitnessSearch.SearchResult.triviallySettled(),
                        miss -> { }));
    }
}

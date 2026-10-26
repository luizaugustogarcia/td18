package br.unb.cic.td18.fixtures;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.search.CommonOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Existing control obstruction shared by reconstruction and checker tests. */
public final class AvoidanceFixtures {
    private AvoidanceFixtures() { }

    public static CyclicTargetPair controlResidual() {
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
}

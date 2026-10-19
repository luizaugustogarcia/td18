package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidualObligationTest {
    @Test
    void roundTripsAResidualAndItsChecksum() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var obligation = new ResidualObligation(
                130, "(3-O)", 8, pair);

        final var restored = ResidualObligation.decode(
                obligation.key(), obligation.value());

        assertEquals(obligation, restored);
        assertEquals(obligation.checksumContribution(),
                restored.checksumContribution());
    }
}

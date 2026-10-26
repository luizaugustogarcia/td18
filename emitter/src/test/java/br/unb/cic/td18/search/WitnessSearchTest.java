package br.unb.cic.td18.search;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitnessSearchTest {
    @Test
    void qualifyingCpuPrefixSettlesWithoutGpuOrCertificateWriter() {
        final var result = WitnessSearch.search(
                CyclicTargetPair.of(
                        new MulticyclePermutation(
                                "(0 1 2)(3 4 5)"),
                        Cycle.of(0, 1, 2, 3, 4, 5)),
                2.0f, 2);

        assertEquals(WitnessSearch.Disposition.TRIVIALLY_SETTLED,
                result.disposition());
        assertTrue(result.moves().isEmpty());
    }

    @Test
    void intermediatePrefixMissReturnsWithoutInitializingCuda() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(
                        "(0 1 2 3 4 7 5 16 6)(8 15 13 11 9 17 14 12 10)"),
                Cycle.of(0, 1, 2, 3, 4, 5, 6, 7, 8,
                        9, 10, 11, 12, 13, 14, 15, 16, 17));

        final var result = WitnessSearch.screenIntermediatePrefix(pair, 9);

        assertEquals(WitnessSearch.Disposition.RESIDUAL,
                result.disposition());
        assertTrue(result.moves().isEmpty());
    }
}

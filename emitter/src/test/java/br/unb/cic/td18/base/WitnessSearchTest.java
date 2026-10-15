package br.unb.cic.td18.base;

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
}

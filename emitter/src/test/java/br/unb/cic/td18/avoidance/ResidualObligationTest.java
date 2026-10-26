package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidualObligationTest {
    @Test
    void usesOnlyTheRawInstanceKey() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var obligation = new ResidualObligation(130, 8, pair);

        assertArrayEquals(CertificateCodec.keyFor(
                pair.canonicalRepresentative()), obligation.key());
        assertEquals(CertificateCodec.classKeyChecksumContribution(
                        pair.canonicalRepresentative()),
                obligation.checksumContribution());
    }
}

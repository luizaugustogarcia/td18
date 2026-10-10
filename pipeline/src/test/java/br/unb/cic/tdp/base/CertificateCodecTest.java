package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CertificateCodecTest {
    @Test
    void classKeyRoundTripsItsCanonicalRepresentative() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var canonical = pair.canonicalRepresentative();

        final var restored = CertificateCodec.pairForKey(
                CertificateCodec.keyFor(canonical), 3);

        assertEquals(canonical.extendedToricClassKey(),
                restored.extendedToricClassKey());
    }

    @Test
    void witnessTriplesRoundTrip() {
        final var moves = List.of(
                Cycle.of(0, 4, 7),
                Cycle.of(1, 3, 8),
                Cycle.of(2, 5, 6));

        assertEquals(moves,
                CertificateCodec.decodeValue(CertificateCodec.valueFor(moves)));
    }
}

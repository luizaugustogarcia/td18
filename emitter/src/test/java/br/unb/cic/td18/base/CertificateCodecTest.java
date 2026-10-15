package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void rejectsAnotherCertificateFormatVersion() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var key = CertificateCodec.keyFor(pair);
        key[1]++;
        final var value = CertificateCodec.valueFor(List.of(Cycle.of(0, 1, 2)));
        value[0]++;

        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.pairForKey(key, 3));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.decodeValue(value));
    }
}

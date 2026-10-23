package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AvoidanceRecordsTest {
    private static CyclicTargetPair rootPair() {
        final int[] symbols = IntStream.range(0, 19).toArray();
        return CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(symbols)),
                Cycle.of(symbols));
    }

    @Test
    void avoidanceSearchMissUsesOnlyTheRawInstanceKey() {
        final var miss = new AvoidanceSearchMiss(10, rootPair());

        assertArrayEquals(CertificateCodec.keyFor(
                rootPair().canonicalRepresentative()), miss.key());
    }

    @Test
    void rejectsAnIntermediateDomainAvoidanceMiss() {
        final var intermediate = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));

        assertThrows(IllegalArgumentException.class,
                () -> new AvoidanceSearchMiss(9, intermediate));
        assertThrows(IllegalArgumentException.class,
                () -> new AvoidanceSearchMiss(10, intermediate));
    }

    @Test
    void roundTripsAnAvoidancePhaseManifest() {
        final var manifest = new AvoidancePhaseManifest(1, 204,
                2_036, 10_000, 60, 10, 50, 20, 80, 0, 1234, true);

        assertEquals(manifest, AvoidancePhaseManifest.decode(
                manifest.encoded()));
    }

    @Test
    void rejectsManifestsWithInsertionHistoryApplicationCounts() {
        final var manifest = new AvoidancePhaseManifest(1, 204,
                2_036, 10_000, 60, 10, 50, 20, 80, 0, 1234, true);
        final byte[] legacy = manifest.encoded();
        assertEquals(4, Byte.toUnsignedInt(legacy[1]));
        legacy[1] = 3;

        assertThrows(IllegalArgumentException.class,
                () -> AvoidancePhaseManifest.decode(legacy));
    }
}

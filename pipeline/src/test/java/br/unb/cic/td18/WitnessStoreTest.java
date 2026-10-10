package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitnessStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReplaysCanonicalWitness() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var moves = List.of(Cycle.of(0, 1, 2));
        final var path = temporaryDirectory.resolve("witnesses");

        try (final var store = new WitnessStore(path, true)) {
            store.put(pair, moves);
            assertTrue(store.contains(pair));
        }

        try (final var store = new WitnessStore(path, false)) {
            assertEquals(moves, store.find(pair, 1).orElseThrow());
        }
    }
}

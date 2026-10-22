package br.unb.cic.td18;

import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvoidancePreimageStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deduplicatesClassesAndPersistsWaveInventories() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var directory = temporaryDirectory.resolve("work");
        AvoidancePreimageStore.StateInventory source;
        final var seen = new ArrayList<CyclicTargetPair>();

        try (final var store = new AvoidancePreimageStore(directory)) {
            store.putStates(List.of(
                    new AvoidancePreimageStore.State(8, pair),
                    new AvoidancePreimageStore.State(8, pair)));
            source = store.inventory(8);
            assertEquals(1, source.count());
            assertEquals(1, store.scanBatches(8, 1, seen::addAll));
            assertEquals(1, seen.size());
            store.requireOrRecordSource(source);

            final var output = store.inventory(9);
            store.recordWave(new AvoidancePreimageStore.WaveManifest(
                    8, source, 17, output));
        }

        try (final var reopened = new AvoidancePreimageStore(directory)) {
            reopened.requireOrRecordSource(source);
            assertEquals(source, reopened.inventory(8));
            final var wave = reopened.waveManifest(8).orElseThrow();
            assertEquals(17, wave.reconstructionApplications());
            assertEquals(0, wave.output().count());
        }
    }

    @Test
    void processesRecordedObstructionsAcrossTheWholeWavePool() {
        final var beta = Cycle.of(0, 1, 2);
        final var identity = CyclicTargetPair.of(
                new MulticyclePermutation(), beta).canonicalRepresentative();
        final var threeCycle = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), beta)
                .canonicalRepresentative();

        try (final var store = new AvoidancePreimageStore(
                temporaryDirectory.resolve("parallel-work"))) {
            store.putPairs(8, List.of(identity, threeCycle));
            final var result = ObstructionAvoidanceRunner.reconstructWave(
                    store, 8, 2);

            assertEquals(2, result.processed());
            assertEquals(Math.min(2, Math.max(1,
                            Runtime.getRuntime().availableProcessors())),
                    result.parallelism());
            assertEquals(0, store.inventory(9).count());
        }
    }
}

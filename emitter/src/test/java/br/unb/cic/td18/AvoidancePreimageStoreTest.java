package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            store.putHardStates(List.of(
                    new AvoidancePreimageStore.State(8, pair),
                    new AvoidancePreimageStore.State(8, pair)));
            source = store.inventory(8);
            assertEquals(1, source.count());
            assertEquals(source, store.hardInventory(8));
            assertEquals(1, store.scanBatches(8, 1, seen::addAll));
            assertEquals(1, seen.size());
            store.requireOrRecordSource(source);

            final var output = store.inventory(9);
            store.recordWave(new AvoidancePreimageStore.WaveManifest(
                    8, source, 17, output));
            store.recordSearch(new AvoidancePreimageStore.SearchManifest(
                    8, source, 0, 0, source, 123));
        }

        try (final var reopened = new AvoidancePreimageStore(directory)) {
            reopened.requireOrRecordSource(source);
            assertEquals(source, reopened.inventory(8));
            final var wave = reopened.waveManifest(8).orElseThrow();
            assertEquals(17, wave.reconstructionApplications());
            assertEquals(0, wave.output().count());
            final var search = reopened.searchManifest(8).orElseThrow();
            assertEquals(source, search.candidates());
            assertEquals(source, search.residuals());
            assertEquals(123, search.checksum());
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
            store.putHardPairs(8, List.of(identity, threeCycle));
            final var result = ObstructionAvoidanceRunner.reconstructWave(
                    store, 8, 2);

            assertEquals(2, result.processed());
            assertEquals(Math.min(2, Math.max(1,
                            Runtime.getRuntime().availableProcessors())),
                    result.parallelism());
            assertEquals(0, store.inventory(9).count());
        }
    }

    @Test
    void screensIntermediatePrefixesAndEmitsOnlyRootMisses() {
        final var beta = Cycle.of(0, 1, 2);
        final var threeCycle = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), beta)
                .canonicalRepresentative();
        final var misses = new ConcurrentLinkedQueue<>();

        try (final var store = new AvoidancePreimageStore(
                temporaryDirectory.resolve("search-work"))) {
            store.putPairs(9, List.of(threeCycle));
            final var intermediate = ObstructionAvoidanceRunner.searchWave(
                    store, 9, 1, false, misses::add,
                    (pair, rate, moves, fullDomain) ->
                            WitnessSearch.SearchResult.residual());

            assertEquals(1, intermediate.processed());
            assertEquals(0, intermediate.certificates());
            assertEquals(1, intermediate.residuals());
            assertEquals(1, store.hardInventory(9).count());
            assertEquals(0, misses.size());

            final int[] symbols = IntStream.range(0, 19).toArray();
            final var root = CyclicTargetPair.of(
                    new MulticyclePermutation(Cycle.of(symbols)),
                    Cycle.of(symbols)).canonicalRepresentative();
            store.putPairs(10, List.of(root));
            final var rootSearch = ObstructionAvoidanceRunner.searchWave(
                    store, 10, 1, true, misses::add,
                    (pair, rate, moves, fullDomain) ->
                            WitnessSearch.SearchResult.residual());

            assertEquals(1, rootSearch.residuals());
            assertEquals(1, store.hardInventory(10).count());
            assertEquals(1, misses.size());
        }
    }

    @Test
    void recognizesOnlyCanonicalFullDomainAvoidanceMissKeys() {
        final int[] symbols = IntStream.range(0, 19).toArray();
        final var root = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(symbols)),
                Cycle.of(symbols)).canonicalRepresentative();
        final var intermediate = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();

        assertTrue(ObstructionAvoidanceRunner.isAvoidanceRootMiss(
                CertificateCodec.keyFor(root)));
        assertFalse(ObstructionAvoidanceRunner.isAvoidanceRootMiss(
                CertificateCodec.keyFor(intermediate)));
        assertFalse(ObstructionAvoidanceRunner.isAvoidanceRootMiss(
                new byte[]{1, 2, 3}));
    }
}

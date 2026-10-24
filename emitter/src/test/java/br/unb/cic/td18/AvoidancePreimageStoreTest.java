package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvoidancePreimageStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(30)
    void flushesAtTheSizeLimitAndReleasesCompletedKeys() throws Exception {
        final var directory = temporaryDirectory.resolve("size-limit");
        final var pair = smallPair();
        try (final var store = new AvoidancePreimageStore(
                directory, 8, Duration.ofHours(1))) {
            store.putStates(IntStream.rangeClosed(1, 32)
                    .mapToObj(budget -> new AvoidancePreimageStore.State(budget, pair))
                    .toList());
            awaitWritten(store, 32, 4);
            final var statistics = store.bufferStatistics();
            assertEquals(8, statistics.peakBufferedKeys());
            assertEquals(0, statistics.bufferedKeys());
            assertEquals(32, statistics.writtenKeys());
            assertTrue(statistics.completedDrains() >= 4);
        }
        try (final var reopened = new AvoidancePreimageStore(directory)) {
            for (var budget = 1; budget <= 32; budget++) {
                assertEquals(1, reopened.inventory(budget).count());
            }
        }
    }

    @Test
    @Timeout(30)
    void flushesOnTheTimerBelowTheSizeLimit() throws Exception {
        final var directory = temporaryDirectory.resolve("timed-flush");
        try (final var store = new AvoidancePreimageStore(
                directory, 1_000, Duration.ofMillis(100))) {
            store.putStates(List.of(new AvoidancePreimageStore.State(8, smallPair())));
            awaitWritten(store, 1);
            assertEquals(0, store.bufferStatistics().bufferedKeys());
            assertEquals(1, store.bufferStatistics().peakBufferedKeys());
        }
        try (final var reopened = new AvoidancePreimageStore(directory)) {
            assertEquals(1, reopened.inventory(8).count());
        }
    }

    @Test
    @Timeout(30)
    void capsBothFamiliesAcrossConcurrentProducersAndPreservesEveryKey()
            throws Exception {
        final var directory = temporaryDirectory.resolve("bounded-concurrent");
        final var pair = smallPair();
        final var states = IntStream.rangeClosed(1, 64)
                .mapToObj(budget -> new AvoidancePreimageStore.State(budget, pair))
                .toList();
        final var store = new AvoidancePreimageStore(
                directory, 17, Duration.ofHours(1));
        try (store; final var producers = Executors.newFixedThreadPool(8)) {
            final var futures = IntStream.range(0, 8)
                    .mapToObj(worker -> producers.submit(() -> {
                        store.putStates(states);
                        store.putHardStates(states);
                    })).toList();
            for (final var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            assertTrue(store.bufferStatistics().peakBufferedKeys() <= 17);
        }
        assertEquals(0, store.bufferStatistics().bufferedKeys());
        try (final var reopened = new AvoidancePreimageStore(directory)) {
            for (var budget = 1; budget <= 64; budget++) {
                assertEquals(1, reopened.inventory(budget).count());
                assertEquals(1, reopened.hardInventory(budget).count());
            }
        }
    }

    private static CyclicTargetPair smallPair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
    }

    @Test
    void persistsPackedCanonicalGpuKeysWithoutRecanonicalizingThem() {
        final var pair = smallPair();
        final byte[] encoded = CertificateCodec.keyFor(pair);
        final byte[] record = new byte[
                GpuAvoidanceReconstructor.KEY_RECORD_BYTES];
        record[0] = (byte) pair.getBeta().size();
        System.arraycopy(encoded, 0, record, 1, encoded.length);
        final var recovered = new ArrayList<CyclicTargetPair>();

        try (final var store = new AvoidancePreimageStore(
                temporaryDirectory.resolve("gpu-keys"))) {
            store.putCanonicalKeyRecords(9, record);
            assertEquals(1, store.inventory(9).count());
            assertEquals(1, store.scanBatches(9, 1, recovered::addAll));
        }
        assertEquals(List.of(pair), recovered);
    }

    @Test
    void clearsOnlyTheUncommittedWaveOutputRange() {
        final var pair = smallPair();
        final var directory = temporaryDirectory.resolve("clear-wave");
        try (final var store = new AvoidancePreimageStore(directory)) {
            store.putStates(List.of(
                    new AvoidancePreimageStore.State(8, pair),
                    new AvoidancePreimageStore.State(9, pair)));
            assertEquals(1, store.inventory(8).count());
            assertEquals(1, store.inventory(9).count());

            store.clearUncommittedWaveOutput(8);

            assertEquals(1, store.inventory(8).count());
            assertEquals(0, store.inventory(9).count());
        }
    }

    private static void awaitWritten(final AvoidancePreimageStore store,
                                     final long expected) throws InterruptedException {
        awaitWritten(store, expected, 1);
    }

    private static void awaitWritten(final AvoidancePreimageStore store,
                                     final long expected, final long minimumDrains)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            final var statistics = store.bufferStatistics();
            if (statistics.writtenKeys() >= expected
                    && statistics.completedDrains() >= minimumDrains
                    && statistics.bufferedKeys() == 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Avoidance buffer did not flush: " + store.bufferStatistics());
    }

    @Test
    void rejectsWorkDatabasesWithInsertionHistoryApplicationCounts()
            throws Exception {
        final var directory = temporaryDirectory.resolve("legacy-work");
        RocksDB.loadLibrary();
        try (final var options = new DBOptions().setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
             final var familyOptions = new ColumnFamilyOptions()) {
            final var handles = new ArrayList<ColumnFamilyHandle>();
            try (final var database = RocksDB.open(options, directory.toString(),
                    List.of(new ColumnFamilyDescriptor(
                                    RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                            new ColumnFamilyDescriptor("avoidance-preimages"
                                    .getBytes(StandardCharsets.UTF_8), familyOptions),
                            new ColumnFamilyDescriptor("avoidance-hard"
                                    .getBytes(StandardCharsets.UTF_8), familyOptions)),
                    handles)) {
                try {
                    database.put(handles.getFirst(),
                            "format-version".getBytes(StandardCharsets.UTF_8),
                            ByteBuffer.allocate(Integer.BYTES).putInt(1).array());
                } finally {
                    handles.forEach(ColumnFamilyHandle::close);
                }
            }
        }

        final var failure = assertThrows(IllegalStateException.class,
                () -> new AvoidancePreimageStore(directory));
        assertTrue(failure.getCause().getMessage()
                .contains("Unsupported avoidance work format version"));
    }

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
            // Development resumes compare the source count, not saved fingerprints.
            reopened.requireOrRecordSource(new AvoidancePreimageStore.StateInventory(
                    source.count(), source.checksum() + 1));
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

package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateCodec;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ClassStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsExistingTablesWithoutBloomFiltersWithoutChangingClassKeys() throws Exception {
        final var directory = temporaryDirectory.resolve("legacy");
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        final var children = CircularClassOperations.childClasses(parent);
        RocksDB.loadLibrary();
        try (final var options = new Options().setCreateIfMissing(true);
             final var database = RocksDB.open(options, directory.toString());
             final var flush = new FlushOptions().setWaitForFlush(true)) {
            database.put(CertificateCodec.keyFor(children.getFirst()), new byte[]{1});
            database.flush(flush);
        }
        try (final var store = new ClassStore(directory, 4, false)) {
            assertTrue(store.contains(children.getFirst()));
            assertFalse(store.contains(children.getLast()));
            assertEquals(1, store.summarize().count());
            final var queried = store.containsMany(children);
            assertTrue(queried[0]);
            assertFalse(queried[queried.length - 1]);
        }
    }

    @Test
    void persistsCanonicalClassesAndReconstructsFixedPoints() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var pair = CircularClassOperations.insertions(parent).stream()
                .map(CircularClassOperations.Insertion::child)
                .filter(child -> child.getOmega().image(3) == 3)
                .findFirst()
                .orElseThrow();
        final ClassStore.Summary written;
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("classes"), 4, true)) {
            assertTrue(store.add(pair));
            assertFalse(store.add(pair.canonicalRepresentative()));
            assertTrue(store.contains(pair));
            written = store.summarize();
            assertEquals(1, written.count());
        }

        try (final var store = new ClassStore(
                temporaryDirectory.resolve("classes"), 4, false)) {
            final var restored = new ArrayList<CyclicTargetPair>();
            store.forEach(restored::add);
            assertEquals(1, restored.size());
            assertEquals(pair.extendedToricClassKey(),
                    restored.getFirst().extendedToricClassKey());
            assertEquals(1, CircularClassOperations.algebraicFixedPointCount(
                    restored.getFirst()));
            assertEquals(written, store.summarize());
        }
    }

    @Test
    void manifestChecksCountAndChecksum() {
        final var stage = temporaryDirectory.resolve("stage");
        try (final var store = new ClassStore(stage.resolve("classes"), 3, true)) {
            store.add(CyclicTargetPair.of(
                    new MulticyclePermutation(Cycle.of(0, 1, 2)),
                    Cycle.of(0, 1, 2)));
            StageManifest.complete(stage, "TEST", 3, store.summarize());
        }
        try (final var store = new ClassStore(stage.resolve("classes"), 3, false)) {
            StageManifest.verify(stage, "TEST", 3, store.summarize());
        }
    }

    @Test
    void boundedParallelIterationVisitsEveryClass() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final Set<ExtendedToricClassKey> expected =
                ConcurrentHashMap.newKeySet();
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("parallel"), 4, true)) {
            CircularClassOperations.childClasses(parent).forEach(child -> {
                store.add(child);
                expected.add(child.extendedToricClassKey());
            });

            final Set<ExtendedToricClassKey> actual =
                    ConcurrentHashMap.newKeySet();
            store.forEachParallel(4,
                    pair -> actual.add(pair.extendedToricClassKey()));
            assertEquals(expected, actual);
        }
    }

    @Test
    void multiGetMatchesScalarReadsIncludingMissesDuplicatesAndEquivalentPairs() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        final var children = CircularClassOperations.childClasses(parent);
        final var queries = new ArrayList<>(children);
        queries.add(children.getFirst().canonicalRepresentative());
        queries.add(children.getFirst());
        final var directory = temporaryDirectory.resolve("multiget");
        try (final var store = new ClassStore(directory, 4, true)) {
            store.add(children.getFirst());
        }
        try (final var store = new ClassStore(directory, 4, false)) {
            final var expected = new boolean[queries.size()];
            for (var index = 0; index < queries.size(); index++) {
                expected[index] = store.contains(queries.get(index));
            }
            assertArrayEquals(expected, store.containsMany(queries));
            assertArrayEquals(new boolean[0], store.containsMany(List.of()));
        }
    }

    @Test
    void parallelBatchesIncludeEveryClassAndThePartialFinalBatch() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("batches"), 4, true)) {
            CircularClassOperations.childClasses(parent).forEach(store::add);
            final var visits = new ConcurrentHashMap<ExtendedToricClassKey, Integer>();
            store.forEachBatchParallel(4, 2, batch -> {
                assertTrue(batch.size() <= 2);
                batch.forEach(pair -> visits.merge(pair.extendedToricClassKey(), 1, Integer::sum));
            });
            assertEquals(store.summarize().count(), visits.size());
            visits.values().forEach(count -> assertEquals(1, count));
        }
    }
}

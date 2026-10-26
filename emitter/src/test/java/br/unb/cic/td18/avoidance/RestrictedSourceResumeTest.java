package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.search.WitnessSearch;
import br.unb.cic.td18.table.Table2;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import static org.junit.jupiter.api.Assertions.*;

class RestrictedSourceResumeTest {
    @TempDir Path directory;

    private static CyclicTargetPair restricted(final int length, final int exponent) {
        final var cycles = new ArrayList<Cycle>();
        for (var component = 0; component < 2; component++) {
            final int size = component == 0 ? length : 18 - length;
            final int offset = component == 0 ? 0 : length;
            final int power = component == 0 ? exponent : (size + 1) / 2;
            final int[] symbols = IntStream.range(0, size)
                    .map(index -> offset + index * power % size).toArray();
            cycles.add(Cycle.of(symbols));
        }
        final var pair = CyclicTargetPair.of(new MulticyclePermutation(cycles),
                Cycle.of(IntStream.range(0, 18).toArray())).canonicalRepresentative();
        assertTrue(RestrictedOddPair.contains(pair));
        return pair;
    }

    private static CyclicTargetPair budgetEight() {
        return CyclicTargetPair.of(new MulticyclePermutation(List.of(
                        Cycle.of(0, 11, 13, 12, 2, 4, 9, 5, 7, 3, 14),
                        Cycle.of(1, 10, 8, 15, 6))),
                Cycle.of(0, 6, 5, 15, 14, 8, 4, 9, 1, 12, 11, 13, 3, 2, 7, 10))
                .canonicalRepresentative();
    }

    private static List<CyclicTargetPair> directRoots() {
        final var result = new ArrayList<CyclicTargetPair>();
        DirectTwistedBraceletGenerator.generateRepresentatives(
                new int[]{9, 7, 3}, new boolean[3], result::add);
        assertEquals(28, result.size());
        return result;
    }

    @Test
    void realRootWitnessCanDeleteItsMarkerWithoutChangingTheImmutableSource() throws Exception {
        final var root = CyclicTargetPair.of(new MulticyclePermutation(List.of(
                Cycle.of(0, 1, 2, 3, 4), Cycle.of(5, 6, 7, 8, 9),
                Cycle.of(10, 15, 12), Cycle.of(11, 17, 13), Cycle.of(14, 18, 16))),
                Cycle.of(IntStream.range(0, 19).toArray())).canonicalRepresentative();
        final var witness = List.of(Cycle.of(2, 3, 4), Cycle.of(0, 1, 2),
                Cycle.of(5, 6, 7), Cycle.of(5, 8, 9), Cycle.of(10, 15, 16),
                Cycle.of(10, 14, 16), Cycle.of(10, 12, 13), Cycle.of(10, 11, 13),
                Cycle.of(13, 17, 18), Cycle.of(13, 16, 18));
        CertificateValidator.validate(root, witness, 10);
        final var obligation = new ResidualObligation(206, 10, root);
        final var table = Table2.read(ProjectPaths.root(Path.of(""))
                .resolve("docs/td18-reduction-count.tex"));
        final var selected = List.of(table.get(205));
        final var certificates = directory.resolve("root-certificate");
        final var work = directory.resolve("root-work");
        try (final var writer = new AsyncCertificateWriter(certificates, 60)) {
            writer.enqueueResidual(obligation);
        }
        final var before = ObstructionAvoidanceRunner.loadResiduals(certificates, selected);
        assertEquals(List.of(obligation), before);
        try (final var store = new AvoidancePreimageStore(work)) {
            assertEquals(List.of(root), ObstructionAvoidanceRunner.initializeSources(store, before).get(10));
        }
        try (final var writer = new AsyncCertificateWriter(certificates, 60)) {
            writer.enqueue(root, witness);
            writer.flush();
            assertEquals(witness, writer.find(root, 10).orElseThrow());
        }
        final var after = ObstructionAvoidanceRunner.loadResiduals(certificates, selected);
        assertTrue(after.isEmpty(), "A real certificate must remove its obstruction marker");
        try (final var store = new AvoidancePreimageStore(work)) {
            final var restored = ObstructionAvoidanceRunner.initializeSources(store, after);
            assertEquals(List.of(root), restored.get(10));
            assertEquals(1, restored.values().stream().mapToLong(List::size).sum());
            store.clearUncommittedWaveOutput(9);
            assertEquals(1, ObstructionAvoidanceRunner.prepareWaveOutput(store, 10, restored.get(10)).count());
        }
    }

    @Test
    void immutableSourcesRejectNewIntermediateSeedsAndRecognizeRecordedRootOutputs() {
        final var roots = directRoots();
        final var first = new ResidualObligation(267, 9, restricted(13, 7));
        final var source = List.of(first, new ResidualObligation(5, 10, roots.getFirst()));
        try (final var store = new AvoidancePreimageStore(directory.resolve("source-membership"))) {
            final var original = store.requireOrRecordTerminalSources(source);
            assertEquals(original, store.requireOrRecordTerminalSources(List.of()));
            assertThrows(IllegalStateException.class, () -> store.requireOrRecordTerminalSources(
                    List.of(first, new ResidualObligation(270, 9, restricted(11, 6)))));
            assertThrows(IllegalStateException.class, () -> store.requireOrRecordTerminalSources(
                    List.of(new ResidualObligation(130, 8, budgetEight()))));
            assertThrows(IllegalStateException.class, () -> store.requireOrRecordTerminalSources(
                    List.of(new ResidualObligation(5, 10, roots.get(1)))));
            store.putPairs(10, List.of(roots.get(1)));
            assertEquals(original, store.requireOrRecordTerminalSources(
                    List.of(new ResidualObligation(5, 10, roots.get(1)))));
        }
    }

    @Test
    void interruptedSourceCommitIsReplacedButMissingCommittedRecordsAreRejected() throws Exception {
        final var first = new ResidualObligation(267, 9, restricted(13, 7));
        final var second = new ResidualObligation(270, 9, restricted(11, 6));
        final var path = directory.resolve("source-crash");
        try (final var store = new AvoidancePreimageStore(path)) {
            store.requireOrRecordTerminalSources(List.of(first));
        }
        // Records preceding a missing completion footer model an interrupted initial write.
        mutateMetadata(path, (database, metadata) ->
                database.delete(metadata, "terminal-source".getBytes(StandardCharsets.UTF_8)));
        try (final var store = new AvoidancePreimageStore(path)) {
            assertEquals(List.of(second), store.requireOrRecordTerminalSources(List.of(second)));
        }
        mutateMetadata(path, (database, metadata) -> {
            try (final var iterator = database.newIterator(metadata)) {
                final byte[] prefix = "terminal-source-state/".getBytes(StandardCharsets.UTF_8);
                iterator.seek(prefix);
                assertTrue(iterator.isValid());
                assertArrayEquals(prefix, Arrays.copyOf(iterator.key(), prefix.length));
                database.delete(metadata, iterator.key());
            }
        });
        try (final var store = new AvoidancePreimageStore(path)) {
            assertThrows(IllegalStateException.class, () ->
                    store.requireOrRecordTerminalSources(List.of(second)));
        }
    }

    @Test
    void immutableSourceDecoderRejectsAChangedRowAndMissingCompletionForExistingWaves() throws Exception {
        final var source = new ResidualObligation(267, 9, restricted(13, 7));
        final var path = directory.resolve("source-corruption");
        try (final var store = new AvoidancePreimageStore(path)) {
            store.requireOrRecordTerminalSources(List.of(source));
        }
        mutateMetadata(path, (database, metadata) -> {
            try (final var iterator = database.newIterator(metadata)) {
                iterator.seek("terminal-source-state/".getBytes(StandardCharsets.UTF_8));
                assertTrue(iterator.isValid());
                database.put(metadata, iterator.key(), new byte[]{0, 1});
            }
        });
        try (final var store = new AvoidancePreimageStore(path)) {
            assertThrows(IllegalArgumentException.class, () ->
                    store.requireOrRecordTerminalSources(List.of(source)));
        }
        final var other = directory.resolve("source-missing-completion");
        try (final var store = new AvoidancePreimageStore(other)) {
            final var empty = new AvoidancePreimageStore.StateInventory(0, 0);
            store.recordWave(new AvoidancePreimageStore.WaveManifest(8, empty, 0, empty));
            assertThrows(IllegalStateException.class, () -> store.requireOrRecordTerminalSources(List.of(source)));
        }
    }

    @FunctionalInterface
    private interface MetadataMutation {
        void apply(RocksDB database, ColumnFamilyHandle metadata) throws Exception;
    }

    private static void mutateMetadata(final Path path, final MetadataMutation mutation) throws Exception {
        final var handles = new ArrayList<ColumnFamilyHandle>();
        try (final var options = new DBOptions();
             final var metadataOptions = new ColumnFamilyOptions();
             final var preimageOptions = new ColumnFamilyOptions();
             final var hardOptions = new ColumnFamilyOptions();
             final var database = RocksDB.open(options, path.toString(), List.of(
                     new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, metadataOptions),
                     new ColumnFamilyDescriptor("avoidance-preimages".getBytes(StandardCharsets.UTF_8), preimageOptions),
                     new ColumnFamilyDescriptor("avoidance-hard".getBytes(StandardCharsets.UTF_8), hardOptions)), handles)) {
            try {
                mutation.apply(database, handles.getFirst());
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }

    @Test
    void standaloneAvoidanceRegeneratesAllSeedsWithoutAnyDatabaseMarkers() throws Exception {
        final var table = Table2.read(ProjectPaths.root(Path.of(""))
                .resolve("docs/td18-reduction-count.tex"));
        final var seeds = ObstructionAvoidanceRunner.loadResiduals(
                directory.resolve("no-certificate-database"), table);
        assertEquals(1_884, seeds.size());
        assertEquals(391, seeds.stream().filter(seed -> seed.row() == 267).count());
        assertEquals(321, seeds.stream().filter(seed -> seed.row() == 270).count());
        assertEquals(1_172, seeds.stream().filter(seed -> seed.row() == 274).count());
        assertTrue(seeds.stream().allMatch(seed -> seed.maximumMoves() == 9
                && RestrictedOddPair.contains(seed.pair())));
        assertEquals(seeds, ObstructionAvoidanceRunner.withRestrictedSeeds(table, seeds));
    }

    @Test
    void mixedSourcesSurviveReconstructionCleanupAndSearchRestart() {
        final var seed = restricted(13, 7);
        final var prefixMiss = restricted(11, 6);
        final var roots = directRoots();
        final var source = List.of(new ResidualObligation(130, 8, budgetEight()),
                new ResidualObligation(267, 9, seed),
                new ResidualObligation(5, 10, roots.getFirst()));
        final var path = directory.resolve("mixed-source-work");
        try (final var store = new AvoidancePreimageStore(path)) {
            final var sources = ObstructionAvoidanceRunner.initializeSources(store, source);
            assertEquals(1, store.inventory(8).count());
            assertEquals(1, store.hardInventory(9).count());
            assertEquals(0, store.inventory(10).count());

            // Simulate an interrupted reconstruction, including a preimage that is a seed.
            store.putPairs(9, List.of(seed, prefixMiss));
            store.clearUncommittedWaveOutput(8);
            assertEquals(1, store.hardInventory(9).count());
            store.putPairs(9, List.of(seed, prefixMiss));
            final var candidates = ObstructionAvoidanceRunner.prepareWaveOutput(
                    store, 9, sources.get(9));
            assertEquals(1, candidates.count());

            // Interrupted search outputs are discarded; immutable seed is restored.
            store.putHardPairs(9, List.of(prefixMiss));
            store.resetHard(9, sources.get(9));
            assertEquals(0, store.hardInventoryExcluding(9, sources.get(9)).count());
            final var result = ObstructionAvoidanceRunner.searchWave(store, 9, 1, false,
                    miss -> fail("Intermediate screening must not persist a root miss"),
                    (pair, rate, budget, fullDomain) -> {
                        assertEquals(prefixMiss, pair);
                        assertEquals(9, budget);
                        assertFalse(fullDomain);
                        return WitnessSearch.SearchResult.residual();
                    });
            assertEquals(1, result.residuals());
            final var searchedMisses = store.hardInventoryExcluding(9, sources.get(9));
            assertEquals(1, searchedMisses.count());
            assertEquals(2, store.hardInventory(9).count());
            store.recordWave(new AvoidancePreimageStore.WaveManifest(
                    8, store.hardInventory(8), 0, candidates));
            store.recordSearch(new AvoidancePreimageStore.SearchManifest(
                    9, candidates, 0, 0, searchedMisses, result.checksum()));
        }
        try (final var store = new AvoidancePreimageStore(path)) {
            final var sources = ObstructionAvoidanceRunner.initializeSources(store, source);
            assertEquals(2, store.hardInventory(9).count());
            assertEquals(store.searchManifest(9).orElseThrow().residuals(),
                    store.hardInventoryExcluding(9, sources.get(9)));
            assertEquals(store.waveManifest(8).orElseThrow().output(), store.inventory(9));

            // Direct root sources are restored after cleanup and join deduplicated roots.
            store.putPairs(10, roots.subList(0, 2));
            store.clearUncommittedWaveOutput(9);
            store.putPairs(10, List.of(roots.get(1)));
            final var output = ObstructionAvoidanceRunner.prepareWaveOutput(
                    store, 10, sources.get(10));
            assertEquals(2, output.count());
            store.putHardPairs(10, List.of(roots.getFirst()));
            store.resetHard(10, List.of());
            final var search = ObstructionAvoidanceRunner.searchWave(store, 10, 2, true,
                    miss -> fail("Stub closure must not emit a miss"),
                    (pair, rate, budget, fullDomain) -> {
                        assertEquals(10, budget);
                        assertEquals(19, pair.getBeta().size());
                        assertTrue(fullDomain);
                        return WitnessSearch.SearchResult.triviallySettled();
                    });
            assertEquals(2, search.trivialClosures());
            assertEquals(0, store.hardInventory(10).count());
        }
    }

    @Test
    void restrictedSeedGoesStraightToRootsAndUsesExactlyTheClaimedInverseSupports() {
        final var seed = restricted(13, 7);
        final var result = ObstructionAvoidance.resolve(
                List.of(new ResidualObligation(267, 9, seed)),
                (pair, rate, budget, fullDomain) -> {
                    assertTrue(fullDomain);
                    assertEquals(10, budget);
                    assertEquals(19, pair.getBeta().size());
                    return WitnessSearch.SearchResult.triviallySettled();
                }, miss -> fail("Stub closure must not emit a miss"));
        assertEquals(2_754, result.reconstructionApplications());
        assertEquals(0, result.intermediatePrefixObligations());
        assertTrue(result.rootSearchObligations() > 0);
        assertTrue(result.rootSearchObligations() <= 2_754);
        assertTrue(result.complete());
    }
}

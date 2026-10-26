package br.unb.cic.td18.verification;

import br.unb.cic.td18.avoidance.ObstructionAvoidance;
import br.unb.cic.td18.avoidance.ResidualObligation;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.search.CheckedSearchResult;
import br.unb.cic.td18.table.Table2;
import br.unb.cic.td18.table.TableEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static br.unb.cic.td18.fixtures.AvoidanceFixtures.controlResidual;
import static org.junit.jupiter.api.Assertions.*;

class RestrictedOddPairVerifierTest {
    @Test
    void independentClassificationCountsAndDirectClassSetsAgree() {
        RestrictedOddPairVerifier.verifyInventory();
        final var seen = new HashSet<>();
        for (final int first : List.of(13, 11, 9)) {
            final var pairs = RestrictedOddPairVerifier.representatives(
                    new int[]{first, 18 - first}, new boolean[]{true, true});
            assertTrue(pairs.stream().allMatch(RestrictedOddPair::contains));
            assertTrue(pairs.stream().map(CyclicTargetPair::extendedToricClassKey).allMatch(seen::add));
        }
        assertEquals(1_884, seen.size());
    }

    @Test
    void unprunedLocalEnumerationAgreesWithNecessaryConditionDfs() {
        for (final int length : List.of(5, 7, 9)) {
            final var found = new HashSet<String>();
            final int[] path = new int[length];
            for (int i = 0; i < length; i++) path[i] = i;
            permute(path, 1, found);
            assertEquals(found, new HashSet<>(RestrictedOddPairVerifier.classify(length).cycles()
                    .stream().map(Arrays::toString).toList()));
        }
    }

    @Test
    void rejectsStaleWholeTypeResidualsAndInvalidRestrictedContexts() {
        final var unrestricted = firstPair(new int[]{13, 5}, new boolean[]{true, true});
        assertFalse(RestrictedOddPair.contains(unrestricted));
        assertThrows(IllegalArgumentException.class, () -> ObstructionAvoidance.validateTerminalResidual(
                new ResidualObligation(267, 9, unrestricted)));
        assertFalse(RestrictedOddPair.isObligation(new int[]{13, 5}, new boolean[]{true, true}, 2));
        assertThrows(IllegalArgumentException.class, () -> RestrictedOddPairVerifier.representatives(
                new int[]{13, 5}, new boolean[]{true, false}));
    }

    private static void permute(final int[] path, final int depth, final HashSet<String> found) {
        if (depth == path.length) {
            if (!RestrictedOddPairVerifier.hasQualifyingTransposition(path)) found.add(Arrays.toString(path));
            return;
        }
        for (int i = depth; i < path.length; i++) {
            final int temp = path[i]; path[i] = path[depth]; path[depth] = temp;
            permute(path, depth + 1, found);
            path[depth] = path[i]; path[i] = temp;
        }
    }

    @Test
    void regeneratesAllSeedsWithoutStoredMarkersOrNineMoveWitnesses(@TempDir final Path directory)
            throws Exception {
        final var root = ProjectPaths.root(Path.of(""));
        final var table = Table2.read(root.resolve("docs/td18-reduction-count.tex"));
        initializeDatabase(directory);
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var queued = new ConcurrentLinkedQueue<ResidualObligation>();
            for (final int row : List.of(267, 270, 274)) {
                final var result = CertificateChecker.verifyRow(table.get(row - 1), certificates, queued, progress);
                assertEquals(0, result.trivialClosures());
                assertEquals(0, result.certificates());
                assertEquals(result.generated(), result.residuals());
            }
            assertEquals(1_884, queued.size());
            final var options = CertificateChecker.Options.parse(new String[]{
                    "--help", "--phase=avoidance", "--rows=267-274"}, root);
            final var standalone = CertificateChecker.loadTerminalResiduals(table, certificates, options, progress);
            assertEquals(1_884, standalone.size());
            assertEquals(new HashSet<>(queued), new HashSet<>(standalone));

            final var entry = table.get(266);
            final var wrongCount = new TableEntry(entry.number(), entry.partition(), entry.orientedByPart(),
                    entry.nonfixedSymbols(), entry.fixedSymbols(), entry.movesUsed(), entry.remainingMoves(),
                    entry.threeNorm(), entry.recordedRealizableClasses(), 390L);
            assertThrows(IllegalStateException.class, () -> CertificateChecker.verifyRow(
                    wrongCount, certificates, new ConcurrentLinkedQueue<>(), progress));
            final var zeroCount = new TableEntry(entry.number(), entry.partition(), entry.orientedByPart(),
                    entry.nonfixedSymbols(), entry.fixedSymbols(), entry.movesUsed(), entry.remainingMoves(),
                    entry.threeNorm(), entry.recordedRealizableClasses(), 0L);
            assertThrows(IllegalStateException.class, () -> CertificateChecker.verifyRow(
                    zeroCount, certificates, new ConcurrentLinkedQueue<>(), progress));
        }
    }

    @Test
    void standaloneIgnoresOldWholeTypeMarkersAndStillRegeneratesEverySeed(
            @TempDir final Path directory) throws Exception {
        final var oldPair = firstPair(new int[]{13, 5}, new boolean[]{true, true});
        assertFalse(RestrictedOddPair.contains(oldPair));
        initializeDatabase(directory, CertificateCodec.keyFor(oldPair));
        final var root = ProjectPaths.root(Path.of(""));
        final var table = Table2.read(root.resolve("docs/td18-reduction-count.tex"));
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var options = CertificateChecker.Options.parse(new String[]{
                    "--help", "--phase=avoidance", "--rows=267-274"}, root);
            final var seeds = CertificateChecker.loadTerminalResiduals(table, certificates, options, progress);
            assertEquals(1_884, seeds.size());
            assertTrue(seeds.stream().allMatch(seed -> RestrictedOddPair.contains(seed.pair())));
        }
    }

    @Test
    void reconstructsSeedsAtRootsAndKeepsMixedBudgetSources(@TempDir final Path directory) {
        final var seedPair = RestrictedOddPairVerifier.representatives(
                new int[]{13, 5}, new boolean[]{true, true}).getFirst();
        final var seed = new ResidualObligation(267, 9, seedPair);
        final var expectedRoots = ObstructionAvoidance.reconstructParents(seedPair, 9);
        assertFalse(expectedRoots.parents().isEmpty());
        assertTrue(expectedRoots.applications() <= 2_754);
        final var rootPair = firstPair(new int[]{8, 3, 3, 3, 2},
                new boolean[]{false, false, false, false, false});
        final var directRoot = new ResidualObligation(212, 10, rootPair);
        ObstructionAvoidance.validateTerminalResidual(directRoot);
        final var visited = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (final var progress = new VerificationProgress()) {
            final var result = DiskObstructionVerifier.resolve(List.of(
                    new ResidualObligation(130, 8, controlResidual()), seed, directRoot),
                    (pair, rate, maximumMoves, fullDomain) -> {
                        assertEquals(maximumMoves == 10, fullDomain);
                        assertNotEquals(seedPair.extendedToricClassKey(), pair.extendedToricClassKey(),
                                "The restricted seed must never be searched at budget nine");
                        if (fullDomain) visited.add(pair.extendedToricClassKey());
                        return CheckedSearchResult.triviallySettled();
                    }, miss -> fail("Unexpected miss"), directory.resolve("mixed"), progress);
            assertTrue(result.complete());
            assertEquals(3, result.terminalResiduals());
            final var expected = new HashSet<>(expectedRoots.parents().stream()
                    .map(CyclicTargetPair::extendedToricClassKey).toList());
            expected.add(rootPair.extendedToricClassKey());
            assertEquals(expected, visited);
        }
    }

    private static CyclicTargetPair firstPair(final int[] partition, final boolean[] oriented) {
        final CyclicTargetPair[] result = {null};
        final var done = new RuntimeException("First representative found");
        try {
            DirectTwistedBraceletGenerator.generateRepresentatives(partition, oriented, pair -> {
                result[0] = pair;
                throw done;
            });
        } catch (final RuntimeException failure) {
            if (failure != done) throw failure;
        }
        return java.util.Objects.requireNonNull(result[0]);
    }

    @Test
    void missingRestrictedRootWitnessRejectsAnEmptyDatabase(@TempDir final Path directory) throws Exception {
        final var seedPair = RestrictedOddPairVerifier.representatives(
                new int[]{13, 5}, new boolean[]{true, true}).getFirst();
        final var seed = new ResidualObligation(267, 9, seedPair);
        final var database = directory.resolve("certificate");
        initializeDatabase(database);
        // Absence of root witnesses is enough to reject the branch; there is
        // no requirement for a stored residual or search-miss marker first.
        try (final var certificates = new CertificateStore(database);
             final var progress = new VerificationProgress()) {
            final var queue = new ConcurrentLinkedQueue<ResidualObligation>();
            queue.add(seed);
            final var failure = assertThrows(IllegalStateException.class, () ->
                    CertificateChecker.verifyAvoidance(certificates, queue, directory, progress));
            assertTrue(failure.getMessage().contains("Unresolved avoidance obligations"), failure.getMessage());
        }
    }

    static void initializeDatabase(final Path directory, final byte[]... obstructionKeys) throws Exception {
        RocksDB.loadLibrary();
        final var handles = new ArrayList<ColumnFamilyHandle>();
        try (final var options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             final var familyOptions = new ColumnFamilyOptions();
             final var database = RocksDB.open(options, directory.toString(), List.of(
                     new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                     new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), familyOptions),
                     new ColumnFamilyDescriptor("avoidance-intermediate-witnesses".getBytes(StandardCharsets.UTF_8),
                             familyOptions)), handles)) {
            for (final var key : obstructionKeys) database.put(handles.get(1), key, new byte[0]);
            handles.forEach(ColumnFamilyHandle::close);
        }
    }
}

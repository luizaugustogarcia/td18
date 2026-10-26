package br.unb.cic.td18.verification;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExceptionalFifteenCycle;
import br.unb.cic.td18.permutation.Cycle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionalFifteenCycleVerifierTest {
    @Test
    void classifiesTheLocalExceptionAndChecksBothCompanionBudgets() {
        ExceptionalFifteenCycleVerifier.verifyInventory();
        final var inventory = IndependentTypeEnumerator.enumerate();
        assertFalse(inventory.entries().stream().anyMatch(entry ->
                entry.type().equals("(15-O,3-U)")));
    }

    @Test
    void acceptsOnlyACompanionKeyInTheFifteenPhase(@TempDir final Path root) throws Exception {
        final var options = CertificateChecker.Options.parse(new String[]{
                "--help", "--phase=exceptional-fifteen", "--fifteen-type=15O-3U@1"}, root);
        assertEquals("15O-3U@1", options.fifteenType());
        assertTrue(options.checksExceptionalFifteen());
        assertFalse(options.checksExceptionalNine());
        assertFalse(options.checksRows());
        assertTrue(CertificateChecker.Options.parse(new String[]{"--help"}, root)
                .checksExceptionalFifteen());
        assertThrows(IllegalArgumentException.class, () -> CertificateChecker.Options.parse(
                new String[]{"--help", "--phase=rows", "--fifteen-type=15O-3U@1"}, root));
        assertThrows(IllegalArgumentException.class, () -> CertificateChecker.Options.parse(
                new String[]{"--help", "--phase=exceptional-fifteen",
                        "--fifteen-type=15O-3U@0"}, root));
    }

    @Test
    void missingRootWitnessesStillFailAfterCpuClosureScreening(@TempDir final Path directory) throws Exception {
        createDatabase(directory, null, null);
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var error = assertThrows(IllegalStateException.class, () ->
                    ExceptionalFifteenCycleVerifier.verify(certificates, "15O-2U-2U@0", progress));
            assertTrue(error.getMessage().contains("Missing exceptional-fifteen witness"));
            assertTrue(error.getMessage().contains("15O-2U-2U@0"));
            assertTrue(error.getMessage().contains("maximumMoves=10"));
        }
    }

    @Test
    void intermediatePhaseRegeneratesEveryClosureWithAnEmptyDatabase(@TempDir final Path directory)
            throws Exception {
        createDatabase(directory, null, null);
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var result = ExceptionalFifteenCycleVerifier.verify(
                    certificates, "15O-3U@1", progress);
            assertEquals(7, result.classes());
            assertEquals(7, result.cpuClosures());
            assertEquals(0, result.certificates());
        }
    }

    @Test
    void closureCountsRespectEachActualBudget() {
        for (final var obligation : ExceptionalFifteenCycle.obligations()) {
            final long[] generated = {0};
            final long[] closed = {0};
            DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                    obligation.type().partition(), obligation.type().oriented(), pair -> {
                        generated[0]++;
                        if (CertificateChecker.cpuClosure(pair, obligation.budget())) closed[0]++;
                        if (obligation.movesUsed() == 1) {
                            assertFalse(CertificateChecker.cpuClosure(pair, 8));
                        }
                    });
            assertEquals(obligation.movesUsed() == 0 ? 90 : 7, generated[0]);
            assertEquals(obligation.movesUsed() == 0 ? 79 : 7, closed[0]);
        }
    }

    @Test
    void rootMissesStillRejectExcessiveCertificates(@TempDir final Path directory) throws Exception {
        final var pairs = new ArrayList<CyclicTargetPair>();
        DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                new int[]{15, 2, 2}, new boolean[]{true, false, false}, pairs::add);
        final var firstMiss = pairs.stream().filter(pair -> !CertificateChecker.cpuClosure(pair, 10))
                .findFirst().orElseThrow();
        // Count rejection occurs before replay, so this deliberately excessive
        // value must never discharge a class left over from the CPU screen.
        createDatabase(directory, CertificateCodec.keyFor(firstMiss),
                CertificateCodec.valueFor(Collections.nCopies(11, Cycle.of(0, 1, 2))));
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var error = assertThrows(IllegalArgumentException.class, () ->
                    ExceptionalFifteenCycleVerifier.verify(certificates, "15O-2U-2U@0", progress));
            assertTrue(error.getMessage().contains("required range is 1..10"));
        }
    }

    private static void createDatabase(final Path directory, final byte[] key, final byte[] value)
            throws Exception {
        RocksDB.loadLibrary();
        final var handles = new ArrayList<ColumnFamilyHandle>();
        try (final var options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             final var familyOptions = new ColumnFamilyOptions();
             final var database = RocksDB.open(options, directory.toString(), List.of(
                     new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                     new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), familyOptions),
                     new ColumnFamilyDescriptor("avoidance-intermediate-witnesses"
                             .getBytes(StandardCharsets.UTF_8), familyOptions)), handles)) {
            try {
                if (key != null) database.put(handles.getFirst(), key, value);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }
}

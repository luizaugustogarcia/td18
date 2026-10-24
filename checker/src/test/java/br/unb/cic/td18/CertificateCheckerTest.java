package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateStore;
import br.unb.cic.td18.base.CertificateCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateCheckerTest {
    @Test
    void reconstructsResidualContextWithoutCompletionManifests(@TempDir final Path directory) throws Exception {
        final var pair = ObstructionAvoidanceTest.controlResidual();
        RocksDB.loadLibrary();
        final var handles = new ArrayList<ColumnFamilyHandle>();
        try (final var options = new DBOptions().setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
             final var familyOptions = new ColumnFamilyOptions()) {
            try (final var database = RocksDB.open(options, directory.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), familyOptions),
                    new ColumnFamilyDescriptor("avoidance-intermediate-witnesses"
                            .getBytes(StandardCharsets.UTF_8), familyOptions)), handles)) {
                database.put(handles.get(1), CertificateCodec.keyFor(pair), new byte[0]);
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var entries = CountTD18Bracelets.readTableEntries(
                Files.readString(root.resolve("docs/td18-reduction-count.tex")));
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var selected = CertificateChecker.Options.parse(new String[]{
                    "--help", "--phase=avoidance", "--rows=130-130"}, root);
            final var residuals = CertificateChecker.loadTerminalResiduals(
                    entries, certificates, selected, progress);
            assertEquals(1, residuals.size());
            assertEquals(130, residuals.getFirst().row());
            assertEquals(8, residuals.getFirst().maximumMoves());
            assertEquals(pair.extendedToricClassKey(), residuals.getFirst().pair().extendedToricClassKey());
            final var otherRows = CertificateChecker.Options.parse(new String[]{
                    "--help", "--phase=avoidance", "--rows=134-134"}, root);
            assertTrue(CertificateChecker.loadTerminalResiduals(
                    entries, certificates, otherRows, progress).isEmpty());
        }
    }

    @Test
    void acceptsPartialStartingPointsWithoutOpeningTheDatabase(@TempDir final Path directory) throws Exception {
        final var options = CertificateChecker.Options.parse(new String[]{
                "--help", "--rows=130-134", "--phase=avoidance"}, directory);
        assertEquals(130, options.firstRow());
        assertEquals(134, options.lastRow());
        assertTrue(options.checksAvoidance());
        assertTrue(!options.checksRows());
        assertTrue(!options.checksSurvivors());
    }

    @Test
    void recordsBothMissingExceptionalWitnesses(@TempDir final Path directory) throws Exception {
        RocksDB.loadLibrary();
        final var handles = new ArrayList<ColumnFamilyHandle>();
        try (final var options = new DBOptions().setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
             final var familyOptions = new ColumnFamilyOptions()) {
            try (final var database = RocksDB.open(options, directory.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), familyOptions),
                    new ColumnFamilyDescriptor("avoidance-intermediate-witnesses"
                            .getBytes(StandardCharsets.UTF_8), familyOptions)), handles)) {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
        try (final var certificates = new CertificateStore(directory)) {
            final var missing = CertificateChecker.checkOrientedNineteenCertificates(
                    certificates, OrientedNineteenCycleClassification.expectedOrientedPairs());
            assertEquals(2, missing.size());
            assertTrue(missing.stream().allMatch(key -> key.length() == 24));
            final var entry = new CountTD18Bracelets.TableEntry(1,
                    new int[]{3}, new boolean[]{true}, 3, 16, 0, 10, 1, 1L, 1L);
            // No completion manifests exist; mathematical CPU closure still works.
            try (final var progress = new VerificationProgress()) {
                CertificateChecker.verifyRows(List.of(entry), certificates,
                        new ConcurrentLinkedQueue<>(), progress);
            }
        }
    }

    @Test
    void independentlyReconstructsTheDocumentedInventory() throws Exception {
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var table = CountTD18Bracelets.readTableEntries(
                Files.readString(root.resolve(
                                "docs/td18-reduction-count.tex"),
                        StandardCharsets.UTF_8));

        CertificateChecker.verifyInventory(table);
        assertTrue(IndependentTypeEnumerator.verifyReductionPolicy() > 360);
    }

    @Test
    void dischargesQZeroOnlyThroughTheCitedPaperLemma() throws Exception {
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var table = CountTD18Bracelets.readTableEntries(
                Files.readString(root.resolve(
                                "docs/td18-reduction-count.tex"),
                        StandardCharsets.UTF_8));

        final var audit = PaperDischargeLemma.verify(table);

        assertEquals("https://arxiv.org/abs/2609.17493",
                PaperDischargeLemma.PAPER);
        assertEquals(52, audit.orientedThreeRows());
        assertEquals(52, audit.successorSearchRows());
        assertEquals(50, audit.paperSettledSuccessorRows());
        assertEquals(2, audit.retainedResidualRows());
        assertEquals(4_511_223_147L,
                PaperDischargeLemma.PAPER_SUCCESSOR_CLASSES);
        assertEquals(4_511_221_111L,
                PaperDischargeLemma.PAPER_SORTED_SUCCESSOR_CLASSES);
        assertEquals(2_036L,
                PaperDischargeLemma.PAPER_RESIDUAL_SUCCESSOR_CLASSES);
        assertEquals(14, audit.emptyUncoveredRows());
        assertEquals(116, audit.zeroObligationRows());
        assertEquals(29_064_174_153L, audit.totalQ());
    }
}

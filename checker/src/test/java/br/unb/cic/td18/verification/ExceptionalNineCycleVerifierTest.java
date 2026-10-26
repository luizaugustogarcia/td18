package br.unb.cic.td18.verification;

import br.unb.cic.td18.certificate.CertificateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionalNineCycleVerifierTest {
    @Test
    void inventoryAndFocusedPhaseAreIndependentOfTableRowSelection(@TempDir final Path directory)
            throws Exception {
        ExceptionalNineCycleVerifier.verifyInventory();
        final var focused = CertificateChecker.Options.parse(new String[]{"--help",
                "--phase=exceptional-nine", "--nine-type=9O-8U-2U"}, directory);
        assertTrue(focused.checksExceptionalNine());
        assertFalse(focused.checksRows());
        assertEquals("9O-8U-2U", focused.nineType());
        assertTrue(CertificateChecker.Options.parse(new String[]{"--help"}, directory)
                .checksExceptionalNine());
        assertThrows(IllegalArgumentException.class, () -> CertificateChecker.Options.parse(
                new String[]{"--help", "--nine-type=9O-8U-2U"}, directory));
    }

    @Test
    void regeneratesObligationsAndRejectsMissingWitnessesWithoutSidecars(
            @TempDir final Path directory) throws Exception {
        createEmptyDatabase(directory);
        try (final var certificates = new CertificateStore(directory);
             final var progress = new VerificationProgress()) {
            final var missing = assertThrows(IllegalStateException.class, () ->
                    ExceptionalNineCycleVerifier.verify(certificates, "9O-8U-2U", progress));
            assertTrue(missing.getMessage().contains("Missing exceptional-nine root witness"));
            assertFalse(java.nio.file.Files.exists(directory.resolve("proof-manifests")));
        }
    }

    private static void createEmptyDatabase(final Path directory) throws Exception {
        RocksDB.loadLibrary();
        final var handles = new ArrayList<ColumnFamilyHandle>();
        try (final var options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             final var family = new ColumnFamilyOptions();
             final var database = RocksDB.open(options, directory.toString(), List.of(
                     new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, family),
                     new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), family),
                     new ColumnFamilyDescriptor("avoidance-intermediate-witnesses"
                             .getBytes(StandardCharsets.UTF_8), family)), handles)) {
            handles.forEach(ColumnFamilyHandle::close);
        }
    }
}

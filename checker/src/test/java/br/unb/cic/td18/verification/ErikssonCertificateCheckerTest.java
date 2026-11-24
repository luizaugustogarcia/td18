package br.unb.cic.td18.verification;

import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErikssonCertificateCheckerTest {

    @Test
    void missingEndpointWitnessRegeneratesAvoidanceWithoutAnyObstructionMarker(@TempDir final Path path) throws Exception {
        val directory = path.resolve("certificate");
        emptyDatabase(directory);

        val endpoint = new br.unb.cic.td18.joint.ErikssonInventory.Endpoint(1, new int[] { 3, 2, 2 }, new boolean[3]);
        final long expected = br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(), endpoint.orientedByPart());

        try (val store = new CertificateStore(directory);
            val residuals = new CheckerStateSet(path.resolve("regenerated"));
            val progress = new VerificationProgress()) {
            ErikssonCertificateChecker.verifyEndpoint(endpoint, store, residuals, progress);
            assertTrue(expected > 0);
            assertEquals(endpoint.elementaryClosure() ? 0 : expected, residuals.seal().count());
        }
    }

    @Test
    void partialRangesCannotBeReportedAsComplete() throws Exception {
        val options = ErikssonCertificateChecker.Options.parse(new String[] { "--structural-only", "--phase=all", "--partitions=2-" + br.unb.cic.td18.joint.ErikssonRoute.ROOT_PARTITIONS },
                Path.of("."));
        assertFalse(options.completeScope());
        assertThrows(IllegalArgumentException.class,
                () -> ErikssonCertificateChecker.Options.parse(new String[] { "--structural-only", "--endpoints=0-1" }, Path.of(".")));
    }

    @Test
    void missingDirectRootIsRejectedWithoutAMarker(@TempDir final Path path) throws Exception {
        final Path directory = path.resolve("empty-root-database");
        emptyDatabase(directory);

        final int[] partition = { 7 };
        val counts = br.unb.cic.td18.joint.ErikssonRootGenerator.count(partition, 2, new java.util.concurrent.atomic.LongAdder());
        assertTrue(counts.direct() > 0);

        val row = new br.unb.cic.td18.joint.ErikssonInventory.Root(1, partition, counts.unoriented(), counts.oriented(), counts.supportCleared());

        try (val store = new CertificateStore(directory);
            val progress = new VerificationProgress()) {
            assertThrows(IllegalStateException.class, () -> ErikssonCertificateChecker.verifyRoot(row, store, progress));
        }
    }

    @Test
    void zeroDirectInventoryRowsCannotHideMissingRoots(@TempDir final Path path) throws Exception {
        final Path directory = path.resolve("zero-row-database");
        emptyDatabase(directory);

        val row = new br.unb.cic.td18.joint.ErikssonInventory.Root(1, new int[] { 7 }, 0, 0, 0);

        try (val store = new CertificateStore(directory);
            val progress = new VerificationProgress()) {
            val error = assertThrows(IllegalStateException.class,
                    () -> ErikssonCertificateChecker.verifyRoot(row, store, progress));
            assertTrue(error.getMessage().contains("Independent active root counts disagree"));
            assertTrue(java.util.Arrays.stream(error.getSuppressed()).anyMatch(missing -> missing.getMessage().contains("Missing sorting certificate")));
        }
    }

    @Test
    void zeroDirectInventoryRowsStillRequireAllCountsToMatch(@TempDir final Path path) throws Exception {
        final Path directory = path.resolve("support-cleared-row-database");
        emptyDatabase(directory);

        // The three-symbol root domain closes immediately; this falsely
        // declared retained and support-cleared class must still be rejected.
        val row = new br.unb.cic.td18.joint.ErikssonInventory.Root(1, new int[] { 3 }, 0, 1, 1);

        try (val store = new CertificateStore(directory);
            val progress = new VerificationProgress()) {
            val error = assertThrows(IllegalStateException.class,
                    () -> ErikssonCertificateChecker.verifyRoot(row, store, progress));
            assertTrue(error.getMessage().contains("Independent active root counts disagree"));
        }
    }

    private static void emptyDatabase(final Path directory) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val first = new ColumnFamilyOptions();
            val second = new ColumnFamilyOptions()) {
            val handles = new ArrayList<ColumnFamilyHandle>();

            try (val database = RocksDB.open(options,
                    directory.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)),
                    handles);
                val writes = new WriteOptions().setDisableWAL(true);
                val flush = new FlushOptions().setWaitForFlush(true)) {
                database.put(handles.get(0),
                        writes,
                        "td18-schema".getBytes(StandardCharsets.UTF_8),
                        "joint-selection-v3".getBytes(StandardCharsets.UTF_8));
                database.flush(flush, handles);
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }
}

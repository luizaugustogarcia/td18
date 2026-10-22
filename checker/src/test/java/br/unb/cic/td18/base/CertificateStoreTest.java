package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateStoreTest {
    private static final byte[] OBSTRUCTIONS =
            "obstructions".getBytes(StandardCharsets.UTF_8);

    @Test
    void readsRawWitnessesAndSidecarManifests(
            @TempDir final Path directory
    ) throws Exception {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var moves = List.of(Cycle.of(0, 1, 2));
        final var row = new CertificateRowManifest(1, "(3-O)", 1,
                1, 1, 1, 0, 1, 0,
                CertificateCodec.checksumContribution(pair, moves), true);
        final var avoidance = new AvoidancePhaseManifest(1, 204,
                0, 0, 0, 0, 0, 0, 0, true);
        writeDatabase(directory, pair, moves);
        writeManifests(directory, row, avoidance);

        System.setProperty(
                "td18.certificateStatsPeriodSeconds", "0");
        try (final var store = new CertificateStore(directory)) {
            assertEquals(moves, store.require(pair, 1));
            final var batch = store.find(
                    new CyclicTargetPair[]{pair}, 1);
            assertEquals(1, batch.size());
            assertTrue(batch.getFirst().isPresent());
            assertEquals(moves, batch.getFirst().orElseThrow());
            assertEquals(row, store.requireRowManifest(1));
            assertEquals(List.of(avoidance), store.avoidanceManifests());
            assertFalse(store.findResidual(new ResidualObligation(
                    1, 1, pair)).isPresent());
        } finally {
            System.clearProperty(
                    "td18.certificateStatsPeriodSeconds");
        }
    }

    private static void writeDatabase(
            final Path directory,
            final CyclicTargetPair pair,
            final List<Cycle> moves
    ) {
        RocksDB.loadLibrary();
        final var handles = new ArrayList<ColumnFamilyHandle>();
        final var options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
        final var witnessOptions = new ColumnFamilyOptions();
        final var obstructionOptions = new ColumnFamilyOptions();
        RocksDB database = null;
        try {
            database = RocksDB.open(options, directory.toString(), List.of(
                    new ColumnFamilyDescriptor(
                            RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                    new ColumnFamilyDescriptor(
                            OBSTRUCTIONS, obstructionOptions)), handles);
            database.put(handles.get(0), CertificateCodec.keyFor(pair),
                    CertificateCodec.valueFor(moves));
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot create test certificate database", failure);
        } finally {
            handles.forEach(ColumnFamilyHandle::close);
            if (database != null) {
                database.close();
            }
            obstructionOptions.close();
            witnessOptions.close();
            options.close();
        }
    }

    private static void writeManifests(
            final Path directory,
            final CertificateRowManifest row,
            final AvoidancePhaseManifest avoidance
    ) throws Exception {
        final var manifests = directory.resolve("proof-manifests");
        Files.createDirectories(manifests);
        Files.write(manifests.resolve("row-00001.bin"), row.encoded());
        Files.write(manifests.resolve("avoidance-00001-00204.bin"),
                avoidance.encoded());
    }
}

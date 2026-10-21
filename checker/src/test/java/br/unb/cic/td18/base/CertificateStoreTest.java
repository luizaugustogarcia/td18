package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateStoreTest {
    @Test
    void readsVersionedRecordsAndReplaysWitnesses(
            @TempDir final Path directory
    ) {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var moves = List.of(Cycle.of(0, 1, 2));
        final var row = new CertificateRowManifest(1, "(3-O)", 1,
                1, 1, 1, 0, 1, 0,
                CertificateCodec.checksumContribution(pair, moves), true);
        final var avoidance = new AvoidancePhaseManifest(1, 204,
                0, 0, 0, 0, 0, 0, 0, true);
        writeDatabase(directory, pair, moves, row, avoidance);

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
                    1, "(3-O)", 1, pair)).isPresent());
        } finally {
            System.clearProperty(
                    "td18.certificateStatsPeriodSeconds");
        }
    }

    private static void writeDatabase(
            final Path directory,
            final CyclicTargetPair pair,
            final List<Cycle> moves,
            final CertificateRowManifest row,
            final AvoidancePhaseManifest avoidance
    ) {
        RocksDB.loadLibrary();
        try (final var options = new Options().setCreateIfMissing(true);
             final var database = RocksDB.open(
                     options, directory.toAbsolutePath().toString())) {
            database.put(new byte[]{0, 'T', 'D', '1', '8'}, new byte[]{5});
            database.put(CertificateCodec.keyFor(pair),
                    CertificateCodec.valueFor(moves));
            database.put(row.key(), row.value());
            database.put(avoidance.key(), avoidance.value());
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot create test certificate database", failure);
        }
    }
}

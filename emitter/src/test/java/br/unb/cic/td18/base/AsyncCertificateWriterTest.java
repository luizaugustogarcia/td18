package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCertificateWriterTest {
    @Test
    void writesACertificate(@TempDir final Path directory) throws Exception {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var move = Cycle.of(0, 1, 2);
        final var manifest = new CertificateRowManifest(
                7, "(3-O)", 2, 1, 1, 1, 0, 1, 0,
                CertificateCodec.checksumContribution(pair, List.of(move)), true);

        try (final var writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(pair, List.of(move));
            writer.enqueueManifest(manifest);
        }

        try (final var options = new Options();
             final var database = RocksDB.openReadOnly(
                     options, directory.toString())) {
            assertEquals(List.of(move), CertificateCodec.decodeValue(
                    database.get(CertificateCodec.keyFor(pair))));
            final var restoredManifest = CertificateRowManifest.decode(
                    manifest.key(), database.get(manifest.key()));
            assertEquals(manifest, restoredManifest);
            assertTrue(restoredManifest.complete());
        }
    }

    @Test
    void rejectsAnUnversionedNonemptyDatabase(@TempDir final Path directory)
            throws Exception {
        RocksDB.loadLibrary();
        try (final var options = new Options().setCreateIfMissing(true);
             final var database = RocksDB.open(options, directory.toString())) {
            database.put(new byte[]{9}, new byte[]{9});
        }

        assertThrows(IllegalStateException.class,
                () -> new AsyncCertificateWriter(directory, 60));
    }
}

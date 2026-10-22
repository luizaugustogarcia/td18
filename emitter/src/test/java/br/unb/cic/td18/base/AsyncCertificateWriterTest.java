package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncCertificateWriterTest {
    @Test
    void writesRawTd16WitnessAndSidecarManifest(
            @TempDir final Path directory
    ) throws Exception {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var move = Cycle.of(0, 1, 2);
        final var manifest = new CertificateRowManifest(
                7, "(3-O)", 2, 1, 1, 1, 0, 1, 0,
                CertificateCodec.checksumContribution(
                        pair, List.of(move)), true);

        try (final var writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(pair, List.of(move));
            writer.enqueueManifest(manifest);
        }

        assertEquals(List.of(move), CertificateCodec.decodeValue(
                get(directory, RocksDB.DEFAULT_COLUMN_FAMILY,
                        CertificateCodec.keyFor(pair))));
        assertNull(get(directory,
                AsyncCertificateWriter.OBSTRUCTIONS_COLUMN_FAMILY,
                CertificateCodec.keyFor(pair)));
        assertEquals(manifest,
                new CertificateManifestStore(directory).requireRow(7));
        assertColumnFamilies(directory);
    }

    @Test
    void writesAKeyOnlyObstruction(@TempDir final Path directory)
            throws Exception {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var obligation = new ResidualObligation(130, 8, pair);

        try (final var writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueueResidual(obligation);
        }

        assertArrayEquals(new byte[0], get(directory,
                AsyncCertificateWriter.OBSTRUCTIONS_COLUMN_FAMILY,
                obligation.key()));
        assertNull(get(directory, RocksDB.DEFAULT_COLUMN_FAMILY,
                obligation.key()));
    }

    @Test
    void writesAvoidanceObstructionAndSidecarManifest(
            @TempDir final Path directory
    ) throws Exception {
        final int[] symbols = IntStream.range(0, 19).toArray();
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(symbols)),
                Cycle.of(symbols));
        final var miss = new AvoidanceSearchMiss(10, pair);
        final var manifest = new AvoidancePhaseManifest(130, 134,
                1, 10, 1, 0, 0, 1, 42, false);

        try (final var writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueueAvoidanceMiss(miss);
            writer.enqueueAvoidanceManifest(manifest);
        }

        assertArrayEquals(new byte[0], get(directory,
                AsyncCertificateWriter.OBSTRUCTIONS_COLUMN_FAMILY,
                miss.key()));
        assertEquals(List.of(manifest),
                new CertificateManifestStore(directory).avoidanceManifests());
    }

    @Test
    void rejectsTheOldSingleColumnFamilyFormat(
            @TempDir final Path directory
    ) throws Exception {
        RocksDB.loadLibrary();
        try (final var options = new Options().setCreateIfMissing(true);
             final var database = RocksDB.open(
                     options, directory.toString())) {
            database.put(new byte[]{1}, new byte[]{1});
        }

        assertThrows(IllegalStateException.class,
                () -> new AsyncCertificateWriter(directory, 60));
    }

    private static byte[] get(final Path directory,
                              final byte[] family,
                              final byte[] key) throws Exception {
        try (final var witnessOptions = new ColumnFamilyOptions();
             final var obstructionOptions = new ColumnFamilyOptions();
             final var options = new DBOptions()) {
            final var handles = new ArrayList<ColumnFamilyHandle>();
            try (final var database = RocksDB.openReadOnly(
                    options, directory.toString(), List.of(
                            new ColumnFamilyDescriptor(
                                    RocksDB.DEFAULT_COLUMN_FAMILY,
                                    witnessOptions),
                            new ColumnFamilyDescriptor(
                                    AsyncCertificateWriter
                                            .OBSTRUCTIONS_COLUMN_FAMILY,
                                    obstructionOptions)), handles)) {
                final var handle = Arrays.equals(
                        family, RocksDB.DEFAULT_COLUMN_FAMILY)
                        ? handles.get(0) : handles.get(1);
                return database.get(handle, key);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }

    private static void assertColumnFamilies(final Path directory)
            throws Exception {
        try (final var options = new Options()) {
            final var names = RocksDB.listColumnFamilies(
                    options, directory.toString());
            assertEquals(2, names.size());
            assertArrayEquals(RocksDB.DEFAULT_COLUMN_FAMILY, names.get(0));
            assertArrayEquals(
                    AsyncCertificateWriter.OBSTRUCTIONS_COLUMN_FAMILY,
                    names.get(1));
        }
    }
}

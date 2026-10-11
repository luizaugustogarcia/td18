package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateCodec;
import br.unb.cic.tdp.base.CertificateValidator;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Persistent sorting witnesses indexed by canonical class key. */
public final class WitnessStore implements AutoCloseable {
    static {
        RocksDB.loadLibrary();
    }

    private final boolean writable;
    private final RocksStoreResources resources;
    private final RocksDB database;

    public WitnessStore(final Path directory, final boolean writable) {
        this.writable = writable;
        resources = new RocksStoreResources(writable);
        try {
            if (writable) {
                Files.createDirectories(directory);
                database = RocksDB.open(resources.options, directory.toAbsolutePath().toString());
            } else {
                database = RocksDB.openReadOnly(
                        resources.options, directory.toAbsolutePath().toString());
            }
        } catch (final Exception failure) {
            resources.close();
            throw new IllegalStateException("Cannot open witness store " + directory, failure);
        }
    }

    public void put(final CyclicTargetPair pair, final List<Cycle> moves) {
        if (!writable) {
            throw new IllegalStateException("Witness store is read-only");
        }
        final var canonical = pair.canonicalRepresentative();
        CertificateValidator.validate(canonical, moves, moves.size());
        try {
            database.put(CertificateCodec.keyFor(canonical),
                    CertificateCodec.valueFor(moves));
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot persist sorting witness", failure);
        }
    }

    public Optional<List<Cycle>> find(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        final var canonical = pair.canonicalRepresentative();
        final byte[] encoded;
        try {
            encoded = database.get(resources.reads, CertificateCodec.keyFor(canonical));
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query witness store", failure);
        }
        if (encoded == null) {
            return Optional.empty();
        }
        final var moves = CertificateCodec.decodeValue(encoded);
        CertificateValidator.validate(canonical, moves, maximumMoves);
        return Optional.of(moves);
    }

    public boolean contains(final CyclicTargetPair pair) {
        final var canonical = pair.canonicalRepresentative();
        try {
            return database.get(resources.reads,
                    CertificateCodec.keyFor(canonical)) != null;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query witness store", failure);
        }
    }

    @Override
    public void close() {
        database.close();
        resources.close();
    }
}

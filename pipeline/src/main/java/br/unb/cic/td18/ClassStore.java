package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateCodec;
import br.unb.cic.tdp.base.CyclicTargetPair;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/** Persistent set of canonical extended-toric class representatives. */
public final class ClassStore implements AutoCloseable {
    private static final byte[] PRESENT = {1};
    private static final int LOCK_COUNT = 1 << 10;

    static {
        RocksDB.loadLibrary();
    }

    private final int domainSize;
    private final boolean writable;
    private final Options options;
    private final ReadOptions readOptions = new ReadOptions()
            .setVerifyChecksums(true)
            .setFillCache(false);
    private final RocksDB database;
    private final Object[] locks = new Object[LOCK_COUNT];

    public ClassStore(
            final Path directory,
            final int domainSize,
            final boolean writable
    ) {
        if (domainSize <= 1 || domainSize > 32) {
            throw new IllegalArgumentException("Domain size must belong to 2..32");
        }
        this.domainSize = domainSize;
        this.writable = writable;
        for (var index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        options = new Options()
                .setCreateIfMissing(writable)
                .setMaxOpenFiles(-1);
        try {
            if (writable) {
                Files.createDirectories(directory);
                database = RocksDB.open(options, directory.toAbsolutePath().toString());
            } else {
                database = RocksDB.openReadOnly(
                        options, directory.toAbsolutePath().toString());
            }
        } catch (final Exception failure) {
            readOptions.close();
            options.close();
            throw new IllegalStateException("Cannot open class store " + directory, failure);
        }
    }

    public int domainSize() {
        return domainSize;
    }

    /** Adds a class and reports whether it was absent. */
    public boolean add(final CyclicTargetPair pair) {
        requireWritable();
        final var key = keyFor(pair);
        final var lock = locks[(java.util.Arrays.hashCode(key) & Integer.MAX_VALUE)
                % locks.length];
        synchronized (lock) {
            try {
                if (database.get(key) != null) {
                    return false;
                }
                database.put(key, PRESENT);
                return true;
            } catch (final RocksDBException failure) {
                throw new IllegalStateException("Cannot update class store", failure);
            }
        }
    }

    public boolean contains(final CyclicTargetPair pair) {
        final var key = keyFor(pair);
        try {
            return database.get(readOptions, key) != null;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query class store", failure);
        }
    }

    public void forEach(final Consumer<CyclicTargetPair> consumer) {
        Objects.requireNonNull(consumer);
        try (final var iterator = database.newIterator(readOptions)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                consumer.accept(CertificateCodec.pairForKey(
                        iterator.key(), domainSize));
                iterator.next();
            }
            iterator.status();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot iterate class store", failure);
        }
    }

    public Summary summarize() {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        long count = 0;
        try (final var iterator = database.newIterator(readOptions)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                digest.update(iterator.key());
                count++;
                iterator.next();
            }
            iterator.status();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot summarize class store", failure);
        }
        return new Summary(count, HexFormat.of().formatHex(digest.digest()));
    }

    private byte[] keyFor(final CyclicTargetPair pair) {
        Objects.requireNonNull(pair);
        if (pair.getBeta().size() != domainSize) {
            throw new IllegalArgumentException(
                    "Pair domain " + pair.getBeta().size()
                            + " does not match store domain " + domainSize);
        }
        return CertificateCodec.keyFor(pair.canonicalRepresentative());
    }

    private void requireWritable() {
        if (!writable) {
            throw new IllegalStateException("Class store is read-only");
        }
    }

    @Override
    public void close() {
        database.close();
        readOptions.close();
        options.close();
    }

    public record Summary(long count, String sha256) {
    }
}

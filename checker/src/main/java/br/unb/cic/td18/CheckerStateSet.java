package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateCodec;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fresh checker-owned disk set; never opens or resumes an emitter work database.
 * Keys contain version, budget, domain and the canonical five-bit instance.
 * Sealing ends writes before the checker streams the resulting set.
 */
final class CheckerStateSet implements AutoCloseable {
    private static final byte VERSION = 1;
    private static final byte[] EMPTY = {};
    private static final int BUFFER_KEYS = 16_384;
    private static final int BATCH_SIZE = 64;

    static {
        RocksDB.loadLibrary();
    }

    private final Options options = new Options().setCreateIfMissing(true)
            .setWriteBufferSize(32L << 20).setMaxWriteBufferNumber(2)
            .setMaxBackgroundJobs(2).setMaxOpenFiles(128);
    private final WriteOptions writes = new WriteOptions().setDisableWAL(true);
    private final RocksDB database;
    private final Set<ByteBuffer> pending = new HashSet<>();
    private Inventory sealed;

    record Inventory(long count) { }

    CheckerStateSet(final Path directory) {
        try {
            // Refuse even an empty pre-existing directory: only this invocation
            // may populate proof reconstruction scratch space.
            Files.createDirectory(directory);
            database = RocksDB.open(options, directory.toString());
        } catch (final IOException | RocksDBException | RuntimeException failure) {
            writes.close();
            options.close();
            throw new IllegalStateException("Checker requires a fresh state directory: "
                    + directory, failure);
        }
    }

    void add(final ObstructionAvoidance.SearchState state) {
        final var canonical = state.pair().canonicalRepresentative();
        final byte[] raw = CertificateCodec.keyFor(canonical);
        final byte[] key = new byte[3 + raw.length];
        key[0] = VERSION;
        key[1] = (byte) state.maximumMoves();
        key[2] = (byte) canonical.getBeta().size();
        System.arraycopy(raw, 0, key, 3, raw.length);
        synchronized (this) {
            if (sealed != null) {
                throw new IllegalStateException("Cannot add to a sealed checker set");
            }
            pending.add(ByteBuffer.wrap(key));
            if (pending.size() >= BUFFER_KEYS) {
                flushPending();
            }
        }
    }

    private void flushPending() {
        try (final var batch = new WriteBatch()) {
            for (final var key : pending) {
                batch.put(key.array(), EMPTY);
            }
            database.write(writes, batch);
            pending.clear();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot persist checker states", failure);
        }
    }

    boolean contains(final ObstructionAvoidance.SearchState state) {
        if (sealed == null) {
            throw new IllegalStateException("Membership requires a sealed checker set");
        }
        final var pair = state.pair().canonicalRepresentative();
        final byte[] raw = CertificateCodec.keyFor(pair);
        final byte[] key = ByteBuffer.allocate(3 + raw.length).put(VERSION)
                .put((byte) state.maximumMoves()).put((byte) pair.getBeta().size())
                .put(raw).array();
        try {
            return database.get(key) != null;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query checker states", failure);
        }
    }

    synchronized Inventory seal() {
        if (sealed != null) {
            return sealed;
        }
        flushPending();
        long count = 0;
        try (final var reads = new ReadOptions().setFillCache(false);
             final var iterator = database.newIterator(reads)) {
            for (iterator.seek(new byte[]{VERSION}); iterator.isValid(); iterator.next()) {
                final byte[] key = iterator.key();
                requireKey(key, iterator.value());
                count++;
            }
            iterator.status();
            try (final var flush = new FlushOptions().setWaitForFlush(true)) {
                database.flush(flush);
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot seal checker states", failure);
        }
        sealed = new Inventory(count);
        return sealed;
    }

    void forEachBatch(final Consumer<List<ObstructionAvoidance.SearchState>> consumer) {
        final Inventory expected = seal();
        long count = 0;
        try (final var reads = new ReadOptions().setFillCache(false);
             final var iterator = database.newIterator(reads)) {
            var batch = new ArrayList<ObstructionAvoidance.SearchState>(BATCH_SIZE);
            for (iterator.seek(new byte[]{VERSION}); iterator.isValid(); iterator.next()) {
                final byte[] key = iterator.key();
                requireKey(key, iterator.value());
                count++;
                batch.add(new ObstructionAvoidance.SearchState(
                        Byte.toUnsignedInt(key[1]), CertificateCodec.pairForKey(
                        Arrays.copyOfRange(key, 3, key.length), Byte.toUnsignedInt(key[2]))));
                if (batch.size() == BATCH_SIZE) {
                    consumer.accept(List.copyOf(batch));
                    batch = new ArrayList<>(BATCH_SIZE);
                }
            }
            iterator.status();
            if (!batch.isEmpty()) {
                consumer.accept(List.copyOf(batch));
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot scan checker states", failure);
        }
        if (count != expected.count()) {
            throw new IllegalStateException("Checker scan did not visit every generated state");
        }
    }

    private static void requireKey(final byte[] key, final byte[] value) {
        if (key.length < 4 || key[0] != VERSION || key[1] < 8 || key[1] > 10
                || key[2] < 1 || key[2] > 19 || value.length != 0) {
            throw new IllegalStateException("Malformed checker state");
        }
    }

    @Override
    public void close() {
        database.close();
        writes.close();
        options.close();
    }
}

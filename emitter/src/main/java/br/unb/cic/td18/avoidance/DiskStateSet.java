package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;
import org.rocksdb.*;

/** Bounded disk deduplication of a regenerated wave of valid-pair classes. */
public final class DiskStateSet implements AutoCloseable {
    private static final byte VERSION = 1;
    private static final byte[] EMPTY = {};
    private static final int BUFFER_KEYS = 16384;
    static { RocksDB.loadLibrary(); }
    private final Options options = new Options().setCreateIfMissing(true)
            .setWriteBufferSize(32L << 20).setMaxWriteBufferNumber(2).setMaxBackgroundJobs(2).setMaxOpenFiles(128);
    private final WriteOptions writes = new WriteOptions().setDisableWAL(true);
    private final RocksDB database;
    private final HashSet<ByteBuffer> pending = new HashSet<>();
    private long size = -1;
    private boolean closed;

    public record State(CyclicTargetPair pair, int maximumMoves) {
        public State {
            if (maximumMoves < 1 || maximumMoves > 10) throw new IllegalArgumentException("Budget must be 1..10");
            for (int symbol : pair.getBeta().getSymbols()) {
                if (pair.getOmega().image(symbol) == symbol)
                    throw new IllegalArgumentException("Reconstruction states must be fixed-point-free");
            }
        }
    }

    public DiskStateSet(Path directory) {
        try {
            Files.createDirectory(directory);
            database = RocksDB.open(options, directory.toString());
        } catch (IOException | RocksDBException | RuntimeException failure) {
            writes.close(); options.close();
            throw new IllegalStateException("State set needs a fresh directory: " + directory, failure);
        }
    }

    public synchronized void add(CyclicTargetPair pair, int maximumMoves) {
        requireOpen();
        if (size >= 0) throw new IllegalStateException("State set is sealed");
        var state = new State(pair.canonicalRepresentative(), maximumMoves);
        byte[] raw = CertificateCodec.keyFor(state.pair());
        byte[] key = ByteBuffer.allocate(raw.length + 3).put(VERSION).put((byte) maximumMoves)
                .put((byte) pair.getBeta().size()).put(raw).array();
        pending.add(ByteBuffer.wrap(key));
        if (pending.size() >= BUFFER_KEYS) flushPending();
    }

    private void flushPending() {
        try (var batch = new WriteBatch()) {
            for (var key : pending) batch.put(key.array(), EMPTY);
            database.write(writes, batch);
            pending.clear();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot persist reconstruction states", failure); }
    }

    public synchronized long seal() {
        requireOpen();
        if (size >= 0) return size;
        flushPending();
        long count = 0;
        try (var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(reads);
             var flush = new FlushOptions().setWaitForFlush(true)) {
            database.flush(flush);
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                decode(iterator.key(), iterator.value());
                count = Math.addExact(count, 1);
            }
            iterator.status();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot seal reconstruction states", failure); }
        size = count;
        return count;
    }

    public void forEach(Consumer<State> consumer) {
        long expected = seal();
        long scanned = 0;
        try (var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                consumer.accept(decode(iterator.key(), iterator.value()));
                scanned = Math.addExact(scanned, 1);
            }
            iterator.status();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot scan reconstruction states", failure); }
        if (scanned != expected) throw new IllegalStateException("Reconstruction scan missed states");
    }

    private static State decode(byte[] key, byte[] value) {
        if (key.length < 4 || key[0] != VERSION || value.length != 0)
            throw new IllegalStateException("Malformed reconstruction state");
        return new State(CertificateCodec.pairForKey(Arrays.copyOfRange(key, 3, key.length),
                Byte.toUnsignedInt(key[2])), Byte.toUnsignedInt(key[1]));
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("State set is closed"); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; database.close(); writes.close(); options.close();
    }
}

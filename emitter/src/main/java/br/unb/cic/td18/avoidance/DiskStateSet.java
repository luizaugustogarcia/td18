package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.runtime.BoundedTasks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.rocksdb.*;

/** Bounded disk deduplication of a regenerated wave of valid-pair classes. */
public final class DiskStateSet implements AutoCloseable {
    private static final byte VERSION = 2;
    private static final byte[] EMPTY = {};
    private static final int GENERATED_BATCH_KEYS = 4096;
    private static final int ADMISSION_STOPPED = Integer.MIN_VALUE;
    static { RocksDB.loadLibrary(); }
    private final int bufferKeys = positiveProperty("td18.reconstructionBufferKeys", 16777216);
    private final Options options;
    private final WriteOptions writes;
    private final RocksDB database;
    private final StateSetWriter[] writers;
    private final AtomicInteger admissions = new AtomicInteger();
    private final AtomicReference<Throwable> generationFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> persistenceFailure = new AtomicReference<>();
    private long size = -1;
    private volatile boolean closed;
    private final boolean readOnly;

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
        this(directory, false, -1);
    }

    /** Reopens a sealed checkpoint, checking every record and its exact inventory count. */
    public static DiskStateSet openSealed(Path directory, long expectedCount) {
        if (expectedCount < 0) throw new IllegalArgumentException("Nonnegative checkpoint count required");
        return new DiskStateSet(directory, true, expectedCount);
    }

    private DiskStateSet(Path directory, boolean readOnly, long expectedCount) {
        this.readOnly = readOnly;
        int writerCount = readOnly ? 0 : Math.min(bufferKeys, positiveProperty("td18.reconstructionWriters",
                Math.min(8, Runtime.getRuntime().availableProcessors())));
        int queueBatches = readOnly ? 0 : positiveProperty("td18.reconstructionQueueBatches", 16384);
        options = new Options().setCreateIfMissing(true).setWriteBufferSize(256L << 20)
                .setMaxWriteBufferNumber(4).setMaxBackgroundJobs(Math.max(2, Math.min(8, writerCount)))
                .setMaxOpenFiles(128);
        writes = new WriteOptions().setDisableWAL(true);
        writers = new StateSetWriter[writerCount];
        RocksDB opened = null;
        try {
            if (readOnly) {
                if (!Files.isRegularFile(directory.resolve("CURRENT")))
                    throw new IllegalStateException("Missing checkpoint state set: " + directory);
                options.setCreateIfMissing(false);
                opened = RocksDB.openReadOnly(options, directory.toString());
            } else {
                Files.createDirectory(directory);
                opened = RocksDB.open(options, directory.toString());
            }
            database = opened;
            for (int i = 0; i < writerCount; i++) {
                var pending = new HashSet<ByteBuffer>();
                int limit = ceilingDivide(bufferKeys, writerCount);
                writers[i] = new StateSetWriter(ceilingDivide(queueBatches, writerCount),
                        "reconstruction-writer-" + i, keys -> {
                    for (byte[] key : keys) {
                        pending.add(ByteBuffer.wrap(key));
                        if (pending.size() >= limit) flushPending(pending);
                    }
                }, () -> flushPending(pending), persistenceFailure);
            }
            if (readOnly && seal() != expectedCount)
                throw new IllegalStateException("Checkpoint state count disagrees at " + directory
                        + ": expected " + expectedCount + ", found " + size);
        } catch (IOException | RocksDBException | RuntimeException failure) {
            for (var writer : writers) if (writer != null) {
                try { writer.finish(); } catch (RuntimeException error) { failure.addSuppressed(error); }
            }
            if (opened != null) opened.close();
            writes.close(); options.close();
            throw new IllegalStateException("Cannot open reconstruction state set: " + directory, failure);
        }
    }

    public void add(CyclicTargetPair pair, int maximumMoves) {
        beginGeneration();
        try {
            submit(List.of(encode(new State(pair.canonicalRepresentative(), maximumMoves))));
        } catch (RuntimeException | Error failure) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        } finally { admissions.decrementAndGet(); }
    }

    /**
     * Inserts one synchronous generation with bounded, method-local key batches.
     * Canonicalization stays in producers; deterministic writer shards own all
     * deduplication and disk writes. The final partial batch is queued before
     * return. The generator must not retain or concurrently use
     * its output callback. Callers must join all generators before sealing.
     * A failed generation makes the incomplete set unsealable.
     */
    public void addGenerated(int maximumMoves, Consumer<Consumer<CyclicTargetPair>> generator) {
        if (maximumMoves < 1 || maximumMoves > 10) throw new IllegalArgumentException("Budget must be 1..10");
        java.util.Objects.requireNonNull(generator);
        beginGeneration();
        var batch = new ArrayList<byte[]>(GENERATED_BATCH_KEYS);
        try {
            generator.accept(pair -> {
                batch.add(encode(new State(pair.canonicalRepresentative(), maximumMoves)));
                if (batch.size() == GENERATED_BATCH_KEYS) {
                    submit(batch);
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) submit(batch);
        } catch (RuntimeException | Error failure) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        } finally { admissions.decrementAndGet(); }
    }

    private void submit(List<byte[]> keys) {
        if (writers.length == 1) { writers[0].submit(keys); return; }
        var partitions = new ArrayList<List<byte[]>>(writers.length);
        for (int i = 0; i < writers.length; i++) partitions.add(new ArrayList<>());
        boolean powerOfTwo = (writers.length & (writers.length - 1)) == 0;
        for (byte[] key : keys) {
            int hash = Arrays.hashCode(key);
            hash ^= hash >>> 16;
            int shard = powerOfTwo ? hash & (writers.length - 1) : Math.floorMod(hash, writers.length);
            partitions.get(shard).add(key);
        }
        for (int i = 0; i < writers.length; i++) writers[i].submit(partitions.get(i));
    }

    static byte[] encode(State state) {
        var pair = state.pair().canonicalRepresentative();
        byte[] raw = CertificateCodec.keyFor(pair);
        return ByteBuffer.allocate(raw.length + 3).put(VERSION).put((byte) state.maximumMoves())
                .put((byte) pair.getBeta().size()).put(raw).array();
    }

    private void flushPending(HashSet<ByteBuffer> pending) {
        if (pending.isEmpty()) return;
        try (var batch = new WriteBatch()) {
            for (var key : pending) batch.put(key.array(), EMPTY);
            database.write(writes, batch);
            pending.clear();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot persist reconstruction states", failure); }
    }

    public synchronized long seal() {
        requireOpen();
        requireSuccessfulGeneration();
        if (size >= 0) return size;
        if (!readOnly) {
            stopAdmissions();
            finishWriters();
            requireSuccessfulGeneration();
        }
        long count = 0;
        try (var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(reads);
             var flush = new FlushOptions().setWaitForFlush(true)) {
            if (!readOnly) database.flush(flush);
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

    /** Reads deterministic keys with bounded parallel callbacks, waiting for all work to finish. */
    public void forEachConcurrently(int workers, Consumer<State> consumer) {
        try (var tasks = new BoundedTasks(workers, "reconstruction-")) {
            forEach(state -> tasks.submit(() -> consumer.accept(state)));
        }
    }

    public static int lookupBatchSize() { return positiveProperty("td18.certificateLookupBatchSize", 262144); }

    private static int positiveProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int ceilingDivide(int value, int divisor) {
        return Math.toIntExact(((long) value + divisor - 1) / divisor);
    }

    /** Deterministic bounded batches, including the final partial batch. */
    public void forEachBatch(Consumer<List<State>> consumer) {
        int limit = lookupBatchSize();
        var batch = new ArrayList<State>(limit);
        forEach(state -> {
            batch.add(state);
            if (batch.size() == limit) { consumer.accept(List.copyOf(batch)); batch.clear(); }
        });
        if (!batch.isEmpty()) consumer.accept(List.copyOf(batch));
    }

    /** Bulk replay precedes bounded submission; cached classes never enter the worker queue. */
    public void forEachConcurrently(int workers, Function<List<State>, boolean[]> lookup,
                                    Consumer<State> cached, Consumer<State> missing) {
        if (lookup == null) { forEachConcurrently(workers, missing); return; }
        try (var tasks = new BoundedTasks(workers, "reconstruction-")) {
            forEachBatch(batch -> {
                tasks.check();
                boolean[] hits = lookup.apply(batch);
                if (hits == null || hits.length != batch.size())
                    throw new IllegalStateException("Incomplete reconstruction batch lookup");
                for (int i = 0; i < batch.size(); i++) if (hits[i]) cached.accept(batch.get(i));
                for (int i = 0; i < batch.size(); i++) {
                    if (hits[i]) continue;
                    State state = batch.get(i);
                    tasks.submit(() -> missing.accept(state));
                }
            });
        }
    }

    static State decode(byte[] key, byte[] value) {
        if (key.length < 4 || key[0] != VERSION || value.length != 0)
            throw new IllegalStateException("Malformed reconstruction state");
        return new State(CertificateCodec.pairForKey(Arrays.copyOfRange(key, 3, key.length),
                Byte.toUnsignedInt(key[2])), Byte.toUnsignedInt(key[1]));
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("State set is closed"); }
    private void requireSuccessfulGeneration() {
        if (generationFailure.get() != null)
            throw new IllegalStateException("Reconstruction generation failed", generationFailure.get());
        if (persistenceFailure.get() != null)
            throw new IllegalStateException("Reconstruction writer failed", persistenceFailure.get());
    }
    private void beginGeneration() {
        requireOpen();
        requireSuccessfulGeneration();
        if (readOnly) throw new IllegalStateException("State set is sealed");
        for (;;) {
            int active = admissions.get();
            if (active < 0) throw new IllegalStateException("State set is sealed");
            if (active == Integer.MAX_VALUE) throw new IllegalStateException("Too many reconstruction producers");
            if (admissions.compareAndSet(active, active + 1)) return;
        }
    }
    private void stopAdmissions() {
        if (admissions.get() == ADMISSION_STOPPED) return;
        if (!admissions.compareAndSet(0, ADMISSION_STOPPED))
            throw new IllegalStateException("Join all reconstruction producers before sealing or closing");
    }
    private void finishWriters() {
        RuntimeException failure = null;
        for (var writer : writers) if (writer != null) {
            try { writer.finish(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        stopAdmissions();
        closed = true;
        try { finishWriters(); }
        finally { database.close(); writes.close(); options.close(); }
    }
}

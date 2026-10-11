package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateCodec;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private final RocksStoreResources resources;
    private final RocksDB database;
    private final Object[] locks = new Object[LOCK_COUNT];
    private final InFlightTasks<ExtendedToricClassKey, Boolean> processing =
            new InFlightTasks<>();

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
                if (database.get(resources.reads, key) != null) {
                    return false;
                }
                database.put(resources.writes, key, PRESENT);
                return true;
            } catch (final RocksDBException failure) {
                throw new IllegalStateException("Cannot update class store", failure);
            }
        }
    }

    /** Stores a batch without membership reads; existing values are replaced. */
    public void putAll(final List<CyclicTargetPair> pairs) {
        requireWritable();
        Objects.requireNonNull(pairs);
        if (pairs.isEmpty()) {
            return;
        }
        try (final var writes = new WriteBatch()) {
            for (final var pair : pairs) {
                writes.put(keyFor(pair), PRESENT);
            }
            database.write(resources.writes, writes);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot update class-store batch", failure);
        }
    }

    public boolean contains(final CyclicTargetPair pair) {
        final var key = keyFor(pair);
        try {
            return database.get(resources.reads, key) != null;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query class store", failure);
        }
    }

    /**
     * Claims a class without blocking other classes or duplicate callers.
     * Returns false for completed or in-flight classes. Only the owner runs
     * the action and records completion, and only after the action succeeds.
     * A failed owner remains unmarked and can be retried. Callers must not
     * checkpoint the owning parent until this action returns successfully.
     * The action must not recursively process the same class in this store.
     */
    public boolean processIfAbsent(final CyclicTargetPair pair, final Runnable action) {
        requireWritable();
        Objects.requireNonNull(action);
        final var canonical = Objects.requireNonNull(pair).canonicalRepresentative();
        if (canonical.getBeta().size() != domainSize) {
            throw new IllegalArgumentException("Pair domain does not match store domain");
        }
        return processing.tryRun(canonical.extendedToricClassKey(), () -> {
            if (contains(canonical)) {
                return false;
            }
            action.run();
            add(canonical);
            return true;
        }).orElse(false);
    }

    /** Exact batched membership; results retain the input order, including duplicates. */
    public boolean[] containsMany(final List<CyclicTargetPair> pairs) {
        Objects.requireNonNull(pairs);
        final var present = new boolean[pairs.size()];
        if (pairs.isEmpty()) {
            return present;
        }
        final var keys = new ArrayList<byte[]>(pairs.size());
        pairs.forEach(pair -> keys.add(keyFor(pair)));
        try {
            final var values = database.multiGetAsList(resources.reads, keys);
            for (var index = 0; index < values.size(); index++) {
                present[index] = values.get(index) != null;
            }
            return present;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query class-store batch", failure);
        }
    }

    /** Exact batched membership for already encoded canonical keys. */
    boolean[] containsEncoded(final List<EncodedClassKey> keys) {
        Objects.requireNonNull(keys);
        final var present = new boolean[keys.size()];
        if (keys.isEmpty()) {
            return present;
        }
        final var encoded = new ArrayList<byte[]>(keys.size());
        keys.forEach(key -> encoded.add(key.bytes()));
        try {
            final var values = database.multiGetAsList(resources.reads, encoded);
            for (var index = 0; index < values.size(); index++) {
                present[index] = values.get(index) != null;
            }
            return present;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query encoded class-store batch", failure);
        }
    }

    public void forEach(final Consumer<CyclicTargetPair> consumer) {
        Objects.requireNonNull(consumer);
        try (final var iterator = database.newIterator(resources.scans)) {
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

    /**
     * Visits the persisted classes with bounded parallelism.  Iteration and
     * decoding remain single-threaded, while at most four tasks per worker
     * are retained so the store is never materialized in memory.
     */
    public void forEachParallel(
            final int workerCount,
            final Consumer<CyclicTargetPair> consumer
    ) {
        Objects.requireNonNull(consumer);
        forEachBatchParallel(workerCount, 1,
                batch -> consumer.accept(batch.getFirst()));
    }

    /** Visits bounded batches with at most four batches per worker in flight. */
    public void forEachBatchParallel(
            final int workerCount,
            final int batchSize,
            final Consumer<List<CyclicTargetPair>> consumer
    ) {
        if (workerCount < 1) {
            throw new IllegalArgumentException(
                    "Worker count must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        Objects.requireNonNull(consumer);

        final var executor = Executors.newFixedThreadPool(
                workerCount,
                Thread.ofPlatform().daemon().name(
                        "class-store-worker-", 0).factory());
        final CompletionService<Void> completion =
                new ExecutorCompletionService<>(executor);
        final var maximumInFlight = Math.multiplyExact(workerCount, 4);
        long submitted = 0;
        long completed = 0;
        try {
            try (final var iterator = database.newIterator(resources.scans)) {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    final var batch = new ArrayList<CyclicTargetPair>(batchSize);
                    while (iterator.isValid() && batch.size() < batchSize) {
                        batch.add(CertificateCodec.pairForKey(
                                iterator.key(), domainSize));
                        iterator.next();
                    }
                    final var immutableBatch = List.copyOf(batch);
                    completion.submit(() -> {
                        consumer.accept(immutableBatch);
                        return null;
                    });
                    submitted++;
                    if (submitted - completed >= maximumInFlight) {
                        awaitOne(completion);
                        completed++;
                    }
                }
                iterator.status();
            } catch (final RocksDBException failure) {
                throw new IllegalStateException(
                        "Cannot iterate class store", failure);
            }
            while (completed < submitted) {
                awaitOne(completion);
                completed++;
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(
                        Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException(
                            "Class-store workers did not terminate");
                }
            } catch (final InterruptedException failure) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while stopping class-store workers",
                        failure);
            }
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
        try (final var iterator = database.newIterator(resources.scans)) {
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

    private static void awaitOne(
            final CompletionService<Void> completion
    ) {
        try {
            completion.take().get();
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while processing class store", failure);
        } catch (final ExecutionException failure) {
            final var cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Class-store worker failed", cause);
        }
    }

    private void requireWritable() {
        if (!writable) {
            throw new IllegalStateException("Class store is read-only");
        }
    }

    @Override
    public void close() {
        database.close();
        resources.close();
    }

    public record Summary(long count, String sha256) {
    }
}

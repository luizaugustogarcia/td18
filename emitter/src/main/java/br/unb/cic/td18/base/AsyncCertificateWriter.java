package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import org.rocksdb.CompactionStyle;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proof-scoped asynchronous RocksDB certificate sink. Search-completion
 * threads enqueue validated witnesses; one writer owns every database call.
 */
public final class AsyncCertificateWriter implements AutoCloseable {
    private static final byte[] DATABASE_FORMAT_KEY =
            new byte[]{0, 'T', 'D', '1', '8'};
    private static final byte[] DATABASE_FORMAT_VALUE = new byte[]{3};
    private static final long GIBIBYTE = 1L << 30;
    private static final long CERTIFICATE_SST_TARGET_SIZE = GIBIBYTE;
    private static final long CERTIFICATE_BASE_LEVEL_SIZE = 10L * GIBIBYTE;

    static {
        RocksDB.loadLibrary();
    }

    private final Options options = new Options()
            .setCreateIfMissing(true)
            .setCompactionStyle(CompactionStyle.LEVEL)
            .setDisableAutoCompactions(false)
            .setTargetFileSizeBase(CERTIFICATE_SST_TARGET_SIZE)
            .setMaxBytesForLevelBase(CERTIFICATE_BASE_LEVEL_SIZE)
            .setLevel0FileNumCompactionTrigger(4)
            .setMaxBackgroundJobs(4)
            .setMaxSubcompactions(2);
    private final RocksDB database;
    private final LinkedBlockingQueue<Entry> pending = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService writer;
    private final AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    private final Object drainLock = new Object();
    private volatile boolean closed;

    public AsyncCertificateWriter(final Path directory, final long flushPeriodSeconds) {
        if (flushPeriodSeconds <= 0) {
            throw new IllegalArgumentException("Certificate flush period must be positive");
        }
        RocksDB openedDatabase = null;
        try {
            openedDatabase = RocksDB.open(
                    options, directory.toAbsolutePath().toString());
            initializeDatabaseFormat(openedDatabase);
        } catch (final RocksDBException failure) {
            if (openedDatabase != null) {
                openedDatabase.close();
            }
            options.close();
            throw new IllegalStateException("Cannot open certificate database " + directory, failure);
        } catch (final RuntimeException failure) {
            if (openedDatabase != null) {
                openedDatabase.close();
            }
            options.close();
            throw failure;
        }
        database = openedDatabase;
        writer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("certificate-rocksdb-writer-", 0).factory());
        writer.scheduleWithFixedDelay(this::drainFromBackground,
                flushPeriodSeconds, flushPeriodSeconds, TimeUnit.SECONDS);
    }

    private static void initializeDatabaseFormat(final RocksDB database)
            throws RocksDBException {
        final var storedFormat = database.get(DATABASE_FORMAT_KEY);
        if (storedFormat != null) {
            if (!Arrays.equals(storedFormat, DATABASE_FORMAT_VALUE)) {
                throw new IllegalStateException(
                        "Unsupported TD(18) certificate database format");
            }
            return;
        }
        try (final var iterator = database.newIterator()) {
            iterator.seekToFirst();
            if (iterator.isValid()) {
                throw new IllegalStateException(
                        "Refusing to mix an unversioned database with TD(18) certificates");
            }
        }
        database.put(DATABASE_FORMAT_KEY, DATABASE_FORMAT_VALUE);
    }

    /** Nonblocking with respect to the RocksDB writer and GPU completion threads. */
    public void enqueue(final CyclicTargetPair canonicalPair, final List<Cycle> moves) {
        Objects.requireNonNull(canonicalPair);
        Objects.requireNonNull(moves);
        throwIfFailed();
        if (closed) {
            throw new IllegalStateException("Certificate writer is closed");
        }
        pending.add(new Entry(
                CertificateCodec.keyFor(canonicalPair),
                CertificateCodec.valueFor(moves)));
    }

    /** Queues a row marker after all of that row's certificate entries. */
    public void enqueueManifest(final CertificateRowManifest manifest) {
        Objects.requireNonNull(manifest);
        throwIfFailed();
        if (closed) {
            throw new IllegalStateException("Certificate writer is closed");
        }
        pending.add(new Entry(manifest.key(), manifest.value()));
    }

    public void throwIfFailed() {
        final var failure = backgroundFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Certificate writer failed", failure);
        }
    }

    private void drainFromBackground() {
        try {
            drain(true);
        } catch (final Throwable failure) {
            backgroundFailure.compareAndSet(null, failure);
        }
    }

    private void drain(final boolean flush) throws RocksDBException {
        synchronized (drainLock) {
            final var drained = new ArrayList<Entry>();
            pending.drainTo(drained);
            if (!drained.isEmpty()) {
                try (final var batch = new WriteBatch();
                     final var writeOptions = new WriteOptions()) {
                    for (final var entry : drained) {
                        batch.put(entry.key(), entry.value());
                    }
                    database.write(writeOptions, batch);
                }
            }
            if (flush) {
                try (final var flushOptions = new FlushOptions().setWaitForFlush(true)) {
                    database.flush(flushOptions);
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            throwIfFailed();
            return;
        }
        closed = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                writer.shutdownNow();
                writer.awaitTermination(30, TimeUnit.SECONDS);
            }
            drain(true);
            throwIfFailed();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing certificate writer", exception);
        } catch (final RocksDBException exception) {
            throw new IllegalStateException("Cannot finish certificate database", exception);
        } finally {
            database.close();
            options.close();
        }
    }

    private record Entry(byte[] key, byte[] value) {
    }
}

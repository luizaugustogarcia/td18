package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionStyle;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proof-scoped asynchronous sink. The default column family contains Table 2
 * and full-domain witnesses, {@code avoidance-intermediate-witnesses} is a
 * retained legacy namespace, and {@code obstructions} contains key-only
 * residual obligations.
 */
public final class AsyncCertificateWriter implements AutoCloseable {
    static final byte[] OBSTRUCTIONS_COLUMN_FAMILY =
            "obstructions".getBytes(StandardCharsets.UTF_8);
    static final byte[] INTERMEDIATE_WITNESSES_COLUMN_FAMILY =
            "avoidance-intermediate-witnesses"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_VALUE = new byte[0];
    private static final long GIBIBYTE = 1L << 30;
    private static final long CERTIFICATE_SST_TARGET_SIZE = GIBIBYTE;
    private static final long CERTIFICATE_BASE_LEVEL_SIZE = 10L * GIBIBYTE;

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions options;
    private final ColumnFamilyOptions witnessOptions;
    private final ColumnFamilyOptions intermediateWitnessOptions;
    private final ColumnFamilyOptions obstructionOptions;
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses;
    private final ColumnFamilyHandle intermediateWitnesses;
    private final ColumnFamilyHandle obstructions;
    private final CertificateManifestStore manifests;
    private final LinkedBlockingQueue<PendingEntry> pending =
            new LinkedBlockingQueue<>();
    private final ScheduledExecutorService writer;
    private final AtomicReference<Throwable> backgroundFailure =
            new AtomicReference<>();
    private final Object drainLock = new Object();
    private volatile boolean closed;

    public AsyncCertificateWriter(final Path directory,
                                  final long flushPeriodSeconds) {
        if (flushPeriodSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Certificate flush period must be positive");
        }
        final var absolute = directory.toAbsolutePath();
        requireCompatibleColumnFamilies(absolute);
        options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxBackgroundJobs(4)
                .setMaxSubcompactions(2);
        witnessOptions = columnFamilyOptions();
        intermediateWitnessOptions = columnFamilyOptions();
        obstructionOptions = columnFamilyOptions();
        final var descriptors = List.of(
                new ColumnFamilyDescriptor(
                        RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                new ColumnFamilyDescriptor(
                        OBSTRUCTIONS_COLUMN_FAMILY, obstructionOptions),
                new ColumnFamilyDescriptor(
                        INTERMEDIATE_WITNESSES_COLUMN_FAMILY,
                        intermediateWitnessOptions));
        final var handles = new ArrayList<ColumnFamilyHandle>(3);
        RocksDB openedDatabase = null;
        try {
            openedDatabase = RocksDB.open(
                    options, absolute.toString(), descriptors, handles);
            if (handles.size() != 3) {
                throw new IllegalStateException(
                        "TD(18) database did not open all column families");
            }
        } catch (final RocksDBException failure) {
            closeHandles(handles);
            if (openedDatabase != null) {
                openedDatabase.close();
            }
            closeOptions();
            throw new IllegalStateException(
                    "Cannot open certificate database " + directory, failure);
        } catch (final RuntimeException failure) {
            closeHandles(handles);
            if (openedDatabase != null) {
                openedDatabase.close();
            }
            closeOptions();
            throw failure;
        }
        database = openedDatabase;
        witnesses = handles.get(0);
        obstructions = handles.get(1);
        intermediateWitnesses = handles.get(2);
        manifests = new CertificateManifestStore(absolute);
        writer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("certificate-rocksdb-writer-", 0)
                        .factory());
        writer.scheduleWithFixedDelay(this::drainFromBackground,
                flushPeriodSeconds, flushPeriodSeconds, TimeUnit.SECONDS);
    }

    private static ColumnFamilyOptions columnFamilyOptions() {
        return new ColumnFamilyOptions()
                .setCompactionStyle(CompactionStyle.LEVEL)
                .setDisableAutoCompactions(false)
                .setTargetFileSizeBase(CERTIFICATE_SST_TARGET_SIZE)
                .setMaxBytesForLevelBase(CERTIFICATE_BASE_LEVEL_SIZE)
                .setLevel0FileNumCompactionTrigger(4);
    }

    private static void requireCompatibleColumnFamilies(final Path directory) {
        if (!Files.isRegularFile(directory.resolve("CURRENT"))) {
            if (Files.exists(directory)) {
                try (final var contents = Files.list(directory)) {
                    if (contents.findAny().isPresent()) {
                        throw new IllegalStateException(
                                "Refusing to create a TD(18) database in a nonempty directory");
                    }
                } catch (final IOException failure) {
                    throw new IllegalStateException(
                            "Cannot inspect certificate directory " + directory, failure);
                }
            }
            return;
        }
        try (final var listOptions = new Options()) {
            final var families = RocksDB.listColumnFamilies(
                    listOptions, directory.toString());
            if ((families.size() != 2 && families.size() != 3)
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, OBSTRUCTIONS_COLUMN_FAMILY))
                    || (families.size() == 3
                    && families.stream().noneMatch(name -> Arrays.equals(
                    name, INTERMEDIATE_WITNESSES_COLUMN_FAMILY)))) {
                throw new IllegalStateException(
                        "Unsupported TD(18) column-family schema");
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot inspect certificate database " + directory, failure);
        }
    }

    /** Nonblocking with respect to the RocksDB writer and GPU threads. */
    public void enqueue(final CyclicTargetPair canonicalPair,
                        final List<Cycle> moves) {
        Objects.requireNonNull(canonicalPair);
        Objects.requireNonNull(moves);
        enqueue(new DatabaseEntry(Target.WITNESS,
                CertificateCodec.keyFor(canonicalPair),
                CertificateCodec.valueFor(moves)));
    }

    /** Legacy writer retained only for compatibility tests and old artifacts. */
    public void enqueueIntermediate(
            final CyclicTargetPair canonicalPair,
            final int maximumMoves,
            final List<Cycle> moves
    ) {
        Objects.requireNonNull(canonicalPair);
        Objects.requireNonNull(moves);
        enqueue(new DatabaseEntry(Target.INTERMEDIATE_WITNESS,
                CertificateCodec.intermediateKeyFor(
                        canonicalPair, maximumMoves),
                CertificateCodec.valueFor(moves)));
    }

    /** Queues a row sidecar after every outcome from that row. */
    public void enqueueManifest(final CertificateRowManifest manifest) {
        enqueue(new RowManifestEntry(Objects.requireNonNull(manifest)));
    }

    /** Queues a bounded-search obstruction as a key-only entry. */
    public void enqueueResidual(final ResidualObligation obligation) {
        Objects.requireNonNull(obligation);
        enqueue(new DatabaseEntry(
                Target.OBSTRUCTION, obligation.key(), EMPTY_VALUE));
    }

    /** Queues an avoidance-root obstruction as a key-only entry. */
    public void enqueueAvoidanceMiss(final AvoidanceSearchMiss miss) {
        Objects.requireNonNull(miss);
        enqueue(new DatabaseEntry(Target.OBSTRUCTION, miss.key(), EMPTY_VALUE));
    }

    /** Queues the final range sidecar after every avoidance outcome. */
    public void enqueueAvoidanceManifest(
            final AvoidancePhaseManifest manifest
    ) {
        enqueue(new AvoidanceManifestEntry(Objects.requireNonNull(manifest)));
    }

    private void enqueue(final PendingEntry entry) {
        throwIfFailed();
        if (closed) {
            throw new IllegalStateException("Certificate writer is closed");
        }
        pending.add(entry);
    }

    public void throwIfFailed() {
        final var failure = backgroundFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Certificate writer failed", failure);
        }
    }

    /** Flushes every entry queued before this call to RocksDB. */
    public void flush() {
        throwIfFailed();
        try {
            drain(true);
            throwIfFailed();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot flush certificate database", failure);
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
            final var drained = new ArrayList<PendingEntry>();
            pending.drainTo(drained);
            final var batchWitnesses = new HashSet<ByteBuffer>();
            for (final var entry : drained) {
                if (entry instanceof DatabaseEntry databaseEntry
                        && databaseEntry.target() == Target.WITNESS) {
                    batchWitnesses.add(ByteBuffer.wrap(databaseEntry.key()));
                }
            }

            if (!drained.isEmpty()) {
                try (final var batch = new WriteBatch();
                     final var writeOptions = new WriteOptions()) {
                    for (final var entry : drained) {
                        if (!(entry instanceof DatabaseEntry databaseEntry)) {
                            continue;
                        }
                        switch (databaseEntry.target()) {
                            case WITNESS -> {
                                batch.put(witnesses, databaseEntry.key(),
                                        databaseEntry.value());
                                batch.delete(obstructions, databaseEntry.key());
                            }
                            case INTERMEDIATE_WITNESS ->
                                    batch.put(intermediateWitnesses,
                                            databaseEntry.key(),
                                            databaseEntry.value());
                            case OBSTRUCTION -> {
                                if (!batchWitnesses.contains(
                                        ByteBuffer.wrap(databaseEntry.key()))
                                        && !database.keyExists(
                                        witnesses, databaseEntry.key())) {
                                    batch.put(obstructions, databaseEntry.key(),
                                            EMPTY_VALUE);
                                }
                            }
                        }
                    }
                    if (batch.count() > 0) {
                        database.write(writeOptions, batch);
                    }
                }
            }
            if (flush) {
                try (final var flushOptions =
                             new FlushOptions().setWaitForFlush(true)) {
                    database.flush(flushOptions,
                            List.of(witnesses, obstructions,
                                    intermediateWitnesses));
                }
            }
            for (final var entry : drained) {
                if (entry instanceof RowManifestEntry row) {
                    manifests.write(row.manifest());
                } else if (entry instanceof AvoidanceManifestEntry avoidance) {
                    manifests.write(avoidance.manifest());
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
            throw new IllegalStateException(
                    "Interrupted while closing certificate writer", exception);
        } catch (final RocksDBException exception) {
            throw new IllegalStateException(
                    "Cannot finish certificate database", exception);
        } finally {
            intermediateWitnesses.close();
            obstructions.close();
            witnesses.close();
            database.close();
            closeOptions();
        }
    }

    private void closeOptions() {
        obstructionOptions.close();
        intermediateWitnessOptions.close();
        witnessOptions.close();
        options.close();
    }

    private static void closeHandles(
            final List<ColumnFamilyHandle> handles
    ) {
        handles.forEach(ColumnFamilyHandle::close);
    }

    private enum Target {
        WITNESS,
        INTERMEDIATE_WITNESS,
        OBSTRUCTION
    }

    private sealed interface PendingEntry permits DatabaseEntry,
            RowManifestEntry, AvoidanceManifestEntry {
    }

    private record DatabaseEntry(Target target, byte[] key, byte[] value)
            implements PendingEntry {
    }

    private record RowManifestEntry(CertificateRowManifest manifest)
            implements PendingEntry {
    }

    private record AvoidanceManifestEntry(AvoidancePhaseManifest manifest)
            implements PendingEntry {
    }
}

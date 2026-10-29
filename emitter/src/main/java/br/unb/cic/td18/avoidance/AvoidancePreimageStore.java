package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Versioned RocksDB work store for unique avoidance-preimage classes. */
final class AvoidancePreimageStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AvoidancePreimageStore.class);
    // Version 2 uses canonical restorations and early diameter filtering.
    // Version 5 preserves mixed-budget terminal sources and restricted root-lifting seeds.
    private static final int FORMAT_VERSION = 5;
    private static final int SCREENING_POLICY_VERSION = 3;
    private static final byte[] PREIMAGES_COLUMN_FAMILY =
            "avoidance-preimages".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HARD_COLUMN_FAMILY =
            "avoidance-hard".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FORMAT_KEY =
            "format-version".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOURCE_KEY =
            "terminal-source".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOURCE_STATE_PREFIX =
            "terminal-source-state/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOURCE_STATE_END =
            "terminal-source-state0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];
    private static final int STATE_HEADER_BYTES = 3;
    private static final int FLUSH_SECONDS = 60;
    private static final int MAX_BUFFERED_KEYS = Integer.getInteger(
            "td18.avoidanceBufferKeys", 5_000_000);
    private static final int ROCKS_WRITE_BATCH_SIZE = Integer.getInteger(
            "td18.avoidanceRocksWriteBatchKeys", 100_000);
    private static final long ROCKS_WRITE_BUFFER_MIB = Long.getLong(
            "td18.avoidanceRocksWriteBufferMB", 64L);
    private static final int ROCKS_MAX_WRITE_BUFFERS = Integer.getInteger(
            "td18.avoidanceRocksMaxWriteBuffers", 4);
    private static final int ROCKS_BACKGROUND_JOBS = Integer.getInteger(
            "td18.avoidanceRocksBackgroundJobs", 4);
    private static final int ROCKS_SUBCOMPACTIONS = Integer.getInteger(
            "td18.avoidanceRocksSubcompactions", 2);

    static {
        RocksDB.loadLibrary();
    }

    private final Path directory;
    private final DBOptions options;
    private final ColumnFamilyOptions metadataOptions;
    private final ColumnFamilyOptions preimageOptions;
    private final ColumnFamilyOptions hardOptions;
    private final WriteOptions writeOptions;
    private final RocksDB database;
    private final ColumnFamilyHandle metadata;
    private final ColumnFamilyHandle preimages;
    private final ColumnFamilyHandle hard;
    private final Object bufferLock = new Object();
    private final Object drainLock = new Object();
    private final int maximumBufferedKeys;
    private final AtomicReference<Throwable> backgroundFailure =
            new AtomicReference<>();
    private Map<ByteBuffer, byte[]> pendingPreimages = new HashMap<>();
    private Map<ByteBuffer, byte[]> pendingHard = new HashMap<>();
    // Includes detached maps and the batch currently being written.
    private long bufferedKeys;
    private long peakBufferedKeys;
    private long writtenKeys;
    private long completedDrains;
    private boolean sizeDrainQueued;
    private ScheduledExecutorService flusher;
    private volatile boolean closed;

    AvoidancePreimageStore(final Path directory) {
        this(directory, MAX_BUFFERED_KEYS, Duration.ofSeconds(FLUSH_SECONDS));
    }

    AvoidancePreimageStore(final Path directory, final int maximumBufferedKeys,
                           final Duration flushInterval) {
        if (maximumBufferedKeys <= 0 || flushInterval.toMillis() <= 0) {
            throw new IllegalArgumentException("Avoidance buffer limits must be positive");
        }
        this.maximumBufferedKeys = maximumBufferedKeys;
        this.directory = directory.toAbsolutePath();
        final boolean existing = Files.isRegularFile(
                this.directory.resolve("CURRENT"));
        requireUsableDirectory(existing);

        options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxBackgroundJobs(ROCKS_BACKGROUND_JOBS)
                .setMaxSubcompactions(ROCKS_SUBCOMPACTIONS);
        metadataOptions = new ColumnFamilyOptions();
        preimageOptions = bulkColumnFamilyOptions();
        hardOptions = bulkColumnFamilyOptions();
        // Incomplete waves are regenerated, and manifests are forced to disk.
        // Avoid the WAL's duplicate write path for this bulk work database.
        writeOptions = new WriteOptions().setDisableWAL(true);
        final var descriptors = List.of(
                new ColumnFamilyDescriptor(
                        RocksDB.DEFAULT_COLUMN_FAMILY, metadataOptions),
                new ColumnFamilyDescriptor(
                        PREIMAGES_COLUMN_FAMILY, preimageOptions),
                new ColumnFamilyDescriptor(
                        HARD_COLUMN_FAMILY, hardOptions));
        final var handles = new ArrayList<ColumnFamilyHandle>(3);
        RocksDB opened = null;
        try {
            opened = RocksDB.open(options, this.directory.toString(),
                    descriptors, handles);
            if (handles.size() != 3) {
                throw new IllegalStateException(
                        "Avoidance work database did not open all column families");
            }
            database = opened;
            metadata = handles.get(0);
            preimages = handles.get(1);
            hard = handles.get(2);
            requireFormat(existing);
            flusher = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true)
                            .name("avoidance-preimage-writer").factory());
            flusher.scheduleAtFixedRate(() -> flushFromBackground("timer"),
                    flushInterval.toMillis(), flushInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (final RocksDBException | RuntimeException failure) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) {
                opened.close();
            }
            closeOptions();
            throw new IllegalStateException(
                    "Cannot open avoidance work database " + directory, failure);
        }
    }

    private static ColumnFamilyOptions bulkColumnFamilyOptions() {
        if (ROCKS_WRITE_BATCH_SIZE <= 0 || ROCKS_WRITE_BUFFER_MIB <= 0
                || ROCKS_MAX_WRITE_BUFFERS < 2
                || ROCKS_BACKGROUND_JOBS <= 0 || ROCKS_SUBCOMPACTIONS <= 0) {
            throw new IllegalArgumentException(
                    "Avoidance RocksDB tuning properties must be positive");
        }
        return new ColumnFamilyOptions()
                .setWriteBufferSize(Math.multiplyExact(
                        ROCKS_WRITE_BUFFER_MIB, 1024L * 1024L))
                .setMaxWriteBufferNumber(ROCKS_MAX_WRITE_BUFFERS)
                .setMinWriteBufferNumberToMerge(2);
    }

    void putStates(final List<? extends State> states) {
        throwIfFailed();
        final var keys = new ArrayList<byte[]>(states.size());
        for (final var state : states) {
            keys.add(stateKey(state.maximumMoves(), state.pair()));
        }
        putKeys(keys, preimages);
    }

    void putPairs(final int maximumMoves,
                  final List<CyclicTargetPair> pairs) {
        throwIfFailed();
        final var keys = new ArrayList<byte[]>(pairs.size());
        for (final var pair : pairs) {
            keys.add(stateKey(maximumMoves, pair));
        }
        putKeys(keys, preimages);
    }

    void putHardStates(final List<? extends State> states) {
        throwIfFailed();
        final var keys = new ArrayList<byte[]>(states.size());
        for (final var state : states) {
            keys.add(stateKey(state.maximumMoves(), state.pair()));
        }
        putKeys(keys, hard);
    }

    void putHardPairs(final int maximumMoves,
                      final List<CyclicTargetPair> pairs) {
        throwIfFailed();
        final var keys = new ArrayList<byte[]>(pairs.size());
        for (final var pair : pairs) {
            keys.add(stateKey(maximumMoves, pair));
        }
        putKeys(keys, hard);
    }

    private void putKeys(final List<byte[]> keys,
                         final ColumnFamilyHandle destination) {
        synchronized (bufferLock) {
            for (final var key : keys) {
                bufferKey(key, destination);
            }
        }
    }

    /** Called with {@link #bufferLock} held. */
    private void bufferKey(final byte[] key,
                           final ColumnFamilyHandle destination) {
        final var wrapped = ByteBuffer.wrap(key);
        while (true) {
            throwIfFailed();
            if (closed) {
                throw new IllegalStateException(
                        "Avoidance-preimage store is closed");
            }
            // Re-read the map after waiting: a drain may have detached it.
            final Map<ByteBuffer, byte[]> pending = destination == hard
                    ? pendingHard : pendingPreimages;
            if (pending.containsKey(wrapped)) {
                return;
            }
            if (bufferedKeys < maximumBufferedKeys) {
                pending.put(wrapped, key);
                bufferedKeys++;
                peakBufferedKeys = Math.max(peakBufferedKeys, bufferedKeys);
                if (bufferedKeys == maximumBufferedKeys) {
                    requestSizeDrain();
                }
                return;
            }
            requestSizeDrain();
            try {
                bufferLock.wait();
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for avoidance buffer space",
                        interruption);
            }
        }
    }

    /** Called with bufferLock held; at most one size-triggered task is queued. */
    private void requestSizeDrain() {
        if (sizeDrainQueued || closed || backgroundFailure.get() != null) {
            return;
        }
        sizeDrainQueued = true;
        flusher.execute(() -> {
            try {
                flushFromBackground("size");
            } finally {
                synchronized (bufferLock) {
                    sizeDrainQueued = false;
                    bufferLock.notifyAll();
                    if (bufferedKeys == maximumBufferedKeys) {
                        requestSizeDrain();
                    }
                }
            }
        });
    }

    record BufferStatistics(long bufferedKeys, long peakBufferedKeys,
                            long writtenKeys, long completedDrains) {
    }

    BufferStatistics bufferStatistics() {
        synchronized (bufferLock) {
            return new BufferStatistics(bufferedKeys, peakBufferedKeys,
                    writtenKeys, completedDrains);
        }
    }

    long scanBatches(final int maximumMoves, final int batchSize,
                     final Consumer<List<CyclicTargetPair>> consumer) {
        return scanBatches(preimages, maximumMoves, batchSize, consumer);
    }

    long scanHardBatches(final int maximumMoves, final int batchSize,
                         final Consumer<List<CyclicTargetPair>> consumer) {
        return scanBatches(hard, maximumMoves, batchSize, consumer);
    }

    private long scanBatches(final ColumnFamilyHandle source,
                             final int maximumMoves, final int batchSize,
                             final Consumer<List<CyclicTargetPair>> consumer) {
        drain(false);
        throwIfFailed();
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Avoidance scan batch size must be positive");
        }
        final byte[] prefix = statePrefix(maximumMoves);
        long count = 0;
        final Snapshot snapshot = database.getSnapshot();
        try (final var readOptions = new ReadOptions()
                .setSnapshot(snapshot).setVerifyChecksums(true)
                .setFillCache(false);
             final var iterator = database.newIterator(source, readOptions)) {
            final var batch = new ArrayList<CyclicTargetPair>(batchSize);
            iterator.seek(prefix);
            while (iterator.isValid() && hasPrefix(iterator.key(), prefix)) {
                if (iterator.value().length != 0) {
                    throw new IllegalStateException(
                            "Avoidance-preimage state must have an empty value");
                }
                batch.add(decodeState(iterator.key(), maximumMoves));
                count++;
                if (batch.size() == batchSize) {
                    consumer.accept(List.copyOf(batch));
                    batch.clear();
                }
                iterator.next();
            }
            iterator.status();
            if (!batch.isEmpty()) {
                consumer.accept(List.copyOf(batch));
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot scan avoidance-preimage states", failure);
        } finally {
            database.releaseSnapshot(snapshot);
        }
        return count;
    }

    StateInventory inventory(final int maximumMoves) {
        return inventory(preimages, maximumMoves);
    }

    StateInventory hardInventory(final int maximumMoves) {
        return inventory(hard, maximumMoves);
    }

    private StateInventory inventory(final ColumnFamilyHandle source,
                                     final int maximumMoves) {
        drain(false);
        throwIfFailed();
        final byte[] prefix = statePrefix(maximumMoves);
        long count = 0;
        long checksum = 0;
        try (final var readOptions = new ReadOptions()
                .setVerifyChecksums(true).setFillCache(false);
             final var iterator = database.newIterator(source, readOptions)) {
            iterator.seek(prefix);
            while (iterator.isValid() && hasPrefix(iterator.key(), prefix)) {
                if (iterator.value().length != 0) {
                    throw new IllegalStateException(
                            "Avoidance-preimage state must have an empty value");
                }
                count = Math.addExact(count, 1);
                checksum = Math.addExact(checksum,
                        checksumContribution(iterator.key()));
                iterator.next();
            }
            iterator.status();
            return new StateInventory(count, checksum);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot inventory avoidance-preimage states", failure);
        }
    }

    /** Inventory of a deduplicated terminal frontier, including its move budgets. */
    static StateInventory inventoryOf(final List<State> states) {
        final var unique = new java.util.HashSet<ByteBuffer>();
        long checksum = 0;
        for (final var state : states) {
            final byte[] key = stateKey(state.maximumMoves(), state.pair());
            if (unique.add(ByteBuffer.wrap(key))) {
                checksum = Math.addExact(checksum, checksumContribution(key));
            }
        }
        return new StateInventory(unique.size(), checksum);
    }

    /**
     * Saves the exact terminal frontier before any search can delete its
     * obstruction markers. On resume, the immutable frontier remains the source
     * of work even when a new witness has removed its original marker.
     * Source records are flushed before their completion inventory; an interrupted
     * initial write has no completion inventory and is replaced in full.
     */
    List<ResidualObligation> requireOrRecordTerminalSources(
            final List<ResidualObligation> currentMarkers) {
        final var current = indexedSources(currentMarkers);
        try {
            drain(true);
            final byte[] committed = database.get(metadata, SOURCE_KEY);
            if (committed != null) {
                final var saved = readTerminalSources();
                final var inventory = inventoryOf(saved.values().stream()
                        .map(source -> new State(source.maximumMoves(), source.pair())).toList());
                if (!Arrays.equals(committed, inventory.encoded())) {
                    throw new IllegalStateException(
                            "Missing or changed immutable avoidance source records; use a new work directory");
                }
                for (final var entry : current.entrySet()) {
                    final var recorded = saved.get(entry.getKey());
                    if (recorded != null) {
                        if (recorded.row() != entry.getValue().row()) {
                            throw new IllegalStateException("Changed immutable avoidance source row");
                        }
                        continue;
                    }
                    final var source = entry.getValue();
                    // An earlier root search can add a marker whose type also
                    // occurs in Table 2. It is output of this work, not a new source.
                    final byte[] root = source.maximumMoves() == 10
                            ? database.get(preimages, stateKey(10, source.pair())) : null;
                    if (root == null || root.length != 0) {
                        throw new IllegalStateException(
                                "New terminal avoidance sources require a new work directory");
                    }
                }
                return List.copyOf(saved.values());
            }

            if (waveManifest(8).isPresent() || waveManifest(9).isPresent()
                    || searchManifest(9).isPresent() || searchManifest(10).isPresent()) {
                throw new IllegalStateException(
                        "Missing immutable source completion for an existing avoidance run");
            }
            // This also removes records from a crash before source completion.
            database.deleteRange(metadata, writeOptions, SOURCE_STATE_PREFIX, SOURCE_STATE_END);
            try (final var batch = new WriteBatch()) {
                var pending = 0;
                for (final var source : current.values()) {
                    batch.put(metadata, sourceRecordKey(source),
                            ByteBuffer.allocate(Short.BYTES).putShort((short) source.row()).array());
                    if (++pending == ROCKS_WRITE_BATCH_SIZE) {
                        database.write(writeOptions, batch);
                        batch.clear();
                        pending = 0;
                    }
                }
                if (pending != 0) database.write(writeOptions, batch);
            }
            flushRocks();
            final var inventory = inventoryOf(current.values().stream()
                    .map(source -> new State(source.maximumMoves(), source.pair())).toList());
            database.put(metadata, writeOptions, SOURCE_KEY, inventory.encoded());
            flushRocks();
            return List.copyOf(current.values());
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot persist the immutable terminal avoidance source", failure);
        }
    }

    private TreeMap<ByteBuffer, ResidualObligation> readTerminalSources() throws RocksDBException {
        final var sources = new TreeMap<ByteBuffer, ResidualObligation>();
        try (final var readOptions = new ReadOptions().setVerifyChecksums(true).setFillCache(false);
             final var iterator = database.newIterator(metadata, readOptions)) {
            iterator.seek(SOURCE_STATE_PREFIX);
            while (iterator.isValid() && Arrays.compareUnsigned(iterator.key(), SOURCE_STATE_END) < 0) {
                final byte[] key = iterator.key();
                final byte[] value = iterator.value();
                if (key.length <= SOURCE_STATE_PREFIX.length + STATE_HEADER_BYTES
                        || !Arrays.equals(Arrays.copyOf(key, SOURCE_STATE_PREFIX.length), SOURCE_STATE_PREFIX)
                        || value.length != Short.BYTES) {
                    throw new IllegalStateException("Malformed immutable terminal avoidance source record");
                }
                final byte[] state = Arrays.copyOfRange(key, SOURCE_STATE_PREFIX.length, key.length);
                final int budget = Byte.toUnsignedInt(state[1]);
                final var pair = decodeState(state, budget);
                final int row = Short.toUnsignedInt(ByteBuffer.wrap(value).getShort());
                final var source = new ResidualObligation(row, budget, pair);
                ObstructionAvoidance.validateTerminalResidual(source);
                if (!Arrays.equals(key, sourceRecordKey(source))) {
                    throw new IllegalStateException("Noncanonical immutable terminal avoidance source key");
                }
                if (sources.put(ByteBuffer.wrap(state), source) != null) {
                    throw new IllegalStateException("Duplicate immutable terminal avoidance source");
                }
                iterator.next();
            }
            iterator.status();
        }
        return sources;
    }

    private static TreeMap<ByteBuffer, ResidualObligation> indexedSources(
            final List<ResidualObligation> sources) {
        final var result = new TreeMap<ByteBuffer, ResidualObligation>();
        for (final var source : sources) {
            ObstructionAvoidance.validateTerminalResidual(source);
            if (result.put(ByteBuffer.wrap(stateKey(source.maximumMoves(), source.pair())), source) != null) {
                throw new IllegalStateException("Terminal avoidance sources are not unique");
            }
        }
        return result;
    }

    private static byte[] sourceRecordKey(final ResidualObligation source) {
        final byte[] key = stateKey(source.maximumMoves(), source.pair());
        return ByteBuffer.allocate(SOURCE_STATE_PREFIX.length + key.length)
                .put(SOURCE_STATE_PREFIX).put(key).array();
    }

    /** Direct intermediate seeds bypass screening, including overlapping preimages. */
    void removeCandidates(final int maximumMoves, final List<CyclicTargetPair> seeds) {
        try {
            drain(true);
            try (final var batch = new WriteBatch()) {
                for (final var seed : seeds) {
                    batch.delete(preimages, stateKey(maximumMoves, seed));
                }
                database.write(writeOptions, batch);
            }
            flushRocks();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot exclude direct avoidance seeds", failure);
        }
    }

    /** Discard partial search results before replay, restoring immutable direct seeds. */
    void resetHard(final int maximumMoves, final List<CyclicTargetPair> seeds) {
        try {
            drain(true);
            database.deleteRange(hard, writeOptions, statePrefix(maximumMoves),
                    statePrefix(maximumMoves + 1));
            putHardPairs(maximumMoves, seeds);
            drain(true);
            flushRocks();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot reset an incomplete avoidance search", failure);
        }
    }

    /** Deletes resolved retry inputs; the completion manifest makes the deletion durable. */
    void removeHardPairs(final int maximumMoves, final List<CyclicTargetPair> resolved) {
        throwIfFailed();
        // Retry scans use a snapshot and each input has one outcome. There are
        // no pending hard writes for keys resolved by this retry, so unrelated
        // producer buffers need not be drained for every successful root.
        try (final var batch = new WriteBatch()) {
            for (final var pair : resolved) {
                batch.delete(hard, stateKey(maximumMoves, pair));
            }
            database.write(writeOptions, batch);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot remove resolved avoidance retry inputs", failure);
        }
    }

    /** Search residuals exclude direct seeds that were deliberately not searched. */
    StateInventory hardInventoryExcluding(final int maximumMoves,
                                           final List<CyclicTargetPair> seeds) {
        final var all = hardInventory(maximumMoves);
        final var seen = new java.util.HashSet<ByteBuffer>();
        long checksum = 0;
        try {
            for (final var seed : seeds) {
                final byte[] key = stateKey(maximumMoves, seed);
                if (!seen.add(ByteBuffer.wrap(key))) continue;
                final byte[] value = database.get(hard, key);
                if (value == null || value.length != 0) {
                    throw new IllegalStateException("Missing direct avoidance seed");
                }
                checksum = Math.addExact(checksum, checksumContribution(key));
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot verify direct avoidance seeds", failure);
        }
        return new StateInventory(Math.subtractExact(all.count(), seen.size()),
                Math.subtractExact(all.checksum(), checksum));
    }

    Optional<WaveManifest> waveManifest(final int inputMaximumMoves) {
        throwIfFailed();
        try {
            final byte[] encoded = database.get(
                    metadata, waveKey(inputMaximumMoves));
            return encoded == null
                    ? Optional.empty()
                    : Optional.of(WaveManifest.decode(encoded));
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot read avoidance wave manifest", failure);
        }
    }

    void clearUncommittedWaveOutput(final int inputMaximumMoves) {
        if (inputMaximumMoves <= 0 || inputMaximumMoves >= 254) {
            throw new IllegalArgumentException(
                    "Avoidance wave budget cannot be cleared safely");
        }
        final int outputMaximumMoves = inputMaximumMoves + 1;
        final byte[] begin = statePrefix(outputMaximumMoves);
        final byte[] end = statePrefix(outputMaximumMoves + 1);
        try {
            drain(true);
            database.deleteRange(preimages, writeOptions, begin, end);
            flushRocks();
            log.info("Cleared uncommitted avoidance reconstruction wave {} output",
                    inputMaximumMoves);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot clear uncommitted avoidance reconstruction wave "
                            + inputMaximumMoves, failure);
        }
    }

    Optional<SearchManifest> searchManifest(final int maximumMoves) {
        throwIfFailed();
        try {
            final byte[] encoded = database.get(
                    metadata, searchKey(maximumMoves));
            return encoded == null
                    ? Optional.empty()
                    : Optional.of(SearchManifest.decode(encoded));
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot read avoidance search manifest", failure);
        }
    }

    /** Invalidate completion durably before a retry changes its residual set. */
    void clearSearchManifest(final int maximumMoves) {
        try {
            drain(true);
            database.delete(metadata, writeOptions, searchKey(maximumMoves));
            flushRocks();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot invalidate avoidance search completion", failure);
        }
    }

    void recordWave(final WaveManifest manifest) {
        try {
            drain(true);
            database.put(metadata, writeOptions,
                    waveKey(manifest.inputMaximumMoves()), manifest.encoded());
            flushRocks();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot persist avoidance wave manifest", failure);
        }
    }

    void recordSearch(final SearchManifest manifest) {
        try {
            drain(true);
            database.put(metadata, writeOptions,
                    searchKey(manifest.maximumMoves()), manifest.encoded());
            flushRocks();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot persist avoidance search manifest", failure);
        }
    }

    private void requireUsableDirectory(final boolean existing) {
        if (existing) {
            try (final var listOptions = new Options()) {
                final var families = RocksDB.listColumnFamilies(
                        listOptions, directory.toString());
                if ((families.size() != 2 && families.size() != 3)
                        || families.stream().noneMatch(name -> Arrays.equals(
                        name, RocksDB.DEFAULT_COLUMN_FAMILY))
                        || families.stream().noneMatch(name -> Arrays.equals(
                        name, PREIMAGES_COLUMN_FAMILY))
                        || (families.size() == 3
                        && families.stream().noneMatch(name -> Arrays.equals(
                        name, HARD_COLUMN_FAMILY)))) {
                    throw new IllegalStateException(
                            "Unsupported avoidance work database schema");
                }
            } catch (final RocksDBException failure) {
                throw new IllegalStateException(
                        "Cannot inspect avoidance work database " + directory,
                        failure);
            }
            return;
        }
        if (!Files.exists(directory)) {
            return;
        }
        try (final var contents = Files.list(directory)) {
            if (contents.findAny().isPresent()) {
                throw new IllegalStateException(
                        "Refusing to create an avoidance work database in a nonempty directory");
            }
        } catch (final IOException failure) {
            throw new IllegalStateException(
                    "Cannot inspect avoidance work directory " + directory,
                    failure);
        }
    }

    private void requireFormat(final boolean existing) throws RocksDBException {
        final byte[] expected = ByteBuffer.allocate(Integer.BYTES)
                .putInt(FORMAT_VERSION).array();
        final byte[] actual = database.get(metadata, FORMAT_KEY);
        if (!existing && actual == null) {
            database.put(metadata, writeOptions, FORMAT_KEY, expected);
            flushRocks();
        } else if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException(
                    "Unsupported avoidance work format version; use a new "
                            + "work directory for canonical restoration counts");
        }
    }

    private static byte[] stateKey(final int maximumMoves,
                                   final CyclicTargetPair pair) {
        if (maximumMoves <= 0 || maximumMoves > 255) {
            throw new IllegalArgumentException(
                    "Avoidance state budget must fit one byte");
        }
        final var canonical = pair.canonicalRepresentative();
        final byte[] pairKey = CertificateCodec.keyFor(canonical);
        return ByteBuffer.allocate(STATE_HEADER_BYTES + pairKey.length)
                .put((byte) FORMAT_VERSION)
                .put((byte) maximumMoves)
                .put((byte) canonical.getBeta().size())
                .put(pairKey).array();
    }

    private static CyclicTargetPair decodeState(
            final byte[] key, final int expectedMaximumMoves
    ) {
        if (key.length <= STATE_HEADER_BYTES
                || Byte.toUnsignedInt(key[0]) != FORMAT_VERSION
                || Byte.toUnsignedInt(key[1]) != expectedMaximumMoves) {
            throw new IllegalStateException(
                    "Malformed avoidance-preimage state key");
        }
        final int domainSize = Byte.toUnsignedInt(key[2]);
        return CertificateCodec.pairForKey(
                Arrays.copyOfRange(key, STATE_HEADER_BYTES, key.length),
                domainSize);
    }

    private static byte[] statePrefix(final int maximumMoves) {
        if (maximumMoves <= 0 || maximumMoves > 255) {
            throw new IllegalArgumentException(
                    "Avoidance state budget must fit one byte");
        }
        return new byte[]{(byte) FORMAT_VERSION, (byte) maximumMoves};
    }

    private static boolean hasPrefix(final byte[] key, final byte[] prefix) {
        return key.length >= prefix.length
                && key[0] == prefix[0] && key[1] == prefix[1];
    }

    private static long checksumContribution(final byte[] key) {
        final var checksum = new CRC32C();
        checksum.update(key);
        return checksum.getValue();
    }

    private static byte[] waveKey(final int inputMaximumMoves) {
        return ("wave-" + inputMaximumMoves)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] searchKey(final int maximumMoves) {
        return ("screen-" + SCREENING_POLICY_VERSION + '-' + maximumMoves)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void flushFromBackground(final String trigger) {
        try {
            // Full buffers already flow through RocksDB's memtables. Avoid
            // small L0 files from a manual flush on every size drain.
            // Timer and checkpoint drains remain durable.
            drain(!"size".equals(trigger), trigger);
        } catch (final Throwable failure) {
            backgroundFailure.compareAndSet(null, failure);
            synchronized (bufferLock) {
                bufferLock.notifyAll();
            }
        }
    }

    private void drain(final boolean flushRocks) {
        drain(flushRocks, "checkpoint");
    }

    private void drain(final boolean flushRocks, final String trigger) {
        synchronized (drainLock) {
            throwIfFailed();
            final long started = System.nanoTime();
            final Map<ByteBuffer, byte[]> drainedPreimages;
            final Map<ByteBuffer, byte[]> drainedHard;
            synchronized (bufferLock) {
                drainedPreimages = pendingPreimages;
                drainedHard = pendingHard;
                pendingPreimages = new HashMap<>();
                pendingHard = new HashMap<>();
            }
            final long count = (long) drainedPreimages.size() + drainedHard.size();
            try {
                writeKeys(preimages, drainedPreimages);
                writeKeys(hard, drainedHard);
                if (flushRocks) {
                    flushRocks();
                }
                if (count != 0) {
                    final BufferStatistics statistics;
                    synchronized (bufferLock) {
                        completedDrains++;
                        statistics = bufferStatistics();
                    }
                    log.info("Avoidance buffer drained: trigger={}, keys={}, "
                                    + "elapsedMs={}, bufferedKeys={}, peakBufferedKeys={}, "
                                    + "writtenKeys={}, diskFlush={}",
                            trigger, count,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            statistics.bufferedKeys(), statistics.peakBufferedKeys(),
                            statistics.writtenKeys(), flushRocks);
                }
            } catch (final RocksDBException | RuntimeException failure) {
                synchronized (bufferLock) {
                    for (final var key : drainedPreimages.values()) {
                        if (pendingPreimages.putIfAbsent(ByteBuffer.wrap(key), key) != null) {
                            bufferedKeys--;
                        }
                    }
                    for (final var key : drainedHard.values()) {
                        if (pendingHard.putIfAbsent(ByteBuffer.wrap(key), key) != null) {
                            bufferedKeys--;
                        }
                    }
                    bufferLock.notifyAll();
                }
                throw new IllegalStateException(
                        "Cannot drain avoidance-preimage buffer", failure);
            }
        }
    }

    private void writeKeys(final ColumnFamilyHandle destination,
                           final Map<ByteBuffer, byte[]> keys) throws RocksDBException {
        final var iterator = keys.entrySet().iterator();
        final var chunk = new ArrayList<byte[]>(
                Math.min(ROCKS_WRITE_BATCH_SIZE, keys.size()));
        while (iterator.hasNext()) {
            try {
                try (final var batch = new WriteBatch()) {
                    while (iterator.hasNext() && chunk.size() < ROCKS_WRITE_BATCH_SIZE) {
                        final var entry = iterator.next();
                        chunk.add(entry.getValue());
                        iterator.remove();
                        batch.put(destination, chunk.getLast(), EMPTY);
                    }
                    database.write(writeOptions, batch);
                }
            } catch (final RocksDBException | RuntimeException failure) {
                for (final var key : chunk) {
                    keys.put(ByteBuffer.wrap(key), key);
                }
                throw failure;
            }
            final int written = chunk.size();
            // Release successful entries before accepting more buffered keys.
            chunk.clear();
            releaseWritten(written);
        }
    }

    private void releaseWritten(final int count) {
        synchronized (bufferLock) {
            bufferedKeys -= count;
            writtenKeys += count;
            bufferLock.notifyAll();
        }
    }

    private void flushRocks() throws RocksDBException {
        try (final var flushOptions =
                     new FlushOptions().setWaitForFlush(true)) {
            database.flush(flushOptions, List.of(metadata, preimages, hard));
        }
    }

    private void throwIfFailed() {
        final var failure = backgroundFailure.get();
        if (failure != null) {
            throw new IllegalStateException(
                    "Avoidance-preimage background writer failed", failure);
        }
    }

    @Override
    public void close() {
        try {
            synchronized (bufferLock) {
                closed = true;
                bufferLock.notifyAll();
            }
            if (flusher != null) {
                flusher.shutdown();
                if (!flusher.awaitTermination(
                        2L * FLUSH_SECONDS, TimeUnit.SECONDS)) {
                    flusher.shutdownNow();
                    flusher.awaitTermination(
                            2L * FLUSH_SECONDS, TimeUnit.SECONDS);
                }
            }
            drain(true);
            throwIfFailed();
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while closing avoidance work database",
                    failure);
        } finally {
            hard.close();
            preimages.close();
            metadata.close();
            database.close();
            closeOptions();
        }
    }

    private void closeOptions() {
        writeOptions.close();
        hardOptions.close();
        preimageOptions.close();
        metadataOptions.close();
        options.close();
    }

    record State(int maximumMoves, CyclicTargetPair pair) {
        State {
            if (maximumMoves <= 0 || pair == null) {
                throw new IllegalArgumentException(
                        "Inconsistent avoidance-preimage state");
            }
        }
    }

    record StateInventory(long count, long checksum) {
        StateInventory {
            if (count < 0 || checksum < 0) {
                throw new IllegalArgumentException(
                        "Inconsistent avoidance state inventory");
            }
        }

        private byte[] encoded() {
            return ByteBuffer.allocate(2 * Long.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(count).putLong(checksum).array();
        }

        private static StateInventory decode(final ByteBuffer buffer) {
            return new StateInventory(buffer.getLong(), buffer.getLong());
        }
    }

    record WaveManifest(int inputMaximumMoves, StateInventory input,
                        long reconstructionApplications,
                        StateInventory output) {
        private static final int ENCODED_BYTES = Integer.BYTES
                + 5 * Long.BYTES;

        WaveManifest {
            if (inputMaximumMoves <= 0 || input == null || output == null
                    || reconstructionApplications < 0) {
                throw new IllegalArgumentException(
                        "Inconsistent avoidance wave manifest");
            }
        }

        private byte[] encoded() {
            return ByteBuffer.allocate(ENCODED_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(inputMaximumMoves)
                    .putLong(input.count()).putLong(input.checksum())
                    .putLong(reconstructionApplications)
                    .putLong(output.count()).putLong(output.checksum())
                    .array();
        }

        private static WaveManifest decode(final byte[] encoded) {
            if (encoded == null || encoded.length != ENCODED_BYTES) {
                throw new IllegalArgumentException(
                        "Malformed avoidance wave manifest");
            }
            final var buffer = ByteBuffer.wrap(encoded)
                    .order(ByteOrder.BIG_ENDIAN);
            final int maximumMoves = buffer.getInt();
            final var input = StateInventory.decode(buffer);
            final long applications = buffer.getLong();
            final var output = StateInventory.decode(buffer);
            return new WaveManifest(
                    maximumMoves, input, applications, output);
        }
    }


    record SearchManifest(int maximumMoves, StateInventory candidates,
                          long trivialClosures, long certificates,
                          StateInventory residuals, long checksum) {
        private static final int ENCODED_BYTES = Integer.BYTES
                + 7 * Long.BYTES;

        SearchManifest {
            if (maximumMoves <= 0 || candidates == null || residuals == null
                    || trivialClosures < 0 || certificates < 0 || checksum < 0
                    || (maximumMoves < ReductionTypeEnumerator.MAX_BUDGET
                    && certificates != 0)
                    || trivialClosures + certificates + residuals.count()
                    != candidates.count()) {
                throw new IllegalArgumentException(
                        "Inconsistent avoidance search manifest");
            }
        }

        private byte[] encoded() {
            return ByteBuffer.allocate(ENCODED_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(maximumMoves)
                    .putLong(candidates.count()).putLong(candidates.checksum())
                    .putLong(trivialClosures).putLong(certificates)
                    .putLong(residuals.count()).putLong(residuals.checksum())
                    .putLong(checksum).array();
        }

        private static SearchManifest decode(final byte[] encoded) {
            if (encoded == null || encoded.length != ENCODED_BYTES) {
                throw new IllegalArgumentException(
                        "Malformed avoidance search manifest");
            }
            final var buffer = ByteBuffer.wrap(encoded)
                    .order(ByteOrder.BIG_ENDIAN);
            final int maximumMoves = buffer.getInt();
            final var candidates = StateInventory.decode(buffer);
            final long trivialClosures = buffer.getLong();
            final long certificates = buffer.getLong();
            final var residuals = StateInventory.decode(buffer);
            final long checksum = buffer.getLong();
            return new SearchManifest(maximumMoves, candidates,
                    trivialClosures, certificates, residuals, checksum);
        }
    }
}

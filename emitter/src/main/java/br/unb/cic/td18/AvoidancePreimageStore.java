package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CyclicTargetPair;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/** Versioned RocksDB work store for unique avoidance-preimage classes. */
final class AvoidancePreimageStore implements AutoCloseable {
    private static final int FORMAT_VERSION = 1;
    private static final byte[] PREIMAGES_COLUMN_FAMILY =
            "avoidance-preimages".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HARD_COLUMN_FAMILY =
            "avoidance-hard".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FORMAT_KEY =
            "format-version".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOURCE_KEY =
            "terminal-source".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];
    private static final int STATE_HEADER_BYTES = 3;
    private static final int FLUSH_SECONDS = 5;
    private static final int ROCKS_WRITE_BATCH_SIZE = 100_000;

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
    private final AtomicReference<Throwable> backgroundFailure =
            new AtomicReference<>();
    private Map<ByteBuffer, byte[]> pendingPreimages = new HashMap<>();
    private Map<ByteBuffer, byte[]> pendingHard = new HashMap<>();
    private ScheduledExecutorService flusher;
    private volatile boolean closed;

    AvoidancePreimageStore(final Path directory) {
        this.directory = directory.toAbsolutePath();
        final boolean existing = Files.isRegularFile(
                this.directory.resolve("CURRENT"));
        requireUsableDirectory(existing);

        options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxBackgroundJobs(4)
                .setMaxSubcompactions(2);
        metadataOptions = new ColumnFamilyOptions();
        preimageOptions = new ColumnFamilyOptions();
        hardOptions = new ColumnFamilyOptions();
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
            flusher.scheduleAtFixedRate(this::flushFromBackground,
                    FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
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
            throwIfFailed();
            if (closed) {
                throw new IllegalStateException(
                        "Avoidance-preimage store is closed");
            }
            final Map<ByteBuffer, byte[]> pending = destination == hard
                    ? pendingHard : pendingPreimages;
            for (final var key : keys) {
                pending.put(ByteBuffer.wrap(key), key);
            }
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

    void requireOrRecordSource(final StateInventory source) {
        throwIfFailed();
        final byte[] encoded = source.encoded();
        try {
            final byte[] existing = database.get(metadata, SOURCE_KEY);
            if (existing == null) {
                database.put(metadata, writeOptions, SOURCE_KEY, encoded);
            } else if (!Arrays.equals(existing, encoded)) {
                throw new IllegalStateException(
                        "Avoidance work database belongs to another terminal-obstruction set");
            }
            flushRocks();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot validate avoidance work source", failure);
        }
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
                    "Unsupported avoidance work format version");
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
        return ("search-" + maximumMoves)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void flushFromBackground() {
        try {
            drain(false);
        } catch (final Throwable failure) {
            backgroundFailure.compareAndSet(null, failure);
        }
    }

    private void drain(final boolean flushRocks) {
        synchronized (drainLock) {
            throwIfFailed();
            final Map<ByteBuffer, byte[]> drainedPreimages;
            final Map<ByteBuffer, byte[]> drainedHard;
            synchronized (bufferLock) {
                drainedPreimages = pendingPreimages;
                drainedHard = pendingHard;
                pendingPreimages = new HashMap<>();
                pendingHard = new HashMap<>();
            }
            try {
                if (!drainedPreimages.isEmpty()) {
                    writeKeys(preimages,
                            drainedPreimages.values().stream().toList());
                }
                if (!drainedHard.isEmpty()) {
                    writeKeys(hard, drainedHard.values().stream().toList());
                }
                if (flushRocks) {
                    flushRocks();
                }
            } catch (final RocksDBException | RuntimeException failure) {
                synchronized (bufferLock) {
                    for (final var key : drainedPreimages.values()) {
                        pendingPreimages.putIfAbsent(ByteBuffer.wrap(key), key);
                    }
                    for (final var key : drainedHard.values()) {
                        pendingHard.putIfAbsent(ByteBuffer.wrap(key), key);
                    }
                }
                throw new IllegalStateException(
                        "Cannot drain avoidance-preimage buffer", failure);
            }
        }
    }

    private void writeKeys(final ColumnFamilyHandle destination,
                           final List<byte[]> keys) throws RocksDBException {
        for (var start = 0; start < keys.size();
             start += ROCKS_WRITE_BATCH_SIZE) {
            final int end = Math.min(
                    keys.size(), start + ROCKS_WRITE_BATCH_SIZE);
            try (final var batch = new WriteBatch()) {
                for (var index = start; index < end; index++) {
                    batch.put(destination, keys.get(index), EMPTY);
                }
                database.write(writeOptions, batch);
            }
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

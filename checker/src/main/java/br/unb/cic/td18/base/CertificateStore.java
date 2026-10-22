package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only two-column-family TD(18) certificate database. */
public final class CertificateStore implements AutoCloseable {
    private static final byte[] OBSTRUCTIONS_COLUMN_FAMILY =
            "obstructions".getBytes(StandardCharsets.UTF_8);
    private static final String STATS_PROPERTY =
            "td18.certificateStatsPeriodSeconds";

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions options;
    private final ColumnFamilyOptions witnessOptions;
    private final ColumnFamilyOptions obstructionOptions;
    private final ReadOptions readOptions;
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses;
    private final ColumnFamilyHandle obstructions;
    private final CertificateManifestStore manifests;
    private final AtomicLong replayed = new AtomicLong();
    private final ScheduledExecutorService reporter;
    private volatile long reportStarted = System.nanoTime();

    public CertificateStore(final Path directory) {
        final var absolute = directory.toAbsolutePath();
        requireCompatibleColumnFamilies(absolute);
        options = new DBOptions().setMaxOpenFiles(-1);
        witnessOptions = columnFamilyOptions();
        obstructionOptions = columnFamilyOptions();
        readOptions = new ReadOptions()
                .setVerifyChecksums(true)
                .setFillCache(false);
        final var descriptors = List.of(
                new ColumnFamilyDescriptor(
                        RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                new ColumnFamilyDescriptor(
                        OBSTRUCTIONS_COLUMN_FAMILY, obstructionOptions));
        final var handles = new ArrayList<ColumnFamilyHandle>(2);
        RocksDB opened = null;
        try {
            opened = RocksDB.openReadOnly(
                    options, absolute.toString(), descriptors, handles);
            if (handles.size() != 2) {
                throw new IllegalStateException(
                        "TD(18) database did not open both column families");
            }
            database = opened;
            witnesses = handles.get(0);
            obstructions = handles.get(1);
        } catch (final RocksDBException failure) {
            closeHandles(handles);
            if (opened != null) {
                opened.close();
            }
            closeResources();
            throw new IllegalStateException(
                    "Cannot open certificate database " + directory, failure);
        } catch (final RuntimeException failure) {
            closeHandles(handles);
            if (opened != null) {
                opened.close();
            }
            closeResources();
            throw failure;
        }
        manifests = new CertificateManifestStore(absolute);

        final long period = parsePeriod();
        if (period == 0) {
            reporter = null;
        } else {
            reporter = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true)
                            .name("td18-certificate-checker-stats").factory());
            reporter.scheduleAtFixedRate(this::report, period, period,
                    TimeUnit.SECONDS);
        }
    }

    private static ColumnFamilyOptions columnFamilyOptions() {
        return new ColumnFamilyOptions()
                .setTableFormatConfig(new BlockBasedTableConfig()
                        .setCacheIndexAndFilterBlocks(false));
    }

    private static void requireCompatibleColumnFamilies(final Path directory) {
        try (final var listOptions = new Options()) {
            final var families = RocksDB.listColumnFamilies(
                    listOptions, directory.toString());
            if (families.size() != 2
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, OBSTRUCTIONS_COLUMN_FAMILY))) {
                throw new IllegalStateException(
                        "Unsupported TD(18) column-family schema");
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot inspect certificate database " + directory, failure);
        }
    }

    public Optional<List<Cycle>> find(final CyclicTargetPair pair,
                                      final int maximumMoves) {
        final var value = get(witnesses, CertificateCodec.keyFor(pair));
        if (value == null) {
            return Optional.empty();
        }
        final var moves = CertificateCodec.decodeValue(value);
        CertificateValidator.validate(pair, moves, maximumMoves);
        replayed.incrementAndGet();
        return Optional.of(moves);
    }

    /** Looks up and independently replays a position-preserving batch. */
    public List<Optional<List<Cycle>>> find(
            final CyclicTargetPair[] pairs,
            final int maximumMoves
    ) {
        final var keys = new ArrayList<byte[]>(pairs.length);
        final var handles = new ArrayList<ColumnFamilyHandle>(pairs.length);
        for (final var pair : pairs) {
            keys.add(CertificateCodec.keyFor(pair));
            handles.add(witnesses);
        }

        final List<byte[]> values;
        try {
            values = database.multiGetAsList(readOptions, handles, keys);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot read certificate database", failure);
        }

        final var results =
                new ArrayList<Optional<List<Cycle>>>(pairs.length);
        for (var index = 0; index < pairs.length; index++) {
            final var value = values.get(index);
            if (value == null) {
                results.add(Optional.empty());
                continue;
            }
            final var moves = CertificateCodec.decodeValue(value);
            CertificateValidator.validate(pairs[index], moves, maximumMoves);
            replayed.incrementAndGet();
            results.add(Optional.of(moves));
        }
        return List.copyOf(results);
    }

    public List<Cycle> require(final CyclicTargetPair pair,
                               final int maximumMoves) {
        return find(pair, maximumMoves).orElseThrow(() ->
                new IllegalStateException(
                        "Missing sorting certificate: classKey="
                                + pair.extendedToricClassKey()
                                + ", maximumMoves=" + maximumMoves));
    }

    public CertificateRowManifest requireRowManifest(final int row) {
        if (row <= 0 || row > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Invalid Table 2 row: " + row);
        }
        return manifests.requireRow(row);
    }

    public Optional<ResidualObligation> findResidual(
            final ResidualObligation expected
    ) {
        return hasObstruction(expected.key())
                ? Optional.of(expected) : Optional.empty();
    }

    public boolean hasAvoidanceMiss(final AvoidanceSearchMiss expected) {
        return hasObstruction(expected.key());
    }

    public List<AvoidancePhaseManifest> avoidanceManifests() {
        return manifests.avoidanceManifests();
    }

    private boolean hasObstruction(final byte[] key) {
        final var value = get(obstructions, key);
        if (value == null) {
            return false;
        }
        if (value.length != 0) {
            throw new IllegalStateException(
                    "An obstruction entry must have an empty value");
        }
        return true;
    }

    private byte[] get(final ColumnFamilyHandle handle, final byte[] key) {
        try {
            return database.get(handle, readOptions, key);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot read certificate database", failure);
        }
    }

    private static long parsePeriod() {
        final var configured = System.getProperty(STATS_PROPERTY, "60");
        final long period;
        try {
            period = Long.parseLong(configured);
        } catch (final NumberFormatException failure) {
            throw new IllegalArgumentException(
                    STATS_PROPERTY + " must be a nonnegative integer", failure);
        }
        if (period < 0) {
            throw new IllegalArgumentException(
                    STATS_PROPERTY + " must be a nonnegative integer");
        }
        return period;
    }

    private void report() {
        final long now = System.nanoTime();
        final long elapsed = Math.max(1, now - reportStarted);
        final long count = replayed.getAndSet(0);
        reportStarted = now;
        final double seconds = elapsed / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "certificate replay: verified=%d elapsed=%.3fs rate=%.3f/s%n",
                count, seconds, count / seconds);
    }

    @Override
    public void close() {
        if (reporter != null) {
            reporter.shutdownNow();
        }
        obstructions.close();
        witnesses.close();
        database.close();
        closeResources();
    }

    private void closeResources() {
        readOptions.close();
        obstructionOptions.close();
        witnessOptions.close();
        options.close();
    }

    private static void closeHandles(
            final List<ColumnFamilyHandle> handles
    ) {
        handles.forEach(ColumnFamilyHandle::close);
    }
}

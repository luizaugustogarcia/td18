package br.unb.cic.td18.certificate;

import br.unb.cic.td18.avoidance.AvoidanceSearchMiss;
import br.unb.cic.td18.avoidance.ResidualObligation;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;

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

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/** Read-only TD(18) certificate database with separated witness namespaces. */
public final class CertificateStore implements AutoCloseable {
    private static final byte[] OBSTRUCTIONS_COLUMN_FAMILY =
            "obstructions".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERMEDIATE_WITNESSES_COLUMN_FAMILY =
            "avoidance-intermediate-witnesses"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String STATS_PROPERTY =
            "td18.certificateStatsPeriodSeconds";

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions options;
    private final ColumnFamilyOptions witnessOptions;
    private final ColumnFamilyOptions intermediateWitnessOptions;
    private final ColumnFamilyOptions obstructionOptions;
    private final ReadOptions readOptions;
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses;
    private final ColumnFamilyHandle intermediateWitnesses;
    private final ColumnFamilyHandle obstructions;
    private final AtomicLong replayed = new AtomicLong();
    private final ScheduledExecutorService reporter;
    private volatile long reportStarted = System.nanoTime();

    public CertificateStore(final Path directory) {
        final var absolute = directory.toAbsolutePath();
        requireCompatibleColumnFamilies(absolute);
        options = new DBOptions().setMaxOpenFiles(-1);
        witnessOptions = columnFamilyOptions();
        intermediateWitnessOptions = columnFamilyOptions();
        obstructionOptions = columnFamilyOptions();
        readOptions = new ReadOptions()
                .setFillCache(false);
        final var descriptors = List.of(
                new ColumnFamilyDescriptor(
                        RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                new ColumnFamilyDescriptor(
                        OBSTRUCTIONS_COLUMN_FAMILY, obstructionOptions),
                new ColumnFamilyDescriptor(
                        INTERMEDIATE_WITNESSES_COLUMN_FAMILY,
                        intermediateWitnessOptions));
        final var handles = new ArrayList<ColumnFamilyHandle>(3);
        RocksDB opened = null;
        try {
            opened = RocksDB.openReadOnly(
                    options, absolute.toString(), descriptors, handles);
            if (handles.size() != 3) {
                throw new IllegalStateException(
                        "TD(18) database did not open all column families");
            }
            database = opened;
            witnesses = handles.get(0);
            obstructions = handles.get(1);
            intermediateWitnesses = handles.get(2);
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
            if (families.size() != 3
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, OBSTRUCTIONS_COLUMN_FAMILY))
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, INTERMEDIATE_WITNESSES_COLUMN_FAMILY))) {
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

    /** Legacy lookup retained for compatibility tests and old artifacts. */
    public Optional<List<Cycle>> findIntermediate(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        final var value = get(intermediateWitnesses,
                CertificateCodec.intermediateKeyFor(pair, maximumMoves));
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

    public Optional<ResidualObligation> findResidual(
            final ResidualObligation expected
    ) {
        return hasObstruction(expected.key())
                ? Optional.of(expected) : Optional.empty();
    }

    public boolean hasAvoidanceMiss(final AvoidanceSearchMiss expected) {
        return hasObstruction(expected.key());
    }

    /** Streams persisted residual keys without consulting emitter checkpoints. */
    public void forEachObstruction(final java.util.function.Consumer<byte[]> consumer) {
        try (final var iterator = database.newIterator(obstructions, readOptions)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                if (iterator.value().length != 0) {
                    throw new IllegalStateException("An obstruction must have an empty value");
                }
                consumer.accept(iterator.key());
            }
            iterator.status();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot read residual obstruction keys", failure);
        }
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
        final var configured = System.getProperty(STATS_PROPERTY, "0");
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
        intermediateWitnesses.close();
        obstructions.close();
        witnesses.close();
        database.close();
        closeResources();
    }

    private void closeResources() {
        readOptions.close();
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
}

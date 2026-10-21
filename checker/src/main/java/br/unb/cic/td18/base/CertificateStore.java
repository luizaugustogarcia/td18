package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

/** Read-only TD(18) certificate database with replay on every witness lookup. */
public final class CertificateStore implements AutoCloseable {
    private static final byte[] DATABASE_FORMAT_KEY =
            new byte[]{0, 'T', 'D', '1', '8'};
    private static final byte[] DATABASE_FORMAT_VALUE = new byte[]{5};
    private static final String STATS_PROPERTY =
            "td18.certificateStatsPeriodSeconds";

    static {
        RocksDB.loadLibrary();
    }

    private final Options options = new Options()
            .setMaxOpenFiles(-1)
            .setTableFormatConfig(new BlockBasedTableConfig()
                    .setCacheIndexAndFilterBlocks(false));
    private final ReadOptions readOptions = new ReadOptions()
            .setVerifyChecksums(true)
            .setFillCache(false);
    private final RocksDB database;
    private final AtomicLong replayed = new AtomicLong();
    private final ScheduledExecutorService reporter;
    private volatile long reportStarted = System.nanoTime();

    public CertificateStore(final Path directory) {
        RocksDB opened = null;
        try {
            opened = RocksDB.openReadOnly(
                    options, directory.toAbsolutePath().toString());
            final var format = opened.get(readOptions, DATABASE_FORMAT_KEY);
            if (!Arrays.equals(format, DATABASE_FORMAT_VALUE)) {
                throw new IllegalStateException(
                        "Unsupported TD(18) certificate database format");
            }
            database = opened;
        } catch (final RocksDBException failure) {
            if (opened != null) {
                opened.close();
            }
            readOptions.close();
            options.close();
            throw new IllegalStateException(
                    "Cannot open certificate database " + directory, failure);
        } catch (final RuntimeException failure) {
            if (opened != null) {
                opened.close();
            }
            readOptions.close();
            options.close();
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

    public Optional<List<Cycle>> find(final CyclicTargetPair pair,
                                      final int maximumMoves) {
        final var value = get(CertificateCodec.keyFor(pair));
        if (value == null) {
            return Optional.empty();
        }
        final var moves = CertificateCodec.decodeValue(value);
        CertificateValidator.validate(pair, moves, maximumMoves);
        replayed.incrementAndGet();
        return Optional.of(moves);
    }

    /**
     * Looks up and independently replays a position-preserving batch with one
     * RocksDB multi-get operation.
     */
    public List<Optional<List<Cycle>>> find(
            final CyclicTargetPair[] pairs,
            final int maximumMoves
    ) {
        final var keys = new ArrayList<byte[]>(pairs.length);
        for (final var pair : pairs) {
            keys.add(CertificateCodec.keyFor(pair));
        }

        final List<byte[]> values;
        try {
            values = database.multiGetAsList(readOptions, keys);
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
            throw new IllegalArgumentException("Invalid Table 2 row: " + row);
        }
        final var key = new byte[]{
                2, 3, (byte) (row >>> Byte.SIZE), (byte) row
        };
        final var value = get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Missing certificate row manifest: " + row);
        }
        return CertificateRowManifest.decode(key, value);
    }

    public Optional<ResidualObligation> findResidual(
            final ResidualObligation expected
    ) {
        final var value = get(expected.key());
        if (value == null) {
            return Optional.empty();
        }
        final var decoded = ResidualObligation.decode(expected.key(), value);
        if (!decoded.equals(expected)) {
            throw new IllegalStateException(
                    "Residual record does not match its generated class");
        }
        return Optional.of(decoded);
    }

    public boolean hasAvoidanceMiss(final AvoidanceSearchMiss expected) {
        final var value = get(expected.key());
        if (value == null) {
            return false;
        }
        final var decoded = AvoidanceSearchMiss.decode(expected.key(), value);
        if (!decoded.equals(expected)) {
            throw new IllegalStateException(
                    "Avoidance-miss record does not match its class");
        }
        return true;
    }

    /** Reads the small range-manifest keyspace without scanning certificates. */
    public List<AvoidancePhaseManifest> avoidanceManifests() {
        final var result = new ArrayList<AvoidancePhaseManifest>();
        try (final var iterator = database.newIterator(readOptions)) {
            iterator.seek(new byte[]{4});
            while (iterator.isValid()
                    && iterator.key().length > 0
                    && Byte.toUnsignedInt(iterator.key()[0]) == 4) {
                result.add(AvoidancePhaseManifest.decode(
                        iterator.key(), iterator.value()));
                iterator.next();
            }
            iterator.status();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot read avoidance manifests", failure);
        }
        return List.copyOf(result);
    }

    private byte[] get(final byte[] key) {
        try {
            return database.get(readOptions, key);
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
        database.close();
        readOptions.close();
        options.close();
    }
}

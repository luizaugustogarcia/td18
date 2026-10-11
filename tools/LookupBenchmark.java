import br.unb.cic.td18.CircularClassOperations;
import br.unb.cic.tdp.base.CertificateCodec;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Read-only membership microbenchmark for any supported parent domain. */
public final class LookupBenchmark {
    public static void main(final String[] args) throws Exception {
        if (args.length < 2 || args.length > 8) {
            System.out.println("Usage: LookupBenchmark DATABASE DOMAIN [WORKERS] "
                    + "[PARENTS] [REPEATS] [BATCH] [CACHE_MIB] [PASSES]");
            return;
        }
        RocksDB.loadLibrary();
        final var directory = Path.of(args[0]).toAbsolutePath();
        final var domain = positiveArgument(args, 1, 0);
        if (domain < 2 || domain > 31) {
            throw new IllegalArgumentException("Parent domain must belong to 2..31");
        }
        final var workers = positiveArgument(args, 2, Runtime.getRuntime().availableProcessors());
        final var parentCount = positiveArgument(args, 3, 96);
        final var repeats = positiveArgument(args, 4, 3);
        final var batchSize = positiveArgument(args, 5, 500);
        final var cacheBytes = Math.multiplyExact(positiveArgument(args, 6, 256), 1024L * 1024L);
        final var passes = positiveArgument(args, 7, 2);
        final var queries = new ArrayList<byte[]>();
        try (final var options = new Options().setMaxOpenFiles(-1);
             final var database = RocksDB.openReadOnly(options, directory.toString());
             final var scans = new ReadOptions().setFillCache(false)) {
            try (final var iterator = database.newIterator(scans)) {
                iterator.seekToFirst();
                for (var count = 0; count < parentCount && iterator.isValid(); count++, iterator.next()) {
                    final var parent = CertificateCodec.pairForKey(iterator.key(), domain);
                    for (final var insertion : CircularClassOperations.insertions(parent)) {
                        queries.add(CertificateCodec.keyFor(CircularClassOperations.removeSymbol(
                                insertion.child(), 0).canonicalRepresentative()));
                    }
                }
                iterator.status();
            }
        }
        if (queries.isEmpty()) throw new IllegalArgumentException("Parent store is empty");
        long expectedHits = -1;
        for (final var configuration : List.of(
                new Configuration(false, 1), new Configuration(true, 1),
                new Configuration(true, batchSize))) {
            // Each configuration owns a fresh cache; an uncached run must not
            // inherit the blocks populated by a previous cached configuration.
            try (final var cache = new LRUCache(cacheBytes, -1, false, 0.2);
                 final var options = new Options().setMaxOpenFiles(-1)
                         .setTableFormatConfig(new BlockBasedTableConfig()
                                 .setBlockCache(cache)
                                 .setCacheIndexAndFilterBlocks(true)
                                 .setCacheIndexAndFilterBlocksWithHighPriority(true)
                                 .setPinL0FilterAndIndexBlocksInCache(true));
                 final var database = RocksDB.openReadOnly(options, directory.toString())) {
                for (var pass = 0; pass < passes; pass++) {
                    try (final var reads = new ReadOptions().setFillCache(configuration.fillCache());
                         final var executor = Executors.newFixedThreadPool(workers)) {
                        final var started = System.nanoTime();
                        final var futures = new ArrayList<java.util.concurrent.Future<Long>>();
                        for (var worker = 0; worker < workers; worker++) {
                            final var shift = worker * 137;
                            futures.add(executor.submit(() -> {
                                long hits = 0;
                                // Every worker uses the same multiset in a different cyclic order.
                                final var keys = new ArrayList<byte[]>(queries.size());
                                for (var index = 0; index < queries.size(); index++) {
                                    keys.add(queries.get((index + shift) % queries.size()));
                                }
                                for (var repeat = 0; repeat < repeats; repeat++) {
                                    if (configuration.batchSize() == 1) {
                                        for (final var key : keys) {
                                            if (database.get(reads, key) != null) hits++;
                                        }
                                    } else {
                                        for (var offset = 0; offset < keys.size(); offset += configuration.batchSize()) {
                                            for (final var value : database.multiGetAsList(reads,
                                                    keys.subList(offset, Math.min(
                                                            offset + configuration.batchSize(), keys.size())))) {
                                                if (value != null) hits++;
                                            }
                                        }
                                    }
                                }
                                return hits;
                            }));
                        }
                        long hits = 0;
                        for (final var future : futures) hits += future.get();
                        final var elapsed = (System.nanoTime() - started) / 1.0e9;
                        final var lookups = (long) repeats * queries.size() * workers;
                        if (expectedHits < 0) expectedHits = hits;
                        if (hits != expectedHits) throw new AssertionError("Membership results differ");
                        System.out.printf("pass=%d fillCache=%s batch=%d lookups=%d hits=%d "
                                        + "seconds=%.3f lookups/s=%.0f cacheBytes=%d%n",
                                pass + 1, configuration.fillCache(), configuration.batchSize(),
                                lookups, hits, elapsed, lookups / elapsed, cache.getUsage());
                    }
                }
            }
        }
    }

    private static int positiveArgument(final String[] args, final int index, final int fallback) {
        final var value = index < args.length ? Integer.parseInt(args[index]) : fallback;
        if (value < 1) throw new IllegalArgumentException("Arguments must be positive");
        return value;
    }

    private record Configuration(boolean fillCache, int batchSize) {
    }
}

package br.unb.cic.td18.base;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpuPrefixSearchStatsTest {

    @Test
    void recordsOutcomesAndDrainsEachWindow() {
        final var stats = new CpuPrefixSearchStats();

        stats.completed(2, 7);
        stats.completed(3, 11);
        stats.completed(0, 13);
        stats.failed(17);

        assertEquals(new CpuPrefixSearchStats.Snapshot(3, 2, 1, 1, 48, 5),
                stats.snapshot());
        assertEmpty(stats.snapshot());

        stats.completed(1, 19);
        assertEquals(new CpuPrefixSearchStats.Snapshot(1, 1, 0, 0, 19, 1),
                stats.snapshot());
        assertEmpty(stats.snapshot());
    }

    @Test
    void clampsNegativeDurations() {
        final var stats = new CpuPrefixSearchStats();

        stats.completed(0, -1);
        stats.failed(-2);

        assertEquals(new CpuPrefixSearchStats.Snapshot(1, 0, 1, 1, 0, 0),
                stats.snapshot());
    }

    @Test
    void workersUpdateWithoutAcquiringTheSharedStatsMonitor() throws Exception {
        final var stats = new CpuPrefixSearchStats();
        try (final var executor = Executors.newSingleThreadExecutor()) {
            synchronized (stats) {
                executor.submit(() -> {
                    stats.completed(2, 7);
                    stats.failed(11);
                }).get(5, TimeUnit.SECONDS);
            }
        }

        // The exited thread's final counters must still be included.
        assertEquals(new CpuPrefixSearchStats.Snapshot(1, 1, 0, 1, 18, 2),
                stats.snapshot());
        assertEmpty(stats.snapshot());
    }

    @Test
    @Timeout(15)
    void concurrentSnapshotsKeepEverySearchTogetherAndLoseNoUpdates() throws Exception {
        final var stats = new CpuPrefixSearchStats();
        final int workers = 8;
        final int attemptsPerWorker = 10_000;
        final var start = new CountDownLatch(1);
        final var finished = new CountDownLatch(workers);
        final var snapshots = new ArrayList<CpuPrefixSearchStats.Snapshot>();

        try (final var executor = Executors.newFixedThreadPool(workers)) {
            final var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < workers; worker++) {
                tasks.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int attempt = 0; attempt < attemptsPerWorker; attempt++) {
                            stats.completed(2, 7);
                            stats.completed(0, 3);
                            stats.failed(11);
                        }
                    } finally {
                        finished.countDown();
                    }
                    return null;
                }));
            }
            start.countDown();
            do {
                snapshots.add(stats.snapshot());
            } while (!finished.await(1, TimeUnit.MILLISECONDS));
            for (final var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        snapshots.add(stats.snapshot());

        long completed = 0;
        long qualified = 0;
        long misses = 0;
        long failed = 0;
        long elapsedNanos = 0;
        long prefixMoves = 0;
        for (final var snapshot : snapshots) {
            assertEquals(snapshot.completed(),
                    snapshot.qualified() + snapshot.misses());
            assertEquals(2 * snapshot.qualified(), snapshot.prefixMoves());
            assertEquals(7 * snapshot.qualified() + 3 * snapshot.misses()
                    + 11 * snapshot.failed(), snapshot.elapsedNanos());
            completed += snapshot.completed();
            qualified += snapshot.qualified();
            misses += snapshot.misses();
            failed += snapshot.failed();
            elapsedNanos += snapshot.elapsedNanos();
            prefixMoves += snapshot.prefixMoves();
        }
        final long attempts = (long) workers * attemptsPerWorker;
        assertEquals(2 * attempts, completed);
        assertEquals(attempts, qualified);
        assertEquals(attempts, misses);
        assertEquals(attempts, failed);
        assertEquals(21 * attempts, elapsedNanos);
        assertEquals(2 * attempts, prefixMoves);
        assertEmpty(stats.snapshot());
    }

    private static void assertEmpty(final CpuPrefixSearchStats.Snapshot snapshot) {
        assertEquals(new CpuPrefixSearchStats.Snapshot(0, 0, 0, 0, 0, 0), snapshot);
    }
}

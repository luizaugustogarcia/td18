package br.unb.cic.td18.search;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Windowed statistics for the bounded CPU-prefix pre-pass. Each calling thread
 * owns a counter shard, so search workers never contend with one another on
 * updates. A snapshot briefly locks each shard to drain it; all fields for a
 * search stay in the same window. Window boundaries are per shard rather than
 * a single instant shared by all workers.
 */
public final class CpuPrefixSearchStats {
    private static final CpuPrefixSearchStats INSTANCE =
            new CpuPrefixSearchStats();

    // Retain one shard per participating thread, including its final updates
    // after it exits. The emitter uses a fixed worker pool.
    private final ConcurrentLinkedQueue<Counters> shards =
            new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Counters> local = ThreadLocal.withInitial(() -> {
        final var counters = new Counters();
        shards.add(counters);
        return counters;
    });

    CpuPrefixSearchStats() {
    }

    public static CpuPrefixSearchStats instance() {
        return INSTANCE;
    }

    void completed(final int prefixLength, final long searchNanos) {
        final var counters = local.get();
        synchronized (counters) {
            counters.completed++;
            if (prefixLength > 0) {
                counters.qualified++;
                counters.prefixMoves += prefixLength;
            }
            counters.elapsedNanos += Math.max(0, searchNanos);
        }
    }

    void failed(final long searchNanos) {
        final var counters = local.get();
        synchronized (counters) {
            counters.failed++;
            counters.elapsedNanos += Math.max(0, searchNanos);
        }
    }

    public synchronized Snapshot snapshot() {
        long completed = 0;
        long qualified = 0;
        long failed = 0;
        long elapsedNanos = 0;
        long prefixMoves = 0;
        for (final var counters : shards) {
            synchronized (counters) {
                completed += counters.completed;
                qualified += counters.qualified;
                failed += counters.failed;
                elapsedNanos += counters.elapsedNanos;
                prefixMoves += counters.prefixMoves;
                counters.completed = 0;
                counters.qualified = 0;
                counters.failed = 0;
                counters.elapsedNanos = 0;
                counters.prefixMoves = 0;
            }
        }
        return new Snapshot(
                completed, qualified, completed - qualified, failed,
                elapsedNanos, prefixMoves);
    }

    private static final class Counters {
        private long completed;
        private long qualified;
        private long failed;
        private long elapsedNanos;
        private long prefixMoves;
    }

    public record Snapshot(
            long completed,
            long qualified,
            long misses,
            long failed,
            long elapsedNanos,
            long prefixMoves
    ) {
    }
}

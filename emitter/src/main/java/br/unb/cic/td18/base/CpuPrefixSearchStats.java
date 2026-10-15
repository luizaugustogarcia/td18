package br.unb.cic.td18.base;

/** Windowed statistics for the bounded CPU-prefix pre-pass. */
final class CpuPrefixSearchStats {
    private static final CpuPrefixSearchStats INSTANCE =
            new CpuPrefixSearchStats();

    private long completed;
    private long qualified;
    private long failed;
    private long elapsedNanos;
    private long prefixMoves;

    private CpuPrefixSearchStats() {
    }

    static CpuPrefixSearchStats instance() {
        return INSTANCE;
    }

    synchronized void completed(final int prefixLength,
                                final long searchNanos) {
        completed++;
        if (prefixLength > 0) {
            qualified++;
            prefixMoves += prefixLength;
        }
        elapsedNanos += Math.max(0, searchNanos);
    }

    synchronized void failed(final long searchNanos) {
        failed++;
        elapsedNanos += Math.max(0, searchNanos);
    }

    synchronized Snapshot snapshot() {
        final var result = new Snapshot(
                completed, qualified, completed - qualified, failed,
                elapsedNanos, prefixMoves);
        completed = 0;
        qualified = 0;
        failed = 0;
        elapsedNanos = 0;
        prefixMoves = 0;
        return result;
    }

    record Snapshot(
            long completed,
            long qualified,
            long misses,
            long failed,
            long elapsedNanos,
            long prefixMoves
    ) {
    }
}

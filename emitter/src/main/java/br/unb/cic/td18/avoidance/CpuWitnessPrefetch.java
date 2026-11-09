package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.model.CyclicTargetPair;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.List;

/** Bounded CPU searches ahead of the GPU scan; only replayed witnesses can remove GPU work. */
final class CpuWitnessPrefetch implements AutoCloseable {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong completed = new AtomicLong(), covered = new AtomicLong();
    private final ThreadPoolExecutor workers;
    private final Thread producer;

    CpuWitnessPrefetch(DiskStateSet states, BiPredicate<CyclicTargetPair, Integer> search) {
        this(states, search, configuredWorkers());
    }

    CpuWitnessPrefetch(DiskStateSet states, BiPredicate<CyclicTargetPair, Integer> search, int count) {
        this(states, search, count, null);
    }

    CpuWitnessPrefetch(DiskStateSet states, BiPredicate<CyclicTargetPair, Integer> search,
                       Function<List<DiskStateSet.State>, boolean[]> lookup) {
        this(states, search, configuredWorkers(), lookup);
    }

    CpuWitnessPrefetch(DiskStateSet states, BiPredicate<CyclicTargetPair, Integer> search, int count,
                       Function<List<DiskStateSet.State>, boolean[]> lookup) {
        if (count < 0) throw new IllegalArgumentException("CPU prefetch worker count must be nonnegative");
        if (search == null || count == 0) { workers = null; producer = null; return; }
        workers = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), Thread.ofPlatform().name("cpu-witness-prefetch-", 0).factory(),
                (task, pool) -> {
                    try {
                        while (!cancelled.get() && !pool.isShutdown())
                            if (pool.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) return;
                    } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    throw new CancellationException("CPU prefetch stopped");
                });
        producer = Thread.ofPlatform().name("cpu-witness-prefetch-reader").unstarted(() -> {
            try {
                java.util.function.Consumer<DiskStateSet.State> submit = state -> {
                    requireRunning();
                    workers.execute(() -> search(state, search));
                };
                if (lookup == null) states.forEach(submit);
                else states.forEachBatch(batch -> {
                    requireRunning();
                    boolean[] hits = lookup.apply(batch);
                    if (hits == null || hits.length != batch.size())
                        throw new IllegalStateException("Incomplete CPU prefetch batch lookup");
                    for (boolean hit : hits) if (hit) { covered.incrementAndGet(); completed.incrementAndGet(); }
                    for (int i = 0; i < batch.size(); i++) {
                        requireRunning();
                        if (!hits[i]) submit.accept(batch.get(i));
                    }
                });
            } catch (Throwable error) { failed(error); }
            finally { workers.shutdown(); }
        });
        producer.start();
    }

    private void requireRunning() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("CPU prefetch stopped");
        check();
    }

    private void search(DiskStateSet.State state, BiPredicate<CyclicTargetPair, Integer> search) {
        if (cancelled.get()) return;
        try {
            if (search.test(state.pair(), state.maximumMoves())) covered.incrementAndGet();
            completed.incrementAndGet();
        } catch (Throwable error) { failed(error); }
    }

    private static int configuredWorkers() {
        var configured = System.getProperty("td18.cpuPrefetchWorkers", "auto");
        return configured.equals("auto") ? Runtime.getRuntime().availableProcessors() : Integer.parseInt(configured);
    }

    private void failed(Throwable error) {
        if (cancelled.get() && error instanceof CancellationException) return;
        failure.compareAndSet(null, error);
        cancelled.set(true);
        workers.shutdownNow();
    }

    void check() {
        if (failure.get() != null) throw new IllegalStateException("CPU witness prefetch failed", failure.get());
    }

    String progress() {
        return " CPU prefetch completed=" + completed.get() + " covered=" + covered.get()
                + " queued=" + (workers == null ? 0 : workers.getQueue().size());
    }

    @Override public void close() {
        cancelled.set(true);
        if (producer != null) {
            producer.interrupt();
            workers.shutdownNow();
            boolean interrupted = false;
            for (;;) {
                try {
                    producer.join();
                    if (workers.awaitTermination(1, TimeUnit.SECONDS)) break;
                } catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        check();
    }
}

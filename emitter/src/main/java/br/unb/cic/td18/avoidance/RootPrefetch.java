package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.joint.ErikssonRoute;
import br.unb.cic.td18.model.CyclicTargetPair;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advisory final-root searches alongside inverse persistence. Every canonical
 * root is still checked by the final inventory scan, including dropped batches
 * and keys skipped by the bounded recent-seen cache.
 */
public final class RootPrefetch implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RootPrefetch.class);
    private static final int BATCH_KEYS = 65536;
    private static final int STOPPED = Integer.MIN_VALUE;
    private static final byte[] EMPTY = {};
    private final int gpuWorkers;
    private final BiPredicate<CyclicTargetPair, Integer> gpu;
    private final ConcurrentLinkedQueue<List<byte[]>> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore slots, available = new Semaphore(0), seenSlots;
    private final ConcurrentHashMap<ByteBuffer, Boolean> seen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ByteBuffer> seenOrder = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger publishers = new AtomicInteger();
    private final LongAdder accepted = new LongAdder(), examined = new LongAdder();
    private final LongAdder searched = new LongAdder(), closed = new LongAdder(), dropped = new LongAdder();
    private final java.util.concurrent.ScheduledExecutorService reporter =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true)
                    .name("root-prefetch-progress").factory());
    private volatile boolean stopping;
    private ExecutorService workers;
    private boolean finished;

    public RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu) {
        this(gpuWorkers, gpu, property("td18.rootPrefetchQueueBatches", 256),
                property("td18.rootPrefetchSeenKeys", 1048576));
    }

    RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu,
                 int queueBatches, int seenKeys) {
        if (gpuWorkers < 1 || queueBatches < 1 || seenKeys < 1)
            throw new IllegalArgumentException("Positive root-prefetch capacities required");
        this.gpuWorkers = gpuWorkers;
        this.gpu = Objects.requireNonNull(gpu);
        slots = new Semaphore(queueBatches);
        seenSlots = new Semaphore(seenKeys);
    }

    private static int property(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /** Copies batch references without waiting for queue capacity. Keys are immutable. */
    public void publish(List<byte[]> encodedRootKeys) {
        Objects.requireNonNull(encodedRootKeys);
        check();
        for (;;) {
            int count = publishers.get();
            if (count < 0 || stopping) return;
            if (count == Integer.MAX_VALUE) throw new IllegalStateException("Too many root publishers");
            if (publishers.compareAndSet(count, count + 1)) break;
        }
        try {
            for (int first = 0; first < encodedRootKeys.size();) {
                check();
                if (stopping || !slots.tryAcquire()) {
                    dropped.add(encodedRootKeys.size() - first);
                    return;
                }
                int end = first + Math.min(BATCH_KEYS, encodedRootKeys.size() - first);
                try {
                    queue.add(List.copyOf(encodedRootKeys.subList(first, end)));
                    accepted.add(end - first);
                    available.release();
                } catch (RuntimeException | Error error) {
                    slots.release();
                    throw error;
                }
                first = end;
            }
            check();
        } finally { publishers.decrementAndGet(); }
    }

    /** Starts once the source GPU queue has drained. Repeated activation is harmless. */
    public synchronized void activate() {
        check();
        if (stopping || workers != null) return;
        log.info("Root GPU prefetch activated: workers={}, queued keys={}", gpuWorkers, accepted.sum());
        reporter.scheduleWithFixedDelay(this::report, 10, 10, TimeUnit.SECONDS);
        workers = Executors.newFixedThreadPool(gpuWorkers,
                Thread.ofPlatform().name("root-prefetch-", 0).factory());
        for (int i = 0; i < gpuWorkers; i++) workers.submit(this::consume);
    }

    private void consume() {
        try {
            while (!stopping) {
                if (!available.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
                if (stopping) return;
                var batch = queue.poll();
                if (batch == null) continue;
                slots.release();
                for (byte[] key : batch) {
                    if (stopping) return;
                    examined.increment();
                    if (!remember(key)) continue;
                    var state = DiskStateSet.decode(key, EMPTY);
                    if (state.maximumMoves() != ErikssonRoute.ROOT_BUDGET
                            || state.pair().getBeta().size() != ErikssonRoute.ROOT_SYMBOLS)
                        throw new IllegalArgumentException("Root prefetch requires 19 symbols and budget 10");
                    searched.increment();
                    if (gpu.test(state.pair(), state.maximumMoves())) closed.increment();
                }
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure.compareAndSet(null, error);
            stopping = true;
            available.release(gpuWorkers);
        }
    }

    /** A cache miss may remain uncached during concurrent eviction; it is still searched. */
    private boolean remember(byte[] bytes) {
        var key = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        if (seen.containsKey(key)) return false;
        if (!seenSlots.tryAcquire()) {
            var oldest = seenOrder.poll();
            if (oldest == null) return true;
            if (seen.remove(oldest) != null) seenSlots.release();
            if (!seenSlots.tryAcquire()) return true;
        }
        if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
            seenSlots.release();
            return false;
        }
        seenOrder.add(key);
        return true;
    }

    public void check() {
        var error = failure.get();
        if (error != null) throw new IllegalStateException("Root-prefetch search failed", error);
    }

    private void report() {
        log.info("Root GPU prefetch: examined={}, searched={}, closed={}, queued={}, dropped={}, active={}",
                examined.sum(), searched.sum(), closed.sum(), finished ? 0 : accepted.sum() - examined.sum(),
                dropped.sum(), !stopping);
    }

    /** Discards optional backlog and joins in-flight GPU calls before final scanning. */
    public synchronized void finish() {
        boolean interrupted = false;
        if (!finished) {
            stopping = true;
            publishers.getAndUpdate(count -> count | STOPPED);
            while (publishers.get() != STOPPED) {
                if (Thread.interrupted()) interrupted = true;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            queue.clear();
            available.release(gpuWorkers);
            if (workers != null) {
                workers.shutdown();
                for (;;) {
                    try { if (workers.awaitTermination(1, TimeUnit.SECONDS)) break; }
                    catch (InterruptedException error) { interrupted = true; }
                }
            }
            queue.clear();
            seen.clear();
            seenOrder.clear();
            dropped.add(accepted.sum() - examined.sum());
            finished = true;
            reporter.shutdown();
            report();
        }
        if (interrupted) Thread.currentThread().interrupt();
        check();
        if (interrupted) throw new IllegalStateException("Interrupted while finishing root prefetch");
    }

    @Override public void close() { finish(); }
}

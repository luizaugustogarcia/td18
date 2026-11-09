package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.model.CyclicTargetPair;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disk-backed final reconstruction from regenerated endpoint witness misses. */
public final class JointAvoidance {
    private static final Logger log = LoggerFactory.getLogger(JointAvoidance.class);
    private JointAvoidance() { }
    public record Result(long endpoints, long intermediates, long propagatedIntermediates,
                         long roots, long unresolved) { }

    public static Result resolve(DiskStateSet endpoints, Path work,
                                 Predicate<CyclicTargetPair> intermediateClosure,
                                 Predicate<CyclicTargetPair> rootClosure) {
        return resolve(endpoints, work, intermediateClosure, rootClosure, null);
    }

    public static Result resolve(DiskStateSet endpoints, Path work,
                                 Predicate<CyclicTargetPair> intermediateClosure,
                                 Predicate<CyclicTargetPair> rootClosure,
                                 Function<List<DiskStateSet.State>, boolean[]> lookup) {
        int workers = DirectTwistedBraceletGenerator.configuredWorkerCount();
        try (var intermediates = new DiskStateSet(work.resolve("intermediates"));
             var progress = new Progress()) {
            long endpointCount = endpoints.seal();
            var generated = new AtomicLong();
            progress.phase(() -> "generating intermediates: endpoints=" + generated.get() + "/" + endpointCount);
            endpoints.forEachConcurrently(Runtime.getRuntime().availableProcessors(), state -> {
                requireState(state, 16, 8);
                JointInverseReconstruction.intermediates(state.pair(), p -> intermediates.add(p, 9));
                generated.incrementAndGet();
            });
            long middleCount = intermediates.seal();
            new ReconstructionCheckpoint(endpointCount, middleCount).write(work);
            log.info("Reconstruction: endpoints={}, intermediateClasses={}, workers={}", endpointCount, middleCount, workers);
            return searchIntermediates(endpointCount, intermediates, work, intermediateClosure, rootClosure, lookup, workers, progress);
        }
    }

    /** Resumes a sealed intermediate wave; roots are regenerated and available witnesses replayed. */
    public static Result resume(Path checkpointWork, Path work,
                                Predicate<CyclicTargetPair> intermediateClosure,
                                Predicate<CyclicTargetPair> rootClosure) {
        return resume(checkpointWork, work, intermediateClosure, rootClosure, null);
    }

    public static Result resume(Path checkpointWork, Path work,
                                Predicate<CyclicTargetPair> intermediateClosure,
                                Predicate<CyclicTargetPair> rootClosure,
                                Function<List<DiskStateSet.State>, boolean[]> lookup) {
        var checkpoint = ReconstructionCheckpoint.read(checkpointWork);
        int workers = DirectTwistedBraceletGenerator.configuredWorkerCount();
        try (var endpoints = DiskStateSet.openSealed(checkpointWork.resolve("endpoint-residuals"), checkpoint.endpoints());
             var intermediates = DiskStateSet.openSealed(checkpointWork.resolve("intermediates"), checkpoint.intermediates());
             var progress = new Progress()) {
            endpoints.forEach(state -> requireState(state, 16, 8));
            log.info("Resuming reconstruction: endpoints={}, intermediateClasses={}, workers={}, lookupBatch={}, sorting=GPU, source={}",
                    checkpoint.endpoints(), checkpoint.intermediates(), workers, DiskStateSet.lookupBatchSize(), checkpointWork);
            return searchIntermediates(checkpoint.endpoints(), intermediates, work,
                    intermediateClosure, rootClosure, lookup, workers, progress);
        }
    }

    private static Result searchIntermediates(long endpointCount, DiskStateSet intermediates, Path work,
                                              Predicate<CyclicTargetPair> intermediateClosure,
                                              Predicate<CyclicTargetPair> rootClosure,
                                              Function<List<DiskStateSet.State>, boolean[]> lookup, int workers, Progress progress) {
        try (var roots = new DiskStateSet(work.resolve("roots"))) {
            long middleCount = intermediates.seal();
            var completed = new AtomicLong();
            var cached = new AtomicLong();
            var propagated = new AtomicLong();
            var intermediateLookup = checkedLookup(lookup, 18, 9);
            progress.phase(() -> "searching intermediates: completed=" + completed.get() + "/" + middleCount
                    + " propagated=" + propagated.get() + " cached=" + cached.get());
            intermediates.forEachConcurrently(workers, intermediateLookup, state -> {
                cached.incrementAndGet(); completed.incrementAndGet();
            }, state -> {
                requireState(state, 18, 9);
                if (!intermediateClosure.test(state.pair())) {
                    propagated.incrementAndGet();
                    JointInverseReconstruction.roots(state.pair(), p -> roots.add(p, 10));
                }
                completed.incrementAndGet();
            });
            long rootCount = roots.seal();
            log.info("Reconstruction: propagatedIntermediates={}, rootClasses={}", propagated.get(), rootCount);
            var sorted = new AtomicLong();
            var rootCached = new AtomicLong();
            var unresolved = new AtomicLong();
            var rootLookup = checkedLookup(lookup, 19, 10);
            progress.phase(() -> "searching roots: completed=" + sorted.get() + "/" + rootCount
                    + " unresolved=" + unresolved.get() + " cached=" + rootCached.get());
            roots.forEachConcurrently(workers, rootLookup, state -> {
                rootCached.incrementAndGet(); sorted.incrementAndGet();
            }, state -> {
                requireState(state, 19, 10);
                if (!rootClosure.test(state.pair())) unresolved.incrementAndGet();
                sorted.incrementAndGet();
            });
            return new Result(endpointCount, middleCount, propagated.get(), rootCount, unresolved.get());
        }
    }

    private static Function<List<DiskStateSet.State>, boolean[]> checkedLookup(
            Function<List<DiskStateSet.State>, boolean[]> lookup, int domain, int budget) {
        if (lookup == null) return null;
        return batch -> {
            for (var state : batch) requireState(state, domain, budget);
            return lookup.apply(batch);
        };
    }

    private static void requireState(DiskStateSet.State state, int domain, int budget) {
        if (state.pair().getBeta().size() != domain || state.maximumMoves() != budget)
            throw new IllegalStateException("Reconstruction state must have domain " + domain + " and budget " + budget);
    }

    private static final class Progress implements AutoCloseable {
        private volatile Supplier<String> current = () -> "initializing";
        private final java.util.concurrent.ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("reconstruction-progress").factory());
        Progress() {
            reporter.scheduleWithFixedDelay(() -> log.info("Reconstruction progress: {}", current.get()), 10, 10, TimeUnit.SECONDS);
        }
        void phase(Supplier<String> phase) { current = phase; }
        @Override public void close() { reporter.shutdownNow(); }
    }
}

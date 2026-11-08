package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.model.CyclicTargetPair;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
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
        try (var intermediates = new DiskStateSet(work.resolve("intermediates"));
             var roots = new DiskStateSet(work.resolve("roots"))) {
            endpoints.forEach(state -> {
                if (state.maximumMoves() != 8) throw new IllegalStateException("Endpoint budget must be eight");
                JointInverseReconstruction.intermediates(state.pair(), p -> intermediates.add(p, 9));
            });
            long middleCount = intermediates.seal();
            log.info("Reconstruction: endpoints={}, intermediateClasses={}", endpoints.seal(), middleCount);
            var propagated = new AtomicLong();
            intermediates.forEach(state -> {
                if (!intermediateClosure.test(state.pair())) {
                    propagated.incrementAndGet();
                    JointInverseReconstruction.roots(state.pair(), p -> roots.add(p, 10));
                }
            });
            long rootCount = roots.seal();
            log.info("Reconstruction: propagatedIntermediates={}, rootClasses={}", propagated.get(), rootCount);
            var unresolved = new AtomicLong();
            roots.forEach(state -> { if (!rootClosure.test(state.pair())) unresolved.incrementAndGet(); });
            return new Result(endpoints.seal(), middleCount, propagated.get(), rootCount, unresolved.get());
        }
    }
}

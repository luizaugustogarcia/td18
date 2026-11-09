package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CpuWitnessPrefetchTest {
    @Test void cpuWorkersRunIndependentlyAndCancellationJoinsThemBeforeClosingTheSet(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            for (int budget = 1; budget <= 4; budget++) states.add(pair, budget);
            states.seal();
            var entered = new CountDownLatch(2);
            var active = new AtomicInteger();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (var prefetch = new CpuWitnessPrefetch(states, (input, budget) -> {
                    active.incrementAndGet(); entered.countDown();
                    try { new CountDownLatch(1).await(); return false; }
                    catch (InterruptedException error) { throw new CancellationException(); }
                    finally { active.decrementAndGet(); }
                }, 2)) {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    prefetch.check();
                }
            });
            assertEquals(0, active.get());
            assertEquals(4, states.seal());
        }
    }
}

package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static br.unb.cic.td18.ConcurrencyTestSupport.await;
import static br.unb.cic.td18.ConcurrencyTestSupport.submit;
import static org.junit.jupiter.api.Assertions.*;

class CoalescingBoundedSorterTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
    }

    @Test
    void equivalentRequestsShareOneSearchButOwnIndependentWitnesses() throws Exception {
        final var pair = pair();
        final var equivalent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(2, 0, 1)), Cycle.of(2, 0, 1));
        final var entered = new CountDownLatch(1);
        final var finish = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var backendWitness = List.of(Cycle.of(0, 1, 2));
        final var sorter = new CoalescingBoundedSorter((canonical, depth) -> {
            assertSame(canonical, canonical.canonicalRepresentative());
            calls.incrementAndGet();
            entered.countDown();
            await(finish);
            return Optional.of(backendWitness);
        });
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var owner = submit(executor, () -> sorter.sort(pair, 1));
            try {
                await(entered);
                final var duplicate = submit(executor, () -> sorter.sort(equivalent, 1));
                duplicate.awaitWaiting();
                assertEquals(1, calls.get());
                finish.countDown();
                final var first = owner.get().orElseThrow();
                final var second = duplicate.get().orElseThrow();
                assertEquals(first, second);
                assertNotSame(first.getFirst(), second.getFirst());
                first.getFirst().getSymbols()[0] = 99;
                assertArrayEquals(new int[]{0, 1, 2}, second.getFirst().getSymbols());
                assertArrayEquals(new int[]{0, 1, 2}, backendWitness.getFirst().getSymbols());
                sorter.sort(pair, 1);
                assertEquals(2, calls.get(), "Only overlapping requests are coalesced");
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void sharesExhaustiveFailureWithoutConfusingItWithAnException() throws Exception {
        final var entered = new CountDownLatch(1);
        final var finish = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var sorter = new CoalescingBoundedSorter((canonical, depth) -> {
            calls.incrementAndGet();
            entered.countDown();
            await(finish);
            return Optional.empty();
        });
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var owner = submit(executor, () -> sorter.sort(pair(), 0));
            try {
                await(entered);
                final var duplicate = submit(executor, () -> sorter.sort(pair(), 0));
                duplicate.awaitWaiting();
                assertEquals(1, calls.get());
                finish.countDown();
                assertTrue(owner.get().isEmpty());
                assertTrue(duplicate.get().isEmpty());
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void propagatesBackendExceptionsToWaitersAndAllowsRetry() throws Exception {
        final var entered = new CountDownLatch(1);
        final var finish = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var failure = new IllegalStateException("Backend failed");
        final var sorter = new CoalescingBoundedSorter((canonical, depth) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(finish);
                throw failure;
            }
            return Optional.empty();
        });
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var owner = submit(executor, () -> sorter.sort(pair(), 1));
            try {
                await(entered);
                final var duplicate = submit(executor, () -> sorter.sort(pair(), 1));
                duplicate.awaitWaiting();
                assertEquals(1, calls.get());
                finish.countDown();
                assertSame(failure, assertThrows(ExecutionException.class, owner::get).getCause());
                assertSame(failure, assertThrows(ExecutionException.class, duplicate::get).getCause());
                assertTrue(sorter.sort(pair(), 1).isEmpty());
                assertEquals(2, calls.get());
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void distinctBoundsClassesAndDomainsAreNotMergedOrSerialized() throws Exception {
        final var first = pair();
        final var otherClass = CyclicTargetPair.of(
                new MulticyclePermutation(), Cycle.of(0, 1, 2));
        final var otherDomain = CircularClassOperations.childClasses(first).getFirst();
        final var entered = new CountDownLatch(4);
        final var finish = new CountDownLatch(1);
        final var sorter = new CoalescingBoundedSorter((canonical, depth) -> {
            entered.countDown();
            await(finish);
            return Optional.empty();
        });
        try (final var executor = Executors.newFixedThreadPool(4)) {
            final var tasks = List.of(
                    submit(executor, () -> sorter.sort(first, 1)),
                    submit(executor, () -> sorter.sort(first, 2)),
                    submit(executor, () -> sorter.sort(otherClass, 1)),
                    submit(executor, () -> sorter.sort(otherDomain, 1)));
            try {
                await(entered);
            } finally {
                finish.countDown();
            }
            for (final var task : tasks) {
                assertTrue(task.get().isEmpty());
            }
        }
    }

    @Test
    void interruptingAWaiterDoesNotCancelOrForgetTheOwner() throws Exception {
        final var entered = new CountDownLatch(1);
        final var finish = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var sorter = new CoalescingBoundedSorter((canonical, depth) -> {
            calls.incrementAndGet();
            entered.countDown();
            await(finish);
            return Optional.empty();
        });
        try (final var executor = Executors.newFixedThreadPool(3)) {
            final var owner = submit(executor, () -> sorter.sort(pair(), 1));
            try {
                await(entered);
                final var interrupted = submit(executor, () -> {
                    final var failure = assertThrows(IllegalStateException.class,
                            () -> sorter.sort(pair(), 1));
                    assertInstanceOf(InterruptedException.class, failure.getCause());
                    return Thread.currentThread().isInterrupted();
                });
                interrupted.awaitWaiting();
                interrupted.thread().get().interrupt();
                assertTrue(interrupted.get());
                final var duplicate = submit(executor, () -> sorter.sort(pair(), 1));
                duplicate.awaitWaiting();
                assertEquals(1, calls.get());
                finish.countDown();
                assertTrue(owner.get().isEmpty());
                assertTrue(duplicate.get().isEmpty());
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void rejectsNegativeBoundsBeforeCallingBackend() {
        final var sorter = new CoalescingBoundedSorter((pair, depth) -> {
            fail("Invalid search reached backend");
            return Optional.empty();
        });
        assertThrows(IllegalArgumentException.class, () -> sorter.sort(pair(), -1));
    }
}

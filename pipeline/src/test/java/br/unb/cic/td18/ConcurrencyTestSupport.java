package br.unb.cic.td18;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class ConcurrencyTestSupport {
    private ConcurrencyTestSupport() {
    }

    static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for test work");
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    static <V> Task<V> submit(final ExecutorService executor, final Callable<V> action) {
        final var thread = new AtomicReference<Thread>();
        final var future = executor.submit(() -> {
            thread.set(Thread.currentThread());
            return action.call();
        });
        return new Task<>(future, thread);
    }

    record Task<V>(Future<V> future, AtomicReference<Thread> thread) {
        V get() throws Exception {
            return future.get(10, TimeUnit.SECONDS);
        }

        void awaitWaiting() {
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                final var current = thread.get();
                if (current != null && current.getState() == Thread.State.WAITING) {
                    return;
                }
                if (future.isDone()) {
                    fail("Worker finished before joining the shared request");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            fail("Worker did not join the shared request");
        }
    }
}

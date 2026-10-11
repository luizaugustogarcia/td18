package br.unb.cic.td18;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** Shares overlapping work only; completed results and failures are not cached. */
final class InFlightTasks<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> active =
            new ConcurrentHashMap<>();

    V run(final K key, final Supplier<V> task) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(task);
        final var result = new CompletableFuture<V>();
        final var existing = active.putIfAbsent(key, result);
        if (existing != null) {
            return await(existing);
        }
        return execute(key, result, task);
    }

    /** Nonblocking claim: an empty result means another caller owns this key. */
    Optional<V> tryRun(final K key, final Supplier<V> task) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(task);
        final var result = new CompletableFuture<V>();
        if (active.putIfAbsent(key, result) != null) {
            return Optional.empty();
        }
        return Optional.of(execute(key, result, task));
    }

    private V execute(final K key, final CompletableFuture<V> result, final Supplier<V> task) {
        try {
            final var value = Objects.requireNonNull(task.get(), "Task result");
            result.complete(value);
            return value;
        } catch (final Throwable failure) {
            result.completeExceptionally(failure);
            throw propagate(failure);
        } finally {
            active.remove(key, result);
        }
    }

    private static <V> V await(final CompletableFuture<V> result) {
        try {
            return result.get();
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for shared work", failure);
        } catch (final ExecutionException failure) {
            throw propagate(failure.getCause());
        }
    }

    private static RuntimeException propagate(final Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new IllegalStateException("Shared work failed", failure);
    }
}

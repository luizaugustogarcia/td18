package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Thread-safe bounded buffer that writes witnesses and classes in batches. */
final class RetainedClassWriter implements AutoCloseable {
    private final ClassStore classes;
    private final WitnessStore witnesses;
    private final int batchSize;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayList<WitnessStore.Entry> pending;
    private RuntimeException failure;

    RetainedClassWriter(
            final ClassStore classes,
            final WitnessStore witnesses,
            final int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Write batch size must be positive");
        }
        this.classes = Objects.requireNonNull(classes);
        this.witnesses = Objects.requireNonNull(witnesses);
        this.batchSize = batchSize;
        pending = new ArrayList<>(batchSize);
    }

    void add(final CyclicTargetPair pair, final List<Cycle> moves) {
        lock.lock();
        try {
            requireHealthy();
            pending.add(new WitnessStore.Entry(pair, moves));
            if (pending.size() >= batchSize) {
                writePending();
            }
        } finally {
            lock.unlock();
        }
    }

    void flush() {
        lock.lock();
        try {
            requireHealthy();
            writePending();
        } finally {
            lock.unlock();
        }
    }

    private void writePending() {
        if (pending.isEmpty()) {
            return;
        }
        final var batch = List.copyOf(pending);
        pending.clear();
        try {
            // A class is visible only after its corresponding witness exists.
            witnesses.putAll(batch);
            classes.putAll(batch.stream().map(WitnessStore.Entry::pair).toList());
        } catch (final RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }

    private void requireHealthy() {
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        flush();
    }
}

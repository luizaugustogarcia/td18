package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateCodec;
import br.unb.cic.tdp.base.CertificateValidator;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Compact write-behind buffer flushed periodically and at durable boundaries. */
final class RetainedClassWriter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(
            RetainedClassWriter.class);

    private final ClassStore classes;
    private final WitnessStore witnesses;
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final ReentrantLock flushLock = new ReentrantLock();
    private final AtomicReference<RuntimeException> failure =
            new AtomicReference<>();
    private final ScheduledExecutorService flusher;
    private ArrayList<EncodedCertificate> pending = new ArrayList<>();

    RetainedClassWriter(
            final ClassStore classes,
            final WitnessStore witnesses,
            final int flushSeconds
    ) {
        if (flushSeconds < 1) {
            throw new IllegalArgumentException(
                    "Write flush period must be positive");
        }
        this.classes = Objects.requireNonNull(classes);
        this.witnesses = Objects.requireNonNull(witnesses);
        flusher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name(
                        "retained-class-writer").factory());
        flusher.scheduleAtFixedRate(
                this::scheduledFlush,
                flushSeconds,
                flushSeconds,
                TimeUnit.SECONDS);
    }

    void add(final CyclicTargetPair pair, final List<Cycle> moves) {
        requireHealthy();
        final var canonical = Objects.requireNonNull(pair)
                .canonicalRepresentative();
        CertificateValidator.validate(canonical, moves, moves.size());
        final var encoded = new EncodedCertificate(
                CertificateCodec.keyFor(canonical),
                CertificateCodec.valueFor(moves));

        bufferLock.lock();
        try {
            requireHealthy();
            pending.add(encoded);
        } finally {
            bufferLock.unlock();
        }
    }

    void flush() {
        flushLock.lock();
        try {
            requireHealthy();
            final List<EncodedCertificate> batch;
            bufferLock.lock();
            try {
                if (pending.isEmpty()) {
                    return;
                }
                batch = pending;
                pending = new ArrayList<>();
            } finally {
                bufferLock.unlock();
            }

            final var startedAt = System.nanoTime();
            try {
                // A class is visible only after its corresponding witness exists.
                witnesses.putEncodedAll(batch);
                classes.putEncodedAll(batch);
            } catch (final RuntimeException exception) {
                failure.compareAndSet(null, exception);
                throw exception;
            }
            final var elapsedMillis = Math.round(
                    (System.nanoTime() - startedAt) / 1.0e6);
            LOG.info("Flushed {} retained classes and witnesses in {} ms",
                    batch.size(), elapsedMillis);
        } finally {
            flushLock.unlock();
        }
    }

    private void scheduledFlush() {
        flush();
    }

    private void requireHealthy() {
        final var exception = failure.get();
        if (exception != null) {
            throw exception;
        }
    }

    @Override
    public void close() {
        flusher.shutdownNow();
        try {
            if (!flusher.awaitTermination(
                    Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(
                        "Retained-class writer did not terminate");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping retained-class writer",
                    exception);
        }
        flush();
    }
}

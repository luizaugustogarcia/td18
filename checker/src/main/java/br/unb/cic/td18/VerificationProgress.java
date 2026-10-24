package br.unb.cic.td18;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Time-based reporting, including phases that do not query certificates. */
final class VerificationProgress implements AutoCloseable {
    private final ScheduledExecutorService reporter;
    private final long started = System.nanoTime();
    private volatile Supplier<String> status = () -> "starting";

    VerificationProgress() {
        final long seconds = Long.parseLong(System.getProperty(
                "td18.checkerStatsPeriodSeconds", "60"));
        if (seconds <= 0) {
            throw new IllegalArgumentException(
                    "td18.checkerStatsPeriodSeconds must be positive");
        }
        reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("checker-progress").factory());
        reporter.scheduleAtFixedRate(this::report, seconds, seconds, TimeUnit.SECONDS);
    }

    void phase(final String name) {
        phase(() -> name);
    }

    void phase(final Supplier<String> currentStatus) {
        status = currentStatus;
        report();
    }

    private synchronized void report() {
        final var runtime = Runtime.getRuntime();
        System.out.printf(Locale.ROOT,
                "%s checker: %s elapsed=%.1fs heapMiB=%d/%d%n",
                Instant.now(), status.get(), (System.nanoTime() - started) / 1e9,
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                runtime.maxMemory() / (1024 * 1024));
        System.out.flush();
    }

    @Override
    public void close() {
        reporter.shutdownNow();
    }
}

package br.unb.cic.td18;

import br.unb.cic.td18.base.AvoidanceSearchMiss;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.ResidualObligation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** CPU reconstruction and verification with bounded queues and disk deduplication. */
final class DiskObstructionVerifier {
    private DiskObstructionVerifier() { }

    static ObstructionAvoidance.AvoidanceResult resolve(
            final Collection<ResidualObligation> residuals,
            final ObstructionAvoidance.PairSearch search,
            final Consumer<AvoidanceSearchMiss> missSink,
            final Path directory,
            final VerificationProgress progress
    ) {
        try {
            Files.createDirectory(directory);
        } catch (final IOException failure) {
            throw new IllegalStateException("Requires fresh checker scratch: " + directory, failure);
        }
        try (final var terminal8 = new CheckerStateSet(directory.resolve("terminal-8"));
             final var terminal9 = new CheckerStateSet(directory.resolve("terminal-9"));
             final var intermediate = new CheckerStateSet(directory.resolve("predecessors-9"));
             final var propagated = new CheckerStateSet(directory.resolve("prefix-misses-9"));
             final var roots = new CheckerStateSet(directory.resolve("roots-10"))) {
            progress.phase("avoidance: loading regenerated terminal obstructions into " + directory);
            for (final var residual : residuals) {
                ObstructionAvoidance.validateTerminalResidual(residual);
                final var state = new ObstructionAvoidance.SearchState(
                        residual.maximumMoves(), residual.pair());
                switch (residual.maximumMoves()) {
                    case 8 -> terminal8.add(state);
                    case 9 -> terminal9.add(state);
                    case 10 -> roots.add(state);
                    default -> throw new IllegalStateException("Invalid residual budget");
                }
            }
            terminal8.seal();
            terminal9.seal();
            long applications = reconstruct(terminal8, intermediate, 8, progress);
            progress.phase("avoidance: counting intermediate classes");
            final long intermediateCount = intermediate.seal().count();
            System.out.printf("avoidance wave 8 reconstructed: applications=%d classes=%d%n",
                    applications, intermediateCount);
            final var screened = screen(intermediate, terminal9, propagated,
                    search, missSink, false, progress);
            propagated.seal();
            applications = Math.addExact(applications,
                    reconstruct(terminal9, roots, 9, progress));
            applications = Math.addExact(applications,
                    reconstruct(propagated, roots, 9, progress));
            progress.phase("avoidance: counting root classes");
            final long rootCount = roots.seal().count();
            System.out.printf("avoidance roots reconstructed: applications=%d classes=%d%n",
                    applications, rootCount);
            final var checkedRoots = screen(roots, null, null,
                    search, missSink, true, progress);
            return new ObstructionAvoidance.AvoidanceResult(residuals.size(),
                    applications, screened.processed(), screened.residuals(),
                    checkedRoots.processed(), screened.trivial() + checkedRoots.trivial(),
                    checkedRoots.certificates(), checkedRoots.residuals(),
                    checkedRoots.firstUnresolved());
        }
    }

    private static long reconstruct(final CheckerStateSet inputs,
                                    final CheckerStateSet output,
                                    final int budget,
                                    final VerificationProgress progress) {
        final long expected = inputs.seal().count();
        final var completed = new LongAdder();
        final var applications = new LongAdder();
        final long started = System.nanoTime();
        progress.phase(() -> String.format(Locale.ROOT,
                "avoidance reconstruct budget=%d inputs=%d/%d applications=%d rate=%.1f/s",
                budget, completed.sum(), expected, applications.sum(),
                completed.sum() / Math.max(1e-9, (System.nanoTime() - started) / 1e9)));
        parallel(inputs, state -> {
            if (state.maximumMoves() != budget) {
                throw new IllegalStateException("Reconstruction input budget mismatch");
            }
            applications.add(ObstructionAvoidance.reconstructParentsInto(state, output::add));
            completed.increment();
        });
        if (completed.sum() != expected) {
            throw new IllegalStateException("Missing reconstruction inputs");
        }
        return applications.sum();
    }

    private record ScreenResult(long processed, long trivial, long certificates,
                                long residuals,
                                CyclicTargetPair firstUnresolved) { }

    private static ScreenResult screen(final CheckerStateSet states,
                                       final CheckerStateSet excluded,
                                       final CheckerStateSet propagated,
                                       final ObstructionAvoidance.PairSearch search,
                                       final Consumer<AvoidanceSearchMiss> missSink,
                                       final boolean fullDomain,
                                       final VerificationProgress progress) {
        final long expected = states.seal().count();
        final var visited = new LongAdder();
        final var processed = new LongAdder();
        final var trivial = new LongAdder();
        final var certified = new LongAdder();
        final var residual = new LongAdder();
        final var firstUnresolved = new AtomicReference<CyclicTargetPair>();
        final long started = System.nanoTime();
        progress.phase(() -> String.format(Locale.ROOT,
                "avoidance %s visited=%d/%d checked=%d trivial=%d witnesses=%d %s=%d rate=%.1f/s",
                fullDomain ? "roots" : "intermediate", visited.sum(), expected,
                processed.sum(), trivial.sum(), certified.sum(),
                fullDomain ? "unresolved" : "propagatedMisses", residual.sum(),
                processed.sum() / Math.max(1e-9, (System.nanoTime() - started) / 1e9)));
        parallel(states, state -> {
            if (excluded != null && excluded.contains(state)) {
                visited.increment();
                return;
            }
            if (fullDomain ? state.maximumMoves() != 10 || state.pair().getBeta().size() != 19
                    : state.maximumMoves() != 9 || state.pair().getBeta().size() >= 19) {
                throw new IllegalStateException("Avoidance search domain/budget mismatch");
            }
            final int norm = state.pair().getOmega().stream()
                    .mapToInt(cycle -> cycle.size() / 2).sum();
            final var result = search.search(state.pair(), state.maximumMoves() / (2f * norm),
                    state.maximumMoves(), fullDomain);
            switch (result.disposition()) {
                case TRIVIALLY_SETTLED -> trivial.increment();
                case CERTIFICATE -> {
                    if (!fullDomain) {
                        throw new IllegalStateException("Intermediate closure requires a CPU prefix");
                    }
                    certified.increment();
                }
                case RESIDUAL -> {
                    residual.increment();
                    if (fullDomain) {
                        missSink.accept(new AvoidanceSearchMiss(state.maximumMoves(), state.pair()));
                        firstUnresolved.compareAndSet(null, state.pair());
                    } else {
                        propagated.add(state);
                    }
                }
            }
            processed.increment();
            visited.increment();
        });
        if (visited.sum() != expected) {
            throw new IllegalStateException("Missing avoidance search inputs");
        }
        System.out.printf("avoidance %s verified: checked=%d trivial=%d witnesses=%d misses=%d%n",
                fullDomain ? "roots" : "intermediate", processed.sum(), trivial.sum(),
                certified.sum(), residual.sum());
        return new ScreenResult(processed.sum(), trivial.sum(), certified.sum(),
                residual.sum(), firstUnresolved.get());
    }

    private static void parallel(final CheckerStateSet states,
                                 final Consumer<ObstructionAvoidance.SearchState> action) {
        final int workers = Integer.parseInt(System.getProperty("td18.checkerWorkers",
                Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors()))));
        if (workers < 1) {
            throw new IllegalArgumentException("td18.checkerWorkers must be positive");
        }
        try (final var executor = Executors.newFixedThreadPool(workers)) {
            final var completions = new ExecutorCompletionService<Void>(executor);
            final int[] pending = {0};
            try {
                states.forEachBatch(batch -> {
                    if (pending[0] >= 2 * workers) {
                        await(completions);
                        pending[0]--;
                    }
                    completions.submit(() -> {
                        for (final var state : batch) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IllegalStateException("Interrupted checker worker");
                            }
                            action.accept(state);
                        }
                        return null;
                    });
                    pending[0]++;
                });
                while (pending[0] > 0) {
                    await(completions);
                    pending[0]--;
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void await(final ExecutorCompletionService<Void> completions) {
        try {
            completions.take().get();
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted checker", failure);
        } catch (final ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("CPU verification failed", failure.getCause());
        }
    }
}

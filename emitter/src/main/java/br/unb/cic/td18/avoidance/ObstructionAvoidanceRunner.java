package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.base.GpuDeviceLayout;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.AvoidancePhaseManifest;
import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.emission.OrientedNineteenCycleEmitter;
import br.unb.cic.td18.emission.ExceptionalNineCycleEmitter;
import br.unb.cic.td18.emission.ExceptionalFifteenCycleEmitter;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.search.SortingSearch;
import br.unb.cic.td18.search.WitnessSearch;
import br.unb.cic.td18.table.Table2;
import br.unb.cic.td18.table.TableEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/** Resumes avoidance from persisted misses and regenerated restricted root-lifting seeds. */
@Slf4j
public final class ObstructionAvoidanceRunner {
    private static final byte[] OBSTRUCTIONS_COLUMN_FAMILY =
            "obstructions".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERMEDIATE_WITNESSES_COLUMN_FAMILY =
            "avoidance-intermediate-witnesses"
                    .getBytes(StandardCharsets.UTF_8);
    private static final int ROCKS_SCAN_BATCH_SIZE = 1_024;

    static {
        RocksDB.loadLibrary();
    }

    private ObstructionAvoidanceRunner() {
    }

    public static void main(final String[] arguments) throws IOException {
        final var root = ProjectPaths.root(Path.of(""));
        final var options = Options.parse(arguments, root);
        final boolean trustWaveManifest = Boolean.parseBoolean(
                System.getProperty(
                        "td18.avoidanceTrustWaveManifest", "false"));
        if (options.help()) {
            printUsage();
            return;
        }

        final var entries = Table2.read(options.table());

        final var residuals = loadResiduals(options.database(), entries);
        if (options.validateOnly()) {
            log.info("Persisted terminal-obstruction validation complete: "
                            + "residuals={}, database={}",
                    residuals.size(), options.database().toAbsolutePath());
            DirectTwistedBraceletGenerator.shutdownExecutor();
            return;
        }

        final long flushSeconds = Long.parseLong(System.getProperty(
                "td18.certificateFlushSeconds", "5"));
        final var gpuInitialized = new AtomicBoolean();
        try (final var store = new AvoidancePreimageStore(options.workDatabase());
             final var writer = new AsyncCertificateWriter(
                     options.database(), flushSeconds)) {
            WitnessSearch.installWriter(writer);
            final var sources = initializeSources(store, residuals);
            final long terminalSources = sources.values().stream().mapToLong(List::size).sum();
            log.info("Avoidance terminal source persisted: uniqueClasses={}, "
                            + "workDatabase={}",
                    terminalSources,
                    options.workDatabase().toAbsolutePath());

            long reconstructionApplications = 0;
            long intermediatePrefixObligations = 0;
            long propagatedPrefixMisses = 0;
            long rootSearchObligations = 0;
            long trivialClosures = 0;
            long certificates = 0;
            long unresolved = 0;
            long checksum = 0;
            for (var maximumMoves = 8;
                 maximumMoves < ReductionTypeEnumerator.MAX_BUDGET;
                 maximumMoves++) {
                final var input = store.hardInventory(maximumMoves);
                final var existing = store.waveManifest(maximumMoves);
                final AvoidancePreimageStore.StateInventory reconstructed;
                if (existing.isPresent()) {
                    final var manifest = existing.orElseThrow();
                    final var actualOutput = trustWaveManifest
                            ? manifest.output()
                            : store.inventory(maximumMoves + 1);
                    if (!manifest.input().equals(input)
                            || (!trustWaveManifest
                            && !manifest.output().equals(actualOutput))) {
                        throw new IllegalStateException(
                                "Persisted avoidance wave inventory mismatch at budget "
                                        + maximumMoves);
                    }
                    reconstructionApplications = Math.addExact(
                            reconstructionApplications,
                            manifest.reconstructionApplications());
                    log.info("Avoidance reconstruction wave {} resumed: "
                                    + "inputs={}, predecessorClasses={}",
                            maximumMoves, input.count(), actualOutput.count());
                    if (trustWaveManifest) {
                        log.warn("Avoidance wave {} output trusted from its "
                                        + "checked manifest without resume-time inventory scan",
                                maximumMoves);
                    }
                    reconstructed = actualOutput;
                } else {
                    store.clearUncommittedWaveOutput(maximumMoves);
                    final var wave = reconstructWave(
                            store, maximumMoves, input.count());
                    final var output = prepareWaveOutput(store, maximumMoves + 1,
                            sources.getOrDefault(maximumMoves + 1, List.of()));
                    final var manifest = new AvoidancePreimageStore.WaveManifest(
                            maximumMoves, input, wave.applications(), output);
                    store.recordWave(manifest);
                    reconstructionApplications = Math.addExact(
                            reconstructionApplications, wave.applications());
                    log.info("Avoidance reconstruction wave {} complete: "
                                    + "inputs={}, reconstructionApplications={}, "
                                    + "uniquePredecessorClasses={}",
                            maximumMoves, input.count(), wave.applications(), output.count());
                    reconstructed = output;
                }

                final int searchBudget = maximumMoves + 1;
                if (searchBudget == ReductionTypeEnumerator.MAX_BUDGET
                        && store.searchManifest(searchBudget)
                        .map(manifest -> manifest.residuals().count() != 0).orElse(true)
                        && reconstructed.count() != 0
                        && !gpuInitialized.get()) {
                    initializeGpu();
                    gpuInitialized.set(true);
                }
                final var search = requireOrRunSearch(
                        store, searchBudget, reconstructed, writer,
                        searchBudget == ReductionTypeEnumerator.MAX_BUDGET,
                        searchBudget < ReductionTypeEnumerator.MAX_BUDGET
                                ? sources.getOrDefault(searchBudget, List.of()) : List.of());
                if (searchBudget == ReductionTypeEnumerator.MAX_BUDGET) {
                    rootSearchObligations = Math.addExact(
                            rootSearchObligations, search.candidates().count());
                    unresolved = Math.addExact(
                            unresolved, search.residuals().count());
                } else {
                    intermediatePrefixObligations = Math.addExact(
                            intermediatePrefixObligations,
                            search.candidates().count());
                    propagatedPrefixMisses = Math.addExact(
                            propagatedPrefixMisses, search.residuals().count());
                }
                trivialClosures = Math.addExact(
                        trivialClosures, search.trivialClosures());
                certificates = Math.addExact(
                        certificates, search.certificates());
                checksum = Math.addExact(checksum, search.checksum());
            }

            ExceptionalNineCycleEmitter.ensure(writer, (pair, rate, budget) -> {
                if (!gpuInitialized.get()) {
                    initializeGpu();
                    gpuInitialized.set(true);
                }
                return WitnessSearch.searchCompleteWitness(pair, rate, budget);
            });
            ExceptionalFifteenCycleEmitter.ensure(writer, (pair, rate, budget) -> {
                if (!gpuInitialized.get()) {
                    initializeGpu();
                    gpuInitialized.set(true);
                }
                return WitnessSearch.searchCompleteWitness(pair, rate, budget);
            });
            OrientedNineteenCycleEmitter.ensure(writer, (pair, rate, budget) -> {
                if (!gpuInitialized.get()) {
                    initializeGpu();
                    gpuInitialized.set(true);
                }
                return SortingSearch.searchForSorting(pair, rate, budget);
            });
            writer.flush();
            log.info("Required oriented 19-cycle witnesses are present: 2");
            final var manifest = new AvoidancePhaseManifest(1, Table2.EXPECTED_ENTRIES,
                    terminalSources, reconstructionApplications,
                    intermediatePrefixObligations, propagatedPrefixMisses,
                    rootSearchObligations, trivialClosures, certificates,
                    unresolved, checksum, unresolved == 0);
            writer.enqueueAvoidanceManifest(manifest);
            writer.flush();
            log.info("Avoidance propagation complete: terminalResiduals={}, "
                            + "intermediatePrefixChecks={}, propagatedPrefixMisses={}, "
                            + "rootSearches={}, unresolved={}, "
                            + "reconstructionApplications={}",
                    terminalSources, intermediatePrefixObligations,
                    propagatedPrefixMisses, rootSearchObligations, unresolved,
                    reconstructionApplications);
            if (unresolved != 0) {
                throw new IllegalStateException(
                        "Unresolved full-domain avoidance obligations remain: "
                                + unresolved);
            }
        } finally {
            if (gpuInitialized.get()) {
                GPUSortingSearch.shutdownStatsReporter();
            }
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    /** Keeps budget-nine sources out of prefix searches and budget-ten sources at roots. */
    static Map<Integer, List<CyclicTargetPair>> initializeSources(
            final AvoidancePreimageStore store, final List<ResidualObligation> residuals) {
        // Successful full-domain witnesses delete their obstruction markers.
        // Restore the original exact source before initializing any work frontier.
        final var original = store.requireOrRecordTerminalSources(residuals);
        final var sources = new java.util.TreeMap<Integer, List<CyclicTargetPair>>();
        for (var budget = 8; budget <= 10; budget++) {
            final int selected = budget;
            final var pairs = original.stream().filter(state -> state.maximumMoves() == selected)
                    .map(ResidualObligation::pair).toList();
            sources.put(budget, pairs);
            if (budget < 10) store.putHardPairs(budget, pairs);
            if (budget == 8) store.putPairs(budget, pairs);
        }
        return Map.copyOf(sources);
    }

    /** Called before committing a reconstructed wave, and again after an interrupted wave. */
    static AvoidancePreimageStore.StateInventory prepareWaveOutput(
            final AvoidancePreimageStore store, final int budget,
            final List<CyclicTargetPair> directSources) {
        if (budget == 9) {
            store.removeCandidates(budget, directSources);
        } else if (budget == 10) {
            store.putPairs(budget, directSources);
        } else {
            throw new IllegalArgumentException("Avoidance output budget must be nine or ten");
        }
        return store.inventory(budget);
    }

    private static void initializeGpu() {
        final var devices = GpuDeviceLayout.parseDevices(
                System.getProperty("td18.gpuDevices", "0"));
        final int slots = Integer.parseInt(
                System.getProperty("td18.gpuSlots", "7"));
        final long budgetMiB = Long.parseLong(
                System.getProperty("td18.gpuBudgetMB", "1024"));
        GPUSortingSearch.init(devices, slots,
                Math.multiplyExact(budgetMiB, 1024L * 1024L));
    }

    static WaveResult reconstructWave(
            final AvoidancePreimageStore store,
            final int maximumMoves,
            final long expectedInputs
    ) {
        final int availableProcessors = Math.max(
                1, Runtime.getRuntime().availableProcessors());
        final int parallelism = (int) Math.min(availableProcessors,
                Math.max(1L, expectedInputs));
        log.info("Avoidance reconstruction wave {} starting: inputs={}, "
                        + "parallelism={}, heapUsedMiB={}",
                maximumMoves, expectedInputs, parallelism, usedHeapMiB());

        final long scanned;
        final WaveResult result;
        try (final var processor = new WaveProcessor(
                store, maximumMoves, expectedInputs, parallelism)) {
            scanned = store.scanHardBatches(maximumMoves, ROCKS_SCAN_BATCH_SIZE,
                    processor::submitAll);
            result = processor.finish();
        }
        if (scanned != expectedInputs || result.processed() != expectedInputs) {
            throw new IllegalStateException(
                    "Avoidance wave input changed during its snapshot");
        }
        return result;
    }

    record WaveResult(long processed, long applications, int parallelism) {
        WaveResult {
            if (processed < 0 || applications < 0 || parallelism <= 0) {
                throw new IllegalArgumentException(
                        "Inconsistent avoidance wave result");
            }
        }
    }

    private static AvoidancePreimageStore.SearchManifest requireOrRunSearch(
            final AvoidancePreimageStore store,
            final int maximumMoves,
            final AvoidancePreimageStore.StateInventory candidates,
            final AsyncCertificateWriter writer,
            final boolean fullDomain,
            final List<CyclicTargetPair> directSeeds
    ) {
        final var existing = store.searchManifest(maximumMoves);
        if (existing.isPresent()) {
            final var manifest = existing.orElseThrow();
            final var actualResiduals = store.hardInventoryExcluding(maximumMoves, directSeeds);
            if (!manifest.candidates().equals(candidates)
                    || !manifest.residuals().equals(actualResiduals)) {
                throw new IllegalStateException(
                        "Persisted avoidance search inventory mismatch at budget "
                                + maximumMoves);
            }
            log.info("Avoidance search {} resumed: candidates={}, "
                            + "triviallySettled={}, certificates={}, residuals={}",
                    maximumMoves, candidates.count(),
                    manifest.trivialClosures(), manifest.certificates(),
                    manifest.residuals().count());
            if (fullDomain && actualResiduals.count() != 0) {
                return retryUnresolvedRoots(store, manifest, writer::enqueueAvoidanceMiss,
                        ObstructionAvoidanceRunner::search, writer::flush);
            }
            return manifest;
        }

        store.resetHard(maximumMoves, directSeeds);
        final SearchWaveResult result = searchWave(store, maximumMoves,
                candidates.count(), fullDomain, writer::enqueueAvoidanceMiss);
        writer.flush();
        final var residuals = store.hardInventoryExcluding(maximumMoves, directSeeds);
        if (result.residuals() != residuals.count()) {
            throw new IllegalStateException(
                    "Avoidance search residual inventory mismatch at budget "
                            + maximumMoves);
        }
        final var manifest = new AvoidancePreimageStore.SearchManifest(
                maximumMoves, candidates, result.trivialClosures(),
                result.certificates(), residuals, result.checksum());
        store.recordSearch(manifest);
        log.info("Avoidance search {} complete: candidates={}, "
                        + "triviallySettled={}, certificates={}, residuals={}, "
                        + "completed=true",
                maximumMoves, candidates.count(), result.trivialClosures(),
                result.certificates(), residuals.count());
        return manifest;
    }

    /** Retry only failed roots; an interrupted retry falls back to a full candidate replay. */
    static AvoidancePreimageStore.SearchManifest retryUnresolvedRoots(
            final AvoidancePreimageStore store,
            final AvoidancePreimageStore.SearchManifest previous,
            final Consumer<AvoidanceSearchMiss> missSink,
            final PairSearch search, final Runnable flushCertificates) {
        if (previous.maximumMoves() != 10
                || !previous.candidates().equals(store.inventory(10))
                || !previous.residuals().equals(store.hardInventory(10))) {
            throw new IllegalStateException("Invalid root retry inventory");
        }
        // Persist this invalidation before retiring any hard keys. A crash now
        // cannot leave a completed manifest referring to a partly retried set.
        store.clearSearchManifest(10);
        final var result = searchWave(store, 10, previous.residuals().count(), true,
                missSink, search, true);
        flushCertificates.run();
        final var remaining = store.hardInventory(10);
        if (remaining.count() != result.residuals()) {
            throw new IllegalStateException("Incomplete root retry");
        }
        final var manifest = new AvoidancePreimageStore.SearchManifest(10,
                previous.candidates(), previous.trivialClosures() + result.trivialClosures(),
                previous.certificates() + result.certificates(), remaining, previous.checksum());
        store.recordSearch(manifest);
        return manifest;
    }

    static SearchWaveResult searchWave(
            final AvoidancePreimageStore store,
            final int maximumMoves,
            final long expectedInputs,
            final boolean fullDomain,
            final Consumer<AvoidanceSearchMiss> missSink
    ) {
        return searchWave(store, maximumMoves, expectedInputs, fullDomain,
                missSink, ObstructionAvoidanceRunner::search);
    }

    static SearchWaveResult searchWave(
            final AvoidancePreimageStore store,
            final int maximumMoves,
            final long expectedInputs,
            final boolean fullDomain,
            final Consumer<AvoidanceSearchMiss> missSink,
            final PairSearch search
    ) {
        return searchWave(store, maximumMoves, expectedInputs, fullDomain, missSink, search, false);
    }

    private static SearchWaveResult searchWave(
            final AvoidancePreimageStore store, final int maximumMoves,
            final long expectedInputs, final boolean fullDomain,
            final Consumer<AvoidanceSearchMiss> missSink, final PairSearch search,
            final boolean retryHardOnly
    ) {
        if (fullDomain
                != (maximumMoves == ReductionTypeEnumerator.MAX_BUDGET)) {
            throw new IllegalArgumentException(
                    "Avoidance search budget does not match its stage");
        }
        final int availableProcessors = Math.max(
                1, Runtime.getRuntime().availableProcessors());
        final int parallelism = (int) Math.min(availableProcessors,
                Math.max(1L, expectedInputs));
        log.info("Avoidance search {} starting: candidates={}, parallelism={}, "
                        + "fullDomain={}, heapUsedMiB={}",
                maximumMoves, expectedInputs, parallelism, fullDomain,
                usedHeapMiB());

        final long scanned;
        final SearchWaveResult result;
        try (final var processor = new SearchProcessor(store, maximumMoves,
                expectedInputs, parallelism, fullDomain, missSink, search, retryHardOnly)) {
            scanned = retryHardOnly
                    ? store.scanHardBatches(maximumMoves, ROCKS_SCAN_BATCH_SIZE, processor::submitAll)
                    : store.scanBatches(maximumMoves, ROCKS_SCAN_BATCH_SIZE, processor::submitAll);
            result = processor.finish();
        }
        if (scanned != expectedInputs || result.processed() != expectedInputs) {
            throw new IllegalStateException(
                    "Avoidance search input changed during its snapshot");
        }
        return result;
    }

    private static WitnessSearch.SearchResult search(
            final CyclicTargetPair pair,
            final float minimumRate,
            final int maximumMoves,
            final boolean fullDomain
    ) {
        return fullDomain
                ? SortingSearch.searchForSorting(
                pair, minimumRate, maximumMoves)
                : SortingSearch.screenAvoidanceIntermediate(
                pair, maximumMoves);
    }

    @FunctionalInterface
    interface PairSearch {
        WitnessSearch.SearchResult search(
                CyclicTargetPair pair, float minimumRate, int maximumMoves,
                boolean fullDomain);
    }

    record SearchWaveResult(long processed, long trivialClosures,
                            long certificates, long residuals,
                            long checksum, int parallelism) {
        SearchWaveResult {
            if (processed < 0 || trivialClosures < 0 || certificates < 0
                    || residuals < 0 || checksum < 0 || parallelism <= 0
                    || trivialClosures + certificates + residuals != processed) {
                throw new IllegalArgumentException(
                        "Inconsistent avoidance search-wave result");
            }
        }
    }

    private static final class SearchProcessor implements AutoCloseable {
        private static final WorkItem STOP = new WorkItem(null);

        private final AvoidancePreimageStore store;
        private final int maximumMoves;
        private final long expectedInputs;
        private final int parallelism;
        private final boolean fullDomain;
        private final Consumer<AvoidanceSearchMiss> missSink;
        private final PairSearch search;
        private final boolean retireResolvedHard;
        private final LinkedBlockingQueue<WorkItem> queue;
        private final ExecutorService executor;
        private final ScheduledExecutorService statsReporter;
        private final List<Future<?>> workers;
        private final LongAdder trivialClosures = new LongAdder();
        private final LongAdder certificates = new LongAdder();
        private final LongAdder residuals = new LongAdder();
        private final LongAdder checksum = new LongAdder();
        private final AtomicLong processed = new AtomicLong();
        private final AtomicReference<Throwable> failure =
                new AtomicReference<>();
        private long lastReportNanos = System.nanoTime();
        private long lastReportedProcessed;
        private volatile boolean finished;

        private SearchProcessor(
                final AvoidancePreimageStore store,
                final int maximumMoves,
                final long expectedInputs,
                final int parallelism,
                final boolean fullDomain,
                final Consumer<AvoidanceSearchMiss> missSink,
                final PairSearch search,
                final boolean retireResolvedHard
        ) {
            this.store = Objects.requireNonNull(store);
            this.maximumMoves = maximumMoves;
            this.expectedInputs = expectedInputs;
            this.parallelism = parallelism;
            this.fullDomain = fullDomain;
            this.missSink = Objects.requireNonNull(missSink);
            this.search = Objects.requireNonNull(search);
            this.retireResolvedHard = retireResolvedHard;
            final var statsPeriod = System.getProperty(
                    "td18.gpuStatsPeriodSeconds", "").trim();
            final long statsPeriodSeconds = statsPeriod.isEmpty()
                    ? 60 : Long.parseLong(statsPeriod);
            if (statsPeriodSeconds < 0) {
                throw new IllegalArgumentException(
                        "td18.gpuStatsPeriodSeconds must not be negative");
            }
            queue = new LinkedBlockingQueue<>(Math.max(
                    ROCKS_SCAN_BATCH_SIZE, parallelism * 64));
            executor = Executors.newFixedThreadPool(parallelism,
                    Thread.ofPlatform()
                            .name("td18-avoidance-search-", 0).factory());
            statsReporter = statsPeriodSeconds > 0
                    ? Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true)
                            .name("td18-avoidance-search-stats-", 0).factory())
                    : null;
            workers = new ArrayList<>(parallelism);
            for (var worker = 0; worker < parallelism; worker++) {
                workers.add(executor.submit(this::runWorker));
            }
            if (statsReporter != null) {
                statsReporter.scheduleAtFixedRate(
                        () -> logProgress(false), statsPeriodSeconds,
                        statsPeriodSeconds, TimeUnit.SECONDS);
            }
        }

        private void submitAll(final List<CyclicTargetPair> pairs) {
            for (final var pair : pairs) {
                enqueue(new WorkItem(Objects.requireNonNull(pair)));
            }
        }

        private void enqueue(final WorkItem item) {
            try {
                while (true) {
                    throwIfFailed();
                    if (queue.offer(item, 1, TimeUnit.SECONDS)) {
                        return;
                    }
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while queueing avoidance search", interruption);
            }
        }

        private void runWorker() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    final var item = queue.take();
                    if (item == STOP) {
                        return;
                    }
                    final var pair = item.pair();
                    if (fullDomain
                            != (pair.getBeta().size()
                            == ReductionTypeEnumerator.ROOT_SYMBOLS)) {
                        throw new IllegalStateException(
                                "Avoidance search domain does not match its stage");
                    }
                    final int norm = pair.getOmega().stream()
                            .mapToInt(cycle -> cycle.size() / 2).sum();
                    final float minimumRate = maximumMoves
                            / (2 * (float) norm);
                    final var result = search.search(
                            pair, minimumRate, maximumMoves, fullDomain);
                    if (retireResolvedHard
                            && result.disposition() != WitnessSearch.Disposition.RESIDUAL) {
                        store.removeHardPairs(maximumMoves, List.of(pair));
                    }
                    checksum.add(fullDomain
                            ? CertificateCodec
                            .classKeyChecksumContribution(pair)
                            : CertificateCodec
                            .intermediateClassKeyChecksumContribution(
                            pair, maximumMoves));
                    switch (result.disposition()) {
                        case TRIVIALLY_SETTLED -> trivialClosures.increment();
                        case CERTIFICATE -> {
                            if (!fullDomain) {
                                throw new IllegalStateException(
                                        "Intermediate avoidance screening must not run CUDA");
                            }
                            certificates.increment();
                        }
                        case RESIDUAL -> {
                            store.putHardPairs(maximumMoves, List.of(pair));
                            if (fullDomain) {
                                missSink.accept(new AvoidanceSearchMiss(
                                        maximumMoves, pair));
                            }
                            residuals.increment();
                        }
                    }
                    processed.incrementAndGet();
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                if (!finished) {
                    failure.compareAndSet(null, new IllegalStateException(
                            "Avoidance search worker was interrupted",
                            interruption));
                }
            } catch (final RuntimeException | Error throwable) {
                failure.compareAndSet(null, throwable);
                throw throwable;
            }
        }

        private synchronized void logProgress(final boolean finalReport) {
            if (finished && !finalReport) {
                return;
            }
            final long now = System.nanoTime();
            final double elapsedSeconds = Math.max(1L, now - lastReportNanos)
                    / 1_000_000_000.0;
            final long completed = processed.get();
            final double throughput = (completed - lastReportedProcessed)
                    / elapsedSeconds;
            log.info("Avoidance search {} stats (last {}s): processed={}/{}, "
                            + "throughput={}/s, trivial={}, certificates={}, "
                            + "residuals={}, heapUsedMiB={}",
                    maximumMoves, String.format(Locale.ROOT, "%.3f", elapsedSeconds),
                    completed, expectedInputs,
                    String.format(Locale.ROOT, "%.3f", throughput),
                    trivialClosures.sum(), certificates.sum(), residuals.sum(),
                    usedHeapMiB());
            lastReportNanos = now;
            lastReportedProcessed = completed;
        }

        private SearchWaveResult finish() {
            if (finished) {
                throw new IllegalStateException(
                        "Avoidance search processor is already finished");
            }
            for (var worker = 0; worker < parallelism; worker++) {
                enqueue(STOP);
            }
            awaitWorkers();
            throwIfFailed();
            finished = true;
            if (statsReporter != null) {
                statsReporter.shutdownNow();
            }
            executor.shutdown();
            logProgress(true);
            return new SearchWaveResult(processed.get(),
                    trivialClosures.sum(), certificates.sum(), residuals.sum(),
                    checksum.sum(), parallelism);
        }

        private void awaitWorkers() {
            try {
                for (final var worker : workers) {
                    worker.get();
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting avoidance search",
                        interruption);
            } catch (final ExecutionException execution) {
                failure.compareAndSet(null, execution.getCause());
                throwIfFailed();
            }
        }

        private void throwIfFailed() {
            final var throwable = failure.get();
            if (throwable instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            if (throwable != null) {
                throw new IllegalStateException(
                        "Avoidance search worker failed", throwable);
            }
        }

        @Override
        public void close() {
            if (statsReporter != null) {
                statsReporter.shutdownNow();
            }
            if (!finished) {
                workers.forEach(worker -> worker.cancel(true));
                executor.shutdownNow();
            }
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (final InterruptedException interruption) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class WaveProcessor implements AutoCloseable {
        private static final WorkItem STOP = new WorkItem(null);

        private final AvoidancePreimageStore store;
        private final int maximumMoves;
        private final long expectedInputs;
        private final int parallelism;
        private final LinkedBlockingQueue<WorkItem> queue;
        private final ExecutorService executor;
        private final List<Future<?>> workers;
        private final LongAdder applications = new LongAdder();
        private final AtomicLong processed = new AtomicLong();
        private final AtomicReference<Throwable> failure =
                new AtomicReference<>();
        private volatile boolean finished;

        private WaveProcessor(
                final AvoidancePreimageStore store,
                final int maximumMoves,
                final long expectedInputs,
                final int parallelism
        ) {
            this.store = Objects.requireNonNull(store);
            this.maximumMoves = maximumMoves;
            this.expectedInputs = expectedInputs;
            this.parallelism = parallelism;
            queue = new LinkedBlockingQueue<>(Math.max(
                    ROCKS_SCAN_BATCH_SIZE, parallelism * 64));
            executor = Executors.newFixedThreadPool(parallelism,
                    Thread.ofPlatform()
                            .name("td18-avoidance-wave-", 0).factory());
            workers = new ArrayList<>(parallelism);
            for (var worker = 0; worker < parallelism; worker++) {
                workers.add(executor.submit(this::runWorker));
            }
        }

        private void submitAll(final List<CyclicTargetPair> pairs) {
            for (final var pair : pairs) {
                enqueue(new WorkItem(Objects.requireNonNull(pair)));
            }
        }

        private void enqueue(final WorkItem item) {
            try {
                while (true) {
                    throwIfFailed();
                    if (queue.offer(item, 1, TimeUnit.SECONDS)) {
                        return;
                    }
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while queueing avoidance reconstruction",
                        interruption);
            }
        }

        private void runWorker() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    final var item = queue.take();
                    if (item == STOP) {
                        return;
                    }
                    final var batch = ObstructionAvoidance.reconstructParents(
                            item.pair(), maximumMoves);
                    store.putPairs(maximumMoves + 1, batch.parents());
                    applications.add(batch.applications());
                    final long completed = processed.incrementAndGet();
                    if (completed % 10_000 == 0
                            || completed == expectedInputs) {
                        log.info("Avoidance reconstruction wave {} progress: "
                                        + "processed={}/{}, parallelism={}, "
                                        + "heapUsedMiB={}",
                                maximumMoves, completed, expectedInputs,
                                parallelism, usedHeapMiB());
                    }
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                if (!finished) {
                    failure.compareAndSet(null, new IllegalStateException(
                            "Avoidance reconstruction worker was interrupted",
                            interruption));
                }
            } catch (final RuntimeException | Error throwable) {
                failure.compareAndSet(null, throwable);
                throw throwable;
            }
        }

        private WaveResult finish() {
            if (finished) {
                throw new IllegalStateException(
                        "Avoidance wave processor is already finished");
            }
            for (var worker = 0; worker < parallelism; worker++) {
                enqueue(STOP);
            }
            awaitWorkers();
            throwIfFailed();
            finished = true;
            executor.shutdown();
            return new WaveResult(
                    processed.get(), applications.sum(), parallelism);
        }

        private void awaitWorkers() {
            try {
                for (final var worker : workers) {
                    worker.get();
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting avoidance reconstruction",
                        interruption);
            } catch (final ExecutionException execution) {
                failure.compareAndSet(null, execution.getCause());
                throwIfFailed();
            }
        }

        private void throwIfFailed() {
            final var throwable = failure.get();
            if (throwable instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            if (throwable != null) {
                throw new IllegalStateException(
                        "Avoidance reconstruction worker failed", throwable);
            }
        }

        @Override
        public void close() {
            if (!finished) {
                workers.forEach(worker -> worker.cancel(true));
                executor.shutdownNow();
            }
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (final InterruptedException interruption) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private record WorkItem(CyclicTargetPair pair) {
    }

    static List<ResidualObligation> loadResiduals(
            final Path database,
            final List<TableEntry> entries
    ) {
        final var contextsByType =
                new HashMap<String, ResidualRowContext>();
        final var domainsByKeyLength = new HashMap<Integer, List<Integer>>();
        for (final var entry : entries) {
            if (entry.remainingClassObligations() == 0) {
                continue;
            }
            final var context = new ResidualRowContext(
                    entry.number(), entry.type(), entry.nonfixedSymbols(),
                    entry.remainingMoves(), RestrictedOddPair.isObligation(
                            entry.partition(), entry.orientedByPart(), entry.movesUsed()));
            if (contextsByType.put(entry.type(), context) != null) {
                throw new IllegalStateException(
                        "Repeated residual Table 2 type: " + entry.type());
            }
            final int keyLength = keyLength(entry.nonfixedSymbols());
            domainsByKeyLength.computeIfAbsent(
                            keyLength, ignored -> new ArrayList<>())
                    .add(entry.nonfixedSymbols());
        }
        domainsByKeyLength.replaceAll((ignored, domains) ->
                domains.stream().distinct().toList());

        final var keys = readObstructionKeys(database);
        final var counts = new HashMap<Integer, Long>();
        final var residuals = new ArrayList<ResidualObligation>(keys.size());
        var scanned = 0;
        var ignoredRootMisses = 0;
        var ignoredLegacyRestrictedMisses = 0;
        for (final var key : keys) {
            final var domains = domainsByKeyLength.get(key.length);
            ResidualObligation recovered = null;
            RuntimeException lastFailure = null;
            boolean legacyRestrictedMiss = false;
            if (domains != null) {
                for (final int domain : domains) {
                    try {
                        final CyclicTargetPair pair =
                                CertificateCodec.pairForKey(key, domain);
                        final var context = contextsByType.get(
                                ObstructionAvoidance.annotatedTypeOf(pair));
                        if (context == null || context.domainSize() != domain) {
                            continue;
                        }
                        if (!Arrays.equals(key, CertificateCodec.keyFor(pair.canonicalRepresentative()))) {
                            throw new IllegalStateException("Persisted obstruction key is not canonical");
                        }
                        if (context.restricted() && !RestrictedOddPair.contains(pair)) {
                            // The revised route covers these old whole-type misses through
                            // its qualifying child branches, not through this seed family.
                            legacyRestrictedMiss = true;
                            continue;
                        }
                        final var candidate = new ResidualObligation(
                                context.row(), context.maximumMoves(), pair);
                        if (!Arrays.equals(key, candidate.key())) {
                            throw new IllegalStateException(
                                    "Persisted obstruction key is not canonical");
                        }
                        if (recovered != null) {
                            throw new IllegalStateException(
                                    "Persisted obstruction key has ambiguous Table 2 context");
                        }
                        recovered = candidate;
                    } catch (final IllegalArgumentException failure) {
                        lastFailure = failure;
                    }
                }
            }
            if (recovered == null) {
                if (legacyRestrictedMiss) {
                    ignoredLegacyRestrictedMisses++;
                } else if (isAvoidanceRootMiss(key)) {
                    ignoredRootMisses++;
                } else {
                    throw new IllegalStateException(
                            "Cannot reconstruct the Table 2 context of a persisted obstruction",
                            lastFailure);
                }
            } else {
                residuals.add(recovered);
                counts.merge(recovered.row(), 1L, Long::sum);
            }
            scanned++;
            if (scanned % 25_000 == 0 || scanned == keys.size()) {
                log.info("Persisted obstruction loading: scanned={}/{}, "
                                + "terminalResiduals={}, priorRootMisses={}",
                        scanned, keys.size(), residuals.size(),
                        ignoredRootMisses);
            }
        }
        for (final var context : contextsByType.values()) {
            final long actual = counts.getOrDefault(context.row(), 0L);
            if (actual == 0) {
                continue;
            }
            log.info("Recovered Table 2 row {}: type={}, domain={}, "
                            + "budget={}, residuals={}",
                    context.row(), context.type(), context.domainSize(),
                    context.maximumMoves(), actual);
        }
        if (ignoredLegacyRestrictedMisses != 0) {
            log.info("Ignored obsolete whole-type residuals outside the restricted families: {}",
                    ignoredLegacyRestrictedMisses);
        }
        return withRestrictedSeeds(entries, residuals);
    }

    /** Mandatory restricted sources are regenerated even if no residual marker was written. */
    static List<ResidualObligation> withRestrictedSeeds(
            final List<TableEntry> entries, final List<ResidualObligation> stored) {
        final var byClass = new java.util.TreeMap<ExtendedToricClassKey, ResidualObligation>(
                Comparator.comparing(ExtendedToricClassKey::twistedBracelet));
        for (final var residual : stored) {
            ObstructionAvoidance.validateTerminalResidual(residual);
            final var previous = byClass.putIfAbsent(residual.pair().extendedToricClassKey(), residual);
            if (previous != null && (previous.row() != residual.row()
                    || previous.maximumMoves() != residual.maximumMoves())) {
                throw new IllegalStateException("Ambiguous terminal avoidance source");
            }
        }
        for (final var entry : entries) {
            if (!RestrictedOddPair.isObligation(entry.partition(), entry.orientedByPart(),
                    entry.movesUsed())) continue;
            final long expected = RestrictedOddPair.expectedClasses(
                    entry.partition(), entry.orientedByPart());
            if (entry.remainingClassObligations() == null
                    || entry.remainingClassObligations() != expected || entry.remainingMoves() != 9) {
                throw new IllegalStateException("Incorrect restricted Table 2 obligation count/budget");
            }
            final long[] generated = {0};
            DirectTwistedBraceletGenerator.generateRestrictedOddPairRepresentatives(
                    entry.partition(), entry.orientedByPart(), pair -> {
                        if (!RestrictedOddPair.contains(pair)) {
                            throw new IllegalStateException("Invalid restricted avoidance seed");
                        }
                        final var seed = new ResidualObligation(entry.number(), 9, pair);
                        byClass.putIfAbsent(pair.extendedToricClassKey(), seed);
                        generated[0]++;
                    });
            if (generated[0] != expected) {
                throw new IllegalStateException("Incomplete restricted avoidance source generation");
            }
            log.info("Regenerated restricted root-lifting seeds: row={}, classes={}",
                    entry.number(), generated[0]);
        }
        return List.copyOf(byClass.values());
    }

    static boolean isAvoidanceRootMiss(final byte[] key) {
        if (key.length != keyLength(ReductionTypeEnumerator.ROOT_SYMBOLS)) {
            return false;
        }
        try {
            final var pair = CertificateCodec.pairForKey(
                    key, ReductionTypeEnumerator.ROOT_SYMBOLS);
            final var miss = new AvoidanceSearchMiss(
                    ReductionTypeEnumerator.MAX_BUDGET, pair);
            return Arrays.equals(key, miss.key());
        } catch (final IllegalArgumentException failure) {
            return false;
        }
    }

    private static List<byte[]> readObstructionKeys(final Path directory) {
        if (Files.notExists(directory)) return List.of();
        if (Files.isDirectory(directory) && Files.notExists(directory.resolve("CURRENT"))) {
            try (final var files = Files.list(directory)) {
                if (files.findAny().isEmpty()) return List.of();
            } catch (final IOException failure) {
                throw new IllegalStateException("Cannot inspect certificate directory", failure);
            }
        }
        final boolean hasIntermediate =
                requireCompatibleColumnFamilies(directory);
        final var options = new DBOptions().setMaxOpenFiles(-1);
        final var witnessOptions = new ColumnFamilyOptions();
        final var obstructionOptions = new ColumnFamilyOptions();
        final var intermediateOptions = new ColumnFamilyOptions();
        final var descriptors = new ArrayList<ColumnFamilyDescriptor>();
        descriptors.add(new ColumnFamilyDescriptor(
                RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions));
        descriptors.add(new ColumnFamilyDescriptor(
                OBSTRUCTIONS_COLUMN_FAMILY, obstructionOptions));
        if (hasIntermediate) {
            descriptors.add(new ColumnFamilyDescriptor(
                    INTERMEDIATE_WITNESSES_COLUMN_FAMILY,
                    intermediateOptions));
        }
        final int expectedHandles = hasIntermediate ? 3 : 2;
        final var handles =
                new ArrayList<ColumnFamilyHandle>(expectedHandles);
        RocksDB database = null;
        try {
            database = RocksDB.openReadOnly(
                    options, directory.toAbsolutePath().toString(),
                    descriptors, handles);
            if (handles.size() != expectedHandles) {
                throw new IllegalStateException(
                        "TD(18) database did not open every column family");
            }
            final var keys = new ArrayList<byte[]>();
            try (final var readOptions = new ReadOptions()
                    .setVerifyChecksums(true).setFillCache(false);
                 final var iterator = database.newIterator(
                         handles.get(1), readOptions)) {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    if (iterator.value().length != 0) {
                        throw new IllegalStateException(
                                "An obstruction entry must have an empty value");
                    }
                    keys.add(iterator.key());
                    iterator.next();
                }
                iterator.status();
            }
            return keys;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot read certificate database " + directory, failure);
        } finally {
            handles.forEach(ColumnFamilyHandle::close);
            if (database != null) {
                database.close();
            }
            obstructionOptions.close();
            intermediateOptions.close();
            witnessOptions.close();
            options.close();
        }
    }

    private static boolean requireCompatibleColumnFamilies(
            final Path directory
    ) {
        try (final var options = new org.rocksdb.Options()) {
            final var families = RocksDB.listColumnFamilies(
                    options, directory.toAbsolutePath().toString());
            if ((families.size() != 2 && families.size() != 3)
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, OBSTRUCTIONS_COLUMN_FAMILY))
                    || (families.size() == 3
                    && families.stream().noneMatch(name -> Arrays.equals(
                    name, INTERMEDIATE_WITNESSES_COLUMN_FAMILY)))) {
                throw new IllegalStateException(
                        "Unsupported TD(18) column-family schema");
            }
            return families.size() == 3;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException(
                    "Cannot inspect certificate database " + directory, failure);
        }
    }

    private static int keyLength(final int domainSize) {
        return Math.toIntExact(((long) 5 * domainSize + 7) / 8);
    }

    private static long usedHeapMiB() {
        final var runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) >> 20;
    }

    private static void printUsage() {
        System.out.println("ObstructionAvoidanceRunner [--table=path] "
                + "[--database=path] [--work-database=path] "
                + "[--validate-only]");
    }

    private record ResidualRowContext(
            int row, String type, int domainSize, int maximumMoves, boolean restricted
    ) {
    }

    private record Options(
            Path table, Path database, Path workDatabase,
            boolean validateOnly, boolean help
    ) {
        private static Options parse(final String[] arguments, final Path root) {
            var table = root.resolve("docs/td18-reduction-count.tex");
            var database = Path.of(System.getProperty(
                    "td18.certificateDb",
                    root.resolve("certificates").toString()));
            var workDatabase = Path.of(System.getProperty(
                    "td18.avoidanceWorkDb",
                    root.resolve("avoidance-work").toString()));
            var validateOnly = false;
            var help = false;
            for (final var argument : arguments) {
                if (argument.equals("--help")) {
                    help = true;
                } else if (argument.equals("--validate-only")) {
                    validateOnly = true;
                } else if (argument.startsWith("--table=")) {
                    table = Path.of(argument.substring("--table=".length()))
                            .toAbsolutePath();
                } else if (argument.startsWith("--database=")) {
                    database = Path.of(argument.substring(
                            "--database=".length())).toAbsolutePath();
                } else if (argument.startsWith("--work-database=")) {
                    workDatabase = Path.of(argument.substring(
                            "--work-database=".length())).toAbsolutePath();
                } else {
                    throw new IllegalArgumentException(
                            "Unknown argument: " + argument);
                }
            }
            return new Options(
                    table, database, workDatabase, validateOnly, help);
        }
    }
}

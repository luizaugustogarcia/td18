package br.unb.cic.td18;

import br.unb.cic.td18.base.AvoidancePhaseManifest;
import br.unb.cic.td18.base.AvoidanceSearchMiss;
import br.unb.cic.td18.base.AsyncCertificateWriter;
import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CertificateManifestStore;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.base.GpuDeviceLayout;
import br.unb.cic.td18.base.ResidualObligation;
import br.unb.cic.td18.base.SortingSearch;
import br.unb.cic.td18.base.WitnessSearch;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Resumes only the final obstruction-avoidance phase from persisted misses. */
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
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var options = Options.parse(arguments, root);
        if (options.help()) {
            printUsage();
            return;
        }

        final var entries = CountTD18Bracelets.readTableEntries(Files.readString(
                options.table(), StandardCharsets.UTF_8));
        if (entries.size() != CountTD18Bracelets.EXPECTED_ENTRIES) {
            throw new IllegalArgumentException("Expected "
                    + CountTD18Bracelets.EXPECTED_ENTRIES
                    + " Table 2 entries, found " + entries.size());
        }

        final var residuals = loadResiduals(options.database(), entries);
        if (options.validateOnly()) {
            log.info("Persisted terminal-obstruction validation complete: "
                            + "residuals={}, database={}",
                    residuals.size(), options.database().toAbsolutePath());
            return;
        }

        final long flushSeconds = Long.parseLong(System.getProperty(
                "td18.certificateFlushSeconds", "5"));
        var gpuInitialized = false;
        try (final var store = new AvoidancePreimageStore(options.workDatabase());
             final var writer = new AsyncCertificateWriter(
                     options.database(), flushSeconds)) {
            WitnessSearch.installWriter(writer);
            for (var start = 0; start < residuals.size(); start += 10_000) {
                final int end = Math.min(residuals.size(), start + 10_000);
                final var states = residuals.subList(start, end).stream()
                        .map(residual -> new AvoidancePreimageStore.State(
                                residual.maximumMoves(), residual.pair()))
                        .toList();
                store.putStates(states);
                store.putHardStates(states);
            }
            final var source = store.inventory(8);
            final var hardSource = store.hardInventory(8);
            if (source.count() != residuals.size()
                    || !source.equals(hardSource)) {
                throw new IllegalStateException(
                        "Terminal obstruction classes were not unique: recovered="
                                + residuals.size() + ", candidates=" + source.count()
                                + ", hard=" + hardSource.count());
            }
            store.requireOrRecordSource(source);
            log.info("Avoidance terminal source persisted: uniqueClasses={}, "
                            + "checksum={}, workDatabase={}",
                    source.count(), source.checksum(),
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
                if (existing.isPresent()) {
                    final var manifest = existing.orElseThrow();
                    final var actualOutput = store.inventory(maximumMoves + 1);
                    if (!manifest.input().equals(input)
                            || !manifest.output().equals(actualOutput)) {
                        throw new IllegalStateException(
                                "Persisted avoidance wave inventory mismatch at budget "
                                        + maximumMoves);
                    }
                    reconstructionApplications = Math.addExact(
                            reconstructionApplications,
                            manifest.reconstructionApplications());
                    log.info("Avoidance reconstruction wave {} resumed: "
                                    + "inputs={}, predecessorClasses={}, checksum={}",
                            maximumMoves, input.count(), actualOutput.count(),
                            actualOutput.checksum());
                } else {
                    final var wave = reconstructWave(
                            store, maximumMoves, input.count());
                    final var output = store.inventory(maximumMoves + 1);
                    final var manifest = new AvoidancePreimageStore.WaveManifest(
                            maximumMoves, input, wave.applications(), output);
                    store.recordWave(manifest);
                    reconstructionApplications = Math.addExact(
                            reconstructionApplications, wave.applications());
                    log.info("Avoidance reconstruction wave {} complete: "
                                    + "inputs={}, reconstructionApplications={}, "
                                    + "uniquePredecessorClasses={}, checksum={}",
                            maximumMoves, input.count(), wave.applications(),
                            output.count(), output.checksum());
                }

                final int searchBudget = maximumMoves + 1;
                if (searchBudget == ReductionTypeEnumerator.MAX_BUDGET
                        && store.searchManifest(searchBudget).isEmpty()
                        && store.inventory(searchBudget).count() != 0
                        && !gpuInitialized) {
                    initializeGpu();
                    gpuInitialized = true;
                }
                final var search = requireOrRunSearch(
                        store, searchBudget, writer,
                        searchBudget == ReductionTypeEnumerator.MAX_BUDGET);
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

            final var manifest = new AvoidancePhaseManifest(1, 204,
                    residuals.size(), reconstructionApplications,
                    intermediatePrefixObligations, propagatedPrefixMisses,
                    rootSearchObligations, trivialClosures, certificates,
                    unresolved, checksum, unresolved == 0);
            writer.enqueueAvoidanceManifest(manifest);
            writer.flush();
            log.info("Avoidance propagation complete: terminalResiduals={}, "
                            + "intermediatePrefixChecks={}, propagatedPrefixMisses={}, "
                            + "rootSearches={}, unresolved={}, "
                            + "reconstructionApplications={}",
                    residuals.size(), intermediatePrefixObligations,
                    propagatedPrefixMisses, rootSearchObligations, unresolved,
                    reconstructionApplications);
            if (unresolved != 0) {
                throw new IllegalStateException(
                        "Unresolved full-domain avoidance obligations remain: "
                                + unresolved);
            }
        } finally {
            if (gpuInitialized) {
                GPUSortingSearch.shutdownStatsReporter();
            }
        }
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
            final AsyncCertificateWriter writer,
            final boolean fullDomain
    ) {
        final var candidates = store.inventory(maximumMoves);
        final var existing = store.searchManifest(maximumMoves);
        if (existing.isPresent()) {
            final var manifest = existing.orElseThrow();
            final var actualResiduals = store.hardInventory(maximumMoves);
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
            return manifest;
        }

        final SearchWaveResult result = searchWave(store, maximumMoves,
                candidates.count(), fullDomain, writer::enqueueAvoidanceMiss);
        writer.flush();
        final var residuals = store.hardInventory(maximumMoves);
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
                        + "checksum={}",
                maximumMoves, candidates.count(), result.trivialClosures(),
                result.certificates(), residuals.count(), result.checksum());
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
                expectedInputs, parallelism, fullDomain, missSink, search)) {
            scanned = store.scanBatches(maximumMoves, ROCKS_SCAN_BATCH_SIZE,
                    processor::submitAll);
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
        private final LinkedBlockingQueue<WorkItem> queue;
        private final ExecutorService executor;
        private final List<Future<?>> workers;
        private final LongAdder trivialClosures = new LongAdder();
        private final LongAdder certificates = new LongAdder();
        private final LongAdder residuals = new LongAdder();
        private final LongAdder checksum = new LongAdder();
        private final AtomicLong processed = new AtomicLong();
        private final AtomicReference<Throwable> failure =
                new AtomicReference<>();
        private volatile boolean finished;

        private SearchProcessor(
                final AvoidancePreimageStore store,
                final int maximumMoves,
                final long expectedInputs,
                final int parallelism,
                final boolean fullDomain,
                final Consumer<AvoidanceSearchMiss> missSink,
                final PairSearch search
        ) {
            this.store = Objects.requireNonNull(store);
            this.maximumMoves = maximumMoves;
            this.expectedInputs = expectedInputs;
            this.parallelism = parallelism;
            this.fullDomain = fullDomain;
            this.missSink = Objects.requireNonNull(missSink);
            this.search = Objects.requireNonNull(search);
            queue = new LinkedBlockingQueue<>(Math.max(
                    ROCKS_SCAN_BATCH_SIZE, parallelism * 64));
            executor = Executors.newFixedThreadPool(parallelism,
                    Thread.ofPlatform()
                            .name("td18-avoidance-search-", 0).factory());
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
                    final long completed = processed.incrementAndGet();
                    if (completed % 10_000 == 0
                            || completed == expectedInputs) {
                        log.info("Avoidance search {} progress: processed={}/{}, "
                                        + "trivial={}, certificates={}, residuals={}, "
                                        + "heapUsedMiB={}",
                                maximumMoves, completed, expectedInputs,
                                trivialClosures.sum(), certificates.sum(),
                                residuals.sum(), usedHeapMiB());
                    }
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
            executor.shutdown();
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
            final List<CountTD18Bracelets.TableEntry> entries
    ) {
        final var manifests = new CertificateManifestStore(database);
        if (!manifests.avoidanceManifests().isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to replace an existing avoidance manifest");
        }

        final var contextsByType =
                new HashMap<String, ResidualRowContext>();
        final var domainsByKeyLength = new HashMap<Integer, List<Integer>>();
        long expectedTotal = 0;
        for (final var entry : entries) {
            final var manifest = manifests.requireRow(entry.number());
            requireMatchingManifest(entry, manifest);
            if (manifest.residualCount() == 0) {
                continue;
            }
            final var context = new ResidualRowContext(
                    entry.number(), entry.type(), entry.nonfixedSymbols(),
                    entry.remainingMoves(), manifest.residualCount());
            if (contextsByType.put(entry.type(), context) != null) {
                throw new IllegalStateException(
                        "Repeated residual Table 2 type: " + entry.type());
            }
            final int keyLength = keyLength(entry.nonfixedSymbols());
            domainsByKeyLength.computeIfAbsent(
                            keyLength, ignored -> new ArrayList<>())
                    .add(entry.nonfixedSymbols());
            expectedTotal = Math.addExact(
                    expectedTotal, manifest.residualCount());
        }
        domainsByKeyLength.replaceAll((ignored, domains) ->
                domains.stream().distinct().toList());

        final var keys = readObstructionKeys(database);
        final var counts = new long[entries.size()];
        final var residuals = new ArrayList<ResidualObligation>(keys.size());
        var scanned = 0;
        var ignoredRootMisses = 0;
        for (final var key : keys) {
            final var domains = domainsByKeyLength.get(key.length);
            ResidualObligation recovered = null;
            RuntimeException lastFailure = null;
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
                if (isAvoidanceRootMiss(key)) {
                    ignoredRootMisses++;
                } else {
                    throw new IllegalStateException(
                            "Cannot reconstruct the Table 2 context of a persisted obstruction",
                            lastFailure);
                }
            } else {
                residuals.add(recovered);
                counts[recovered.row() - 1]++;
            }
            scanned++;
            if (scanned % 25_000 == 0 || scanned == keys.size()) {
                log.info("Persisted obstruction loading: scanned={}/{}, "
                                + "terminalResiduals={}, priorRootMisses={}",
                        scanned, keys.size(), residuals.size(),
                        ignoredRootMisses);
            }
        }
        if (residuals.size() != expectedTotal) {
            throw new IllegalStateException("Row manifests record "
                    + expectedTotal + " terminal residuals but RocksDB "
                    + "reconstructs " + residuals.size());
        }

        for (final var context : contextsByType.values()) {
            final long actual = counts[context.row() - 1];
            if (actual != context.expectedResiduals()) {
                throw new IllegalStateException("Table 2 row " + context.row()
                        + " records " + context.expectedResiduals()
                        + " residuals but RocksDB reconstructs " + actual);
            }
            log.info("Recovered Table 2 row {}: type={}, domain={}, "
                            + "budget={}, residuals={}",
                    context.row(), context.type(), context.domainSize(),
                    context.maximumMoves(), actual);
        }
        return List.copyOf(residuals);
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

    private static void requireMatchingManifest(
            final CountTD18Bracelets.TableEntry entry,
            final br.unb.cic.td18.base.CertificateRowManifest manifest
    ) {
        if (manifest.row() != entry.number()
                || !manifest.type().equals(entry.type())
                || manifest.maximumMoves() != entry.remainingMoves()
                || manifest.expectedRealizableClasses()
                != entry.recordedRealizableClasses()
                || manifest.expectedRemainingObligations()
                != entry.remainingClassObligations()
                || manifest.generatedClasses()
                != entry.remainingClassObligations()) {
            throw new IllegalStateException(
                    "Certificate manifest disagrees with Table 2 row "
                            + entry.number());
        }
    }

    private static List<byte[]> readObstructionKeys(final Path directory) {
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
            int row, String type, int domainSize, int maximumMoves,
            long expectedResiduals
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

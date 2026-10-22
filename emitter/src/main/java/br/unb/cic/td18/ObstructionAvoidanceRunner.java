package br.unb.cic.td18;

import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CertificateManifestStore;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.ResidualObligation;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Resumes only the final obstruction-avoidance phase from persisted misses. */
@Slf4j
public final class ObstructionAvoidanceRunner {
    private static final byte[] OBSTRUCTIONS_COLUMN_FAMILY =
            "obstructions".getBytes(StandardCharsets.UTF_8);
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

        try (final var store = new AvoidancePreimageStore(
                options.workDatabase())) {
            for (var start = 0; start < residuals.size(); start += 10_000) {
                final int end = Math.min(residuals.size(), start + 10_000);
                store.putStates(residuals.subList(start, end).stream()
                        .map(residual -> new AvoidancePreimageStore.State(
                                residual.maximumMoves(), residual.pair()))
                        .toList());
            }
            final var source = store.inventory(8);
            if (source.count() != residuals.size()) {
                throw new IllegalStateException(
                        "Terminal obstruction classes were not unique: recovered="
                                + residuals.size() + ", unique=" + source.count());
            }
            store.requireOrRecordSource(source);
            log.info("Avoidance terminal source persisted: uniqueClasses={}, "
                            + "checksum={}, workDatabase={}",
                    source.count(), source.checksum(),
                    options.workDatabase().toAbsolutePath());

            long reconstructionApplications = 0;
            for (var maximumMoves = 8;
                 maximumMoves < ReductionTypeEnumerator.MAX_BUDGET;
                 maximumMoves++) {
                final var input = store.inventory(maximumMoves);
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
                    continue;
                }

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

            final var roots = store.inventory(
                    ReductionTypeEnumerator.MAX_BUDGET);
            log.info("Avoidance root preimage reconstruction complete: "
                            + "uniqueClasses={}, checksum={}, "
                            + "reconstructionApplications={}; sortingNotStarted=true",
                    roots.count(), roots.checksum(),
                    reconstructionApplications);
        }
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
            scanned = store.scanBatches(maximumMoves, ROCKS_SCAN_BATCH_SIZE,
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

    private static final class WaveProcessor implements AutoCloseable {
        private static final WorkItem STOP = new WorkItem(null);

        private final AvoidancePreimageStore store;
        private final int maximumMoves;
        private final long expectedInputs;
        private final int parallelism;
        private final ArrayBlockingQueue<WorkItem> queue;
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
            queue = new ArrayBlockingQueue<>(parallelism);
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
            while (true) {
                throwIfFailed();
                try {
                    if (queue.offer(item, 1, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (final InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while submitting an avoidance obstruction",
                            interruption);
                }
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
        if (keys.size() != expectedTotal) {
            throw new IllegalStateException("Row manifests record "
                    + expectedTotal + " terminal residuals but RocksDB contains "
                    + keys.size() + " obstruction keys");
        }

        final var counts = new long[entries.size()];
        final var residuals = new ArrayList<ResidualObligation>(keys.size());
        var decoded = 0;
        for (final var key : keys) {
            final var domains = domainsByKeyLength.get(key.length);
            if (domains == null) {
                throw new IllegalStateException(
                        "Unexpected persisted obstruction key length: "
                                + key.length);
            }
            ResidualObligation recovered = null;
            RuntimeException lastFailure = null;
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
            if (recovered == null) {
                throw new IllegalStateException(
                        "Cannot reconstruct the Table 2 context of a persisted obstruction",
                        lastFailure);
            }
            residuals.add(recovered);
            counts[recovered.row() - 1]++;
            decoded++;
            if (decoded % 25_000 == 0 || decoded == keys.size()) {
                log.info("Persisted obstruction loading: decoded={}/{}",
                        decoded, keys.size());
            }
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
        requireCompatibleColumnFamilies(directory);
        final var options = new DBOptions().setMaxOpenFiles(-1);
        final var witnessOptions = new ColumnFamilyOptions();
        final var obstructionOptions = new ColumnFamilyOptions();
        final var descriptors = List.of(
                new ColumnFamilyDescriptor(
                        RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                new ColumnFamilyDescriptor(
                        OBSTRUCTIONS_COLUMN_FAMILY, obstructionOptions));
        final var handles = new ArrayList<ColumnFamilyHandle>(2);
        RocksDB database = null;
        try {
            database = RocksDB.openReadOnly(
                    options, directory.toAbsolutePath().toString(),
                    descriptors, handles);
            if (handles.size() != 2) {
                throw new IllegalStateException(
                        "TD(18) database did not open both column families");
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
            witnessOptions.close();
            options.close();
        }
    }

    private static void requireCompatibleColumnFamilies(final Path directory) {
        try (final var options = new org.rocksdb.Options()) {
            final var families = RocksDB.listColumnFamilies(
                    options, directory.toAbsolutePath().toString());
            if (families.size() != 2
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(name -> Arrays.equals(
                    name, OBSTRUCTIONS_COLUMN_FAMILY))) {
                throw new IllegalStateException(
                        "Unsupported TD(18) column-family schema");
            }
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

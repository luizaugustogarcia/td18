package br.unb.cic.td18.emission;

import br.unb.cic.td18.avoidance.DiskStateSet;
import br.unb.cic.td18.avoidance.JointAvoidance;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.ReadOnlyWitnessSource;
import br.unb.cic.td18.joint.JointInventory;
import br.unb.cic.td18.joint.JointRootGenerator;
import br.unb.cic.td18.joint.SupportClearing;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.search.WitnessSearch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Generates the joint-selection obligations and persists replayed sorting witnesses. */
public final class WitnessEmitter {
    private static final Logger log = LoggerFactory.getLogger(WitnessEmitter.class);
    private WitnessEmitter() { }

    public static void main(String[] arguments) throws IOException {
        var options = Options.parse(arguments, ProjectPaths.root(Path.of("")));
        if (options.help) { usage(); return; }
        var inventory = JointInventory.read(options.paper);
        Files.createDirectories(options.database.getParent());
        Files.createDirectories(options.work.getParent());
        Path work = options.work.getParent().toRealPath().resolve(options.work.getFileName());
        Path database = Files.exists(options.database) ? options.database.toRealPath()
                : options.database.getParent().toRealPath().resolve(options.database.getFileName());
        if (work.startsWith(database) || database.startsWith(work))
            throw new IllegalArgumentException("Work directory must be outside the certificate database");
        Files.createDirectory(work);
        try (var writer = new AsyncCertificateWriter(database, Long.getLong("td18.certificateFlushSeconds", 5L));
             var source = options.td16 == null ? null : new ReadOnlyWitnessSource(options.td16);
             var endpoints = new DiskStateSet(work.resolve("endpoint-residuals"));
             var progress = new Progress()) {
            WitnessSearch.installWriter(writer);
            try {
                if (options.roots()) for (var row : inventory) if (options.includesPartition(row.number())) {
                    var result = emitRoot(row, (pair, budget) -> settle(pair, budget, writer, null, true), progress);
                    writer.flush();
                    log.info("Root partition {}: unoriented={}, jointResiduals={}, supportCleared={}, directOriented={}, unresolved={}",
                            row.number(), result.unoriented, result.jointResiduals, result.supportCleared,
                            result.jointResiduals-result.supportCleared, result.unresolved);
                    if (result.unresolved != 0) throw new IllegalStateException("Unresolved direct root witnesses: " + result.unresolved);
                }
                if (options.endpoints() || options.avoidance()) for (var endpoint : JointInventory.endpoints())
                    if (options.includesEndpoint(endpoint.number())) {
                        // Avoidance regenerates misses from the witness database. It
                        // never starts from a persisted obstruction list alone.
                        boolean search = !options.phase.equals("avoidance");
                        emitEndpoint(endpoint, (pair, budget) -> settle(pair, budget, writer, source, search),
                                endpoints, progress);
                        writer.flush();
                    }
                if (options.avoidance()) {
                    progress.phase(() -> "final inverse reconstruction");
                    var result = JointAvoidance.resolve(endpoints, work,
                            pair -> settle(pair, 9, writer, null, true),
                            pair -> settle(pair, 10, writer, null, true));
                    writer.flush();
                    log.info("Final reconstruction: {}", result);
                    if (result.unresolved() != 0) throw new IllegalStateException("Unresolved reconstructed roots: " + result.unresolved());
                } else if (endpoints.seal() > 0) {
                    log.info("Endpoint classes requiring final reconstruction: {}", endpoints.seal());
                }
                log.info("Requested emission scope completed: phase={}, partitions={}-{}, endpoints={}-{}, database={}. Independent verification is required.",
                        options.phase, options.firstPartition, options.lastPartition, options.firstEndpoint, options.lastEndpoint, database);
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            WitnessSearch.shutdownGpuStats();
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    /** Returns true only for a replayed complete witness or a persisted known-diameter closure. */
    private static boolean settle(CyclicTargetPair input, int budget,
                                  AsyncCertificateWriter writer, ReadOnlyWitnessSource source, boolean search) {
        var pair = input.canonicalRepresentative();
        if (writer.find(pair, budget).isPresent()) return true;
        if (source != null) {
            var imported = source.find(pair, budget);
            if (imported.isPresent()) { writer.enqueue(pair, imported.orElseThrow()); return true; }
        }
        if (!search) return false;
        var result = WitnessSearch.search(pair, budget);
        if (result.disposition() != WitnessSearch.Disposition.RESIDUAL) return true;
        writer.enqueueObstruction(pair, budget);
        return false;
    }

    static RootResult emitRoot(JointInventory.Root row, PairDischarge discharge, Progress progress) {
        var unoriented = new LongAdder(); var joint = new LongAdder(); var cleared = new LongAdder();
        var unresolved = new LongAdder();
        progress.phase(() -> "root partition " + row.number() + ": unoriented=" + unoriented.sum()
                + " jointResiduals=" + joint.sum() + " unresolved=" + unresolved.sum());
        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(row.partition(), new boolean[row.partition().length],
                () -> { }, pair -> {
                    unoriented.increment();
                    if (!discharge.apply(pair.canonicalRepresentative(), 10)) unresolved.increment();
                });
        try (var tasks = new BoundedTasks()) {
            log.info("Root partition {}: joint generation uses {} available CPU cores",
                    row.number(), Runtime.getRuntime().availableProcessors());
            JointRootGenerator.generateResidualsConcurrently(row.partition(), tasks::check, pair -> {
                joint.increment();
                if (SupportClearing.closes(pair)) { cleared.increment(); return; }
                tasks.submit(() -> { if (!discharge.apply(pair.canonicalRepresentative(), 10)) unresolved.increment(); });
            });
        }
        if (unoriented.sum() != row.unorientedClasses() || joint.sum() != row.jointResidualClasses()
                || cleared.sum() != row.supportClearingClasses() || joint.sum()-cleared.sum() != row.orientedClasses())
            throw new IllegalStateException("Generated root counts disagree with paper at partition " + row.number());
        return new RootResult(unoriented.sum(), joint.sum(), cleared.sum(), unresolved.sum());
    }

    static void emitEndpoint(JointInventory.Endpoint endpoint, PairDischarge discharge,
                             DiskStateSet residuals, Progress progress) {
        if (endpoint.elementaryClosure()) {
            log.info("Endpoint {}: elementary oriented 3-cycle closure", endpoint.number());
            return;
        }
        var generated = new LongAdder(); var misses = new LongAdder();
        progress.phase(() -> "endpoint " + endpoint.number() + ": generated=" + generated.sum() + " misses=" + misses.sum());
        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(endpoint.partition(), endpoint.orientedByPart(),
                () -> { }, pair -> {
                    generated.increment();
                    if (!discharge.apply(pair.canonicalRepresentative(), 8)) {
                        residuals.add(pair, 8); misses.increment();
                    }
                });
        long expected = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(), endpoint.orientedByPart());
        if (generated.sum() != expected) throw new IllegalStateException("Endpoint generation count mismatch: " + endpoint.number());
        log.info("Endpoint {}: generated={}, missingEightMoveDischarges={}", endpoint.number(), generated.sum(), misses.sum());
    }

    @FunctionalInterface interface PairDischarge { boolean apply(CyclicTargetPair pair, int budget); }
    record RootResult(long unoriented, long jointResiduals, long supportCleared, long unresolved) { }

    static final class Progress implements AutoCloseable {
        private volatile Supplier<String> current = () -> "initializing";
        private final java.util.concurrent.ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("emission-progress").factory());
        Progress() { reporter.scheduleWithFixedDelay(() -> log.info("Emission progress: {}", current.get()), 10, 10, TimeUnit.SECONDS); }
        void phase(Supplier<String> value) { current = value; }
        @Override public void close() { reporter.shutdownNow(); }
    }

    private static final class BoundedTasks implements AutoCloseable {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ThreadPoolExecutor workers;
        BoundedTasks() {
            int count = Runtime.getRuntime().availableProcessors();
            workers = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
                    Thread.ofPlatform().name("root-witness-", 0).factory(), new ThreadPoolExecutor.CallerRunsPolicy());
        }
        void submit(Runnable task) { check(); workers.execute(() -> {
            if (failure.get() != null) return;
            try { task.run(); } catch (Throwable error) { failure.compareAndSet(null,error); }
        }); }
        void check() {
            if (failure.get() != null) throw new IllegalStateException("Root witness worker failed", failure.get());
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Root generation interrupted");
        }
        @Override public void close() {
            workers.shutdown();
            try { while (!workers.awaitTermination(1, TimeUnit.SECONDS)) {
                if (failure.get() != null) workers.shutdownNow();
            } } catch (InterruptedException error) {
                workers.shutdownNow(); Thread.currentThread().interrupt(); throw new IllegalStateException(error);
            }
            check();
        }
    }

    private static void usage() {
        System.out.println("WitnessEmitter [--phase=roots|endpoints|avoidance|all] [--partitions=FIRST-LAST]"
                + " [--endpoints=FIRST-LAST] [--paper=PATH] [--database=PATH] [--work=PATH] [--td16-db=PATH]");
    }

    record Options(Path paper, Path database, Path work, Path td16, String phase,
                   int firstPartition, int lastPartition, int firstEndpoint, int lastEndpoint, boolean help) {
        static Options parse(String[] args, Path root) {
            Path paper = root.resolve("docs/td18-reduction-count.tex");
            Path database = Path.of(System.getProperty("td18.certificateDb", root.resolve("certificates").toString()));
            Path work = root.resolve("emitter/target/joint-emission-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
            Path td16 = null; String phase = "all"; boolean help = false;
            int first = 1, last = 53, endpointFirst = 1, endpointLast = 104;
            for (String arg : args) {
                if (arg.equals("--help")) help = true;
                else if (arg.startsWith("--phase=")) phase = arg.substring(8);
                else if (arg.startsWith("--paper=")) paper = Path.of(arg.substring(8));
                else if (arg.startsWith("--database=")) database = Path.of(arg.substring(11));
                else if (arg.startsWith("--work=")) work = Path.of(arg.substring(7));
                else if (arg.startsWith("--td16-db=")) td16 = Path.of(arg.substring(10));
                else if (arg.startsWith("--partitions=")) { int[] r = range(arg.substring(13),53); first = r[0]; last = r[1]; }
                else if (arg.startsWith("--endpoints=")) { int[] r = range(arg.substring(12),104); endpointFirst = r[0]; endpointLast = r[1]; }
                else throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            if (!List.of("roots", "endpoints", "avoidance", "all").contains(phase)) throw new IllegalArgumentException("Invalid emission phase");
            return new Options(paper.toAbsolutePath().normalize(), database.toAbsolutePath().normalize(), work.toAbsolutePath().normalize(),
                    td16 == null ? null : td16.toAbsolutePath().normalize(), phase, first, last, endpointFirst, endpointLast, help);
        }
        private static int[] range(String value, int maximum) {
            String[] fields = value.split("-", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Expected FIRST-LAST range");
            int first = Integer.parseInt(fields[0]), last = Integer.parseInt(fields[1]);
            if (first < 1 || first > last || last > maximum) throw new IllegalArgumentException("Invalid range");
            return new int[]{first,last};
        }
        boolean roots() { return phase.equals("roots") || phase.equals("all"); }
        boolean endpoints() { return phase.equals("endpoints") || phase.equals("all"); }
        boolean avoidance() { return phase.equals("avoidance") || phase.equals("all"); }
        boolean includesPartition(int n) { return firstPartition <= n && n <= lastPartition; }
        boolean includesEndpoint(int n) { return firstEndpoint <= n && n <= lastEndpoint; }
    }
}

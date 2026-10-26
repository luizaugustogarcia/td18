package br.unb.cic.td18.emission;

import br.unb.cic.td18.avoidance.ObstructionAvoidance;
import br.unb.cic.td18.avoidance.ResidualObligation;
import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.base.GpuDeviceLayout;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.AvoidancePhaseManifest;
import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.certificate.CertificateRowManifest;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.search.SortingSearch;
import br.unb.cic.td18.search.WitnessSearch;
import br.unb.cic.td18.table.Table2;
import br.unb.cic.td18.table.TableEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Exhaustive obligation emitter for the annotated types in Table 2 of the
 * TD(18) two-reduction document. Its durable output is a three-column-family
 * RocksDB database of independently replayable sorting witnesses and key-only
 * obstruction hits, together with versioned sidecar manifests.
 *
 * <p>For each selected row with {@code Q = R}, direct twisted-bracelet
 * generation emits one cyclic-target pair per remaining extended-toric class.
 * Rows with {@code Q = 0} are already discharged and bypass generation and
 * search. A qualifying CPU prefix closes a generated class through a known
 * diameter and is not persisted as a certificate. Only a CPU miss reaches
 * native search. A successful native result is replayed by
 * {@link br.unb.cic.td18.certificate.CertificateValidator} before persistence; a
 * native miss is persisted for the common final obstruction phase. That phase
 * reconstructs every reachable full-domain root preimage and searches each
 * root independently with the ten-move budget.
 * </p>
 */
@Slf4j
public final class WitnessEmitter {
    private WitnessEmitter() {
    }

    public static void main(final String[] args) throws IOException {
        final var root = ProjectPaths.root(Path.of(""));
        final var options = Options.parse(args, root);
        if (options.help()) {
            printUsage();
            return;
        }

        final List<TableEntry> entries =
                Table2.read(options.table());

        final var selectedEntries = entries.stream()
                .filter(entry -> options.includes(entry.number()))
                .toList();
        selectedEntries.forEach(WitnessEmitter::validateEmissionEntry);
        final boolean requiresSearch = selectedEntries.stream()
                .anyMatch(entry -> entry.remainingClassObligations() > 0);
        if (requiresSearch) {
            initializeGpu();
        }
        final var database = Path.of(System.getProperty(
                "td18.certificateDb", "certificates"));
        final long flushSeconds = Long.parseLong(System.getProperty(
                "td18.certificateFlushSeconds", "5"));

        long generated = 0;
        long trivialClosures = 0;
        long certificates = 0;
        long residuals = 0;
        final var terminalResiduals =
                new ConcurrentLinkedQueue<ResidualObligation>();
        ObstructionAvoidance.AvoidanceResult avoidanceResult = null;
        try (final var writer = new AsyncCertificateWriter(database, flushSeconds)) {
            if (requiresSearch) {
                WitnessSearch.installWriter(writer);
            }
            if (options.firstRow() == 1 && options.lastRow() == 204) {
                OrientedNineteenCycleEmitter.ensure(writer, (pair, rate, budget) ->
                        SortingSearch.searchForSorting(pair, rate, budget));
                writer.flush();
                log.info("Required oriented 19-cycle witnesses are present: 2");
            }
            for (final var entry : selectedEntries) {
                if (entry.remainingClassObligations() == 0) {
                    log.info("Table 2 row {}: status=already-settled, "
                                    + "type={}, R={}, Q=0",
                            entry.number(), entry.type(),
                            entry.recordedRealizableClasses());
                } else {
                    log.info("Table 2 row {}: status=started, type={}, R={}, Q={}, "
                                    + "budget={}, minimumRate={}",
                            entry.number(), entry.type(),
                            entry.recordedRealizableClasses(),
                            entry.remainingClassObligations(),
                            entry.remainingMoves(), minimumRate(entry));
                }

                final var result = emitEntry(entry, (pair, rate, maximumMoves) ->
                                SortingSearch.searchForSorting(
                                        pair, rate, maximumMoves),
                        obligation -> {
                            writer.enqueueResidual(obligation);
                            terminalResiduals.add(obligation);
                        });
                generated = Math.addExact(generated, result.generated());
                trivialClosures = Math.addExact(
                        trivialClosures, result.trivialClosures());
                certificates = Math.addExact(certificates, result.certificates());
                residuals = Math.addExact(residuals, result.residuals());
                writer.enqueueManifest(new CertificateRowManifest(
                        entry.number(), entry.type(), entry.remainingMoves(),
                        entry.recordedRealizableClasses(),
                        entry.remainingClassObligations(), result.generated(),
                        result.trivialClosures(), result.certificates(),
                        result.residuals(), result.checksum(),
                        result.residuals() == 0));
                writer.throwIfFailed();

                log.info("Table 2 row {}: status=emitted, generated={}, "
                                + "trivially settled={}, certificates={}, residuals={}",
                        entry.number(), result.generated(),
                        result.trivialClosures(), result.certificates(),
                        result.residuals());
            }
            avoidanceResult = ObstructionAvoidance.resolve(terminalResiduals,
                    (pair, rate, maximumMoves, fullDomain) -> fullDomain
                            ? SortingSearch.searchForSorting(
                            pair, rate, maximumMoves)
                            : SortingSearch.screenAvoidanceIntermediate(
                            pair, maximumMoves),
                    writer::enqueueAvoidanceMiss);
            writer.enqueueAvoidanceManifest(new AvoidancePhaseManifest(
                    options.firstRow(), options.lastRow(),
                    avoidanceResult.terminalResiduals(),
                    avoidanceResult.reconstructionApplications(),
                    avoidanceResult.intermediatePrefixObligations(),
                    avoidanceResult.propagatedPrefixMisses(),
                    avoidanceResult.rootSearchObligations(),
                    avoidanceResult.trivialClosures(),
                    avoidanceResult.certificates(),
                    avoidanceResult.unresolved(), avoidanceResult.checksum(),
                    avoidanceResult.complete()));
            writer.throwIfFailed();
        } finally {
            if (requiresSearch) {
                GPUSortingSearch.shutdownStatsReporter();
                DirectTwistedBraceletGenerator.shutdownExecutor();
            }
        }

        log.info("TD(18) emission complete: rows={}-{}, generated={}, "
                        + "trivially settled={}, certificates={}, terminalResiduals={}, "
                        + "avoidanceRootPreimages={}, "
                        + "unresolved={}, database={}",
                options.firstRow(), options.lastRow(), generated,
                trivialClosures, certificates, residuals,
                avoidanceResult.screenedObligations(),
                avoidanceResult.unresolved(), database.toAbsolutePath());
        if (!avoidanceResult.complete()) {
            final var first = avoidanceResult.firstUnresolved();
            throw new IllegalStateException("Avoidance-preimage search left "
                    + avoidanceResult.unresolved()
                    + " unresolved root obligations; first: classKey="
                    + first.extendedToricClassKey()
                    + ", omega=" + first.getOmega()
                    + ", beta=" + first.getBeta());
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

    static EmissionResult emitEntry(final TableEntry entry,
                                    final PairSearch search,
                                    final Consumer<ResidualObligation> residualSink) {
        validateEmissionEntry(entry);
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(residualSink, "residualSink");
        if (entry.remainingClassObligations() == 0) {
            return EmissionResult.empty();
        }
        final var generated = new LongAdder();
        final var trivialClosures = new LongAdder();
        final var certificates = new LongAdder();
        final var residuals = new LongAdder();
        final var checksum = new LongAdder();
        final var firstResidual = new AtomicReference<CyclicTargetPair>();
        final float rate = minimumRate(entry);

        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(
                entry.partition(), entry.orientedByPart(), () -> { }, pair -> {
                    generated.increment();
                    final var result = search.search(
                            pair, rate, entry.remainingMoves());
                    switch (result.disposition()) {
                        case TRIVIALLY_SETTLED -> trivialClosures.increment();
                        case CERTIFICATE -> {
                            certificates.increment();
                            checksum.add(CertificateCodec.checksumContribution(
                                    pair, result.moves()));
                        }
                        case RESIDUAL -> {
                            final var obligation = new ResidualObligation(
                                    entry.number(), entry.remainingMoves(), pair);
                            residualSink.accept(obligation);
                            residuals.increment();
                            checksum.add(obligation.checksumContribution());
                            firstResidual.compareAndSet(null, obligation.pair());
                        }
                    }
                });

        final long generatedCount = generated.sum();
        if (generatedCount != entry.remainingClassObligations()) {
            throw new IllegalStateException("Table 2 row " + entry.number()
                    + " records Q=" + entry.remainingClassObligations()
                    + " but direct generation emitted " + generatedCount);
        }
        return new EmissionResult(generatedCount, trivialClosures.sum(),
                certificates.sum(), residuals.sum(), checksum.sum(),
                firstResidual.get());
    }

    private static void validateEmissionEntry(
            final TableEntry entry
    ) {
        if (entry.recordedRealizableClasses() == null
                || entry.remainingClassObligations() == null) {
            throw new IllegalArgumentException(
                    "Table row has no checked R/Q counts: " + entry.number());
        }
        if (entry.remainingClassObligations() != 0
                && !entry.remainingClassObligations().equals(
                entry.recordedRealizableClasses())) {
            throw new IllegalStateException(
                    "Table 2 row " + entry.number() + " has R="
                            + entry.recordedRealizableClasses() + " and Q="
                            + entry.remainingClassObligations()
                            + "; emission permits only Q=0 or Q=R");
        }
    }

    static float minimumRate(final TableEntry entry) {
        if (entry.remainingMoves() <= 0 || entry.threeNorm() <= 0) {
            throw new IllegalArgumentException(
                    "A sorting obligation needs a positive norm and move budget");
        }
        return entry.remainingMoves() /  (2 * (float) entry.threeNorm());
    }

    private static void printUsage() {
        System.out.println("WitnessEmitter [--table=path] [--rows=first-last]");
    }

    @FunctionalInterface
    interface PairSearch {
        WitnessSearch.SearchResult search(
                CyclicTargetPair pair, float minimumRate, int maximumMoves);
    }

    record EmissionResult(long generated, long trivialClosures,
                          long certificates, long residuals, long checksum,
                          CyclicTargetPair firstResidual) {
        EmissionResult {
            if (generated < 0 || trivialClosures < 0
                    || certificates < 0 || residuals < 0
                    || trivialClosures + certificates + residuals != generated
                    || (residuals == 0) != (firstResidual == null)) {
                throw new IllegalArgumentException("Inconsistent emission result");
            }
        }

        private static EmissionResult empty() {
            return new EmissionResult(0, 0, 0, 0, 0, null);
        }
    }

    private record Options(Path table, int firstRow, int lastRow, boolean help) {
        private static Options parse(final String[] args, final Path root) {
            var table = root.resolve("docs/td18-reduction-count.tex");
            var firstRow = 1;
            var lastRow = Table2.EXPECTED_ENTRIES;
            var help = false;
            for (final var argument : args) {
                if (argument.equals("--help")) {
                    help = true;
                } else if (argument.startsWith("--table=")) {
                    table = Path.of(argument.substring("--table=".length()))
                            .toAbsolutePath();
                } else if (argument.startsWith("--rows=")) {
                    final var range = argument.substring("--rows=".length())
                            .split("-", -1);
                    if (range.length != 2) {
                        throw new IllegalArgumentException(
                                "Expected --rows=first-last");
                    }
                    firstRow = Integer.parseInt(range[0]);
                    lastRow = Integer.parseInt(range[1]);
                } else {
                    throw new IllegalArgumentException(
                            "Unknown argument: " + argument);
                }
            }
            if (firstRow < 1 || lastRow < firstRow
                    || lastRow > Table2.EXPECTED_ENTRIES) {
                throw new IllegalArgumentException("Rows must be in the range 1-"
                        + Table2.EXPECTED_ENTRIES);
            }
            return new Options(table, firstRow, lastRow, help);
        }

        private boolean includes(final int row) {
            return row >= firstRow && row <= lastRow;
        }
    }
}

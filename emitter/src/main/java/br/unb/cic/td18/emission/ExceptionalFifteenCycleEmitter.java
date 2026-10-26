package br.unb.cic.td18.emission;

import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExceptionalFifteenCycle;
import br.unb.cic.td18.model.ExceptionalFifteenCycle.Obligation;
import br.unb.cic.td18.search.WitnessSearch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/** Validated prefix closures and witnesses for the exceptional-15 companion interleavings. */
@Slf4j
public final class ExceptionalFifteenCycleEmitter {
    private ExceptionalFifteenCycleEmitter() {
    }

    public static void main(final String[] args) throws IOException {
        String selectedType = null;
        for (final var argument : args) {
            if (argument.startsWith("--type=") && selectedType == null) {
                selectedType = argument.substring("--type=".length());
            } else {
                throw new IllegalArgumentException("Expected one --type=<annotated-type>@<depth> or no arguments");
            }
        }
        final var obligations = selectedObligations(selectedType);
        final var database = Path.of(System.getProperty("td18.certificateDb", "certificates"));
        final long flushSeconds = Long.parseLong(System.getProperty("td18.certificateFlushSeconds", "5"));
        final boolean[] gpuStarted = {false};
        try (final var writer = new AsyncCertificateWriter(database, flushSeconds)) {
            WitnessSearch.installWriter(writer);
            ensure(writer, obligations, (pair, rate, budget) -> {
                if (!gpuStarted[0]) {
                    OrientedNineteenCycleEmitter.initializeGpu();
                    gpuStarted[0] = true;
                }
                return WitnessSearch.searchCompleteWitness(pair, rate, budget);
            });
        } finally {
            if (gpuStarted[0]) {
                GPUSortingSearch.shutdownStatsReporter();
            }
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    public static List<EmissionResult> ensure(final AsyncCertificateWriter writer, final PairSearch search) {
        return ensure(writer, selectedObligations(null), search);
    }

    static List<EmissionResult> ensure(final AsyncCertificateWriter writer, final List<Obligation> obligations,
                                       final PairSearch search) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(search, "search");
        final var results = new ArrayList<EmissionResult>();
        for (final var obligation : obligations) {
            log.info("Exceptional-fifteen obligation started: key={}, domain={}, budget={}",
                    obligation.key(), obligation.domain(), obligation.budget());
            final var result = emitObligation(obligation, (pair, rate, budget) -> writer.find(pair, budget)
                    .map(WitnessSearch.SearchResult::certificate)
                    .orElseGet(() -> {
                        final var searched = Objects.requireNonNull(search.search(pair, rate, budget), "search result");
                        if (searched.disposition() == WitnessSearch.Disposition.CERTIFICATE) {
                            CertificateValidator.validate(pair, searched.moves(), budget);
                            writer.enqueue(pair, searched.moves());
                        }
                        return searched;
                    }));
            writer.flush();
            writer.throwIfFailed();
            log.info("Exceptional-fifteen obligation emitted: key={}, generated={}, cpuClosures={}, certificates={}, unresolved={}",
                    result.obligation(), result.generated(), result.cpuClosures(), result.certificates(), result.unresolved());
            if (!result.complete()) {
                throw new IllegalStateException("Unresolved exceptional-fifteen classes: " + result);
            }
            results.add(result);
        }
        return List.copyOf(results);
    }

    static List<Obligation> selectedObligations(final String selectedType) {
        final var all = ExceptionalFifteenCycle.obligations();
        if (!all.stream().map(Obligation::key).toList().equals(
                List.of("15O-2U-2U@0", "15O-3U@1"))) {
            throw new IllegalStateException("Unexpected exceptional-fifteen obligation inventory");
        }
        final var selected = all.stream().filter(obligation -> selectedType == null
                || obligation.key().equals(selectedType)).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Unknown exceptional-fifteen obligation: " + selectedType);
        }
        return selected;
    }

    static EmissionResult emitObligation(final Obligation obligation, final PairSearch search) {
        Objects.requireNonNull(search, "search");
        final var configured = selectedObligations(obligation.key()).getFirst();
        if (obligation.domain() != configured.domain() || obligation.movesUsed() != configured.movesUsed()
                || obligation.budget() != configured.budget()) {
            throw new IllegalArgumentException("Exceptional-fifteen obligation has an incorrect domain or budget");
        }
        final long[] counts = {0, 0, 0};
        final float rate = (float) (2 * Arrays.stream(obligation.type().partition()).map(size -> size / 2).sum())
                / obligation.budget();
        DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                obligation.type().partition(), obligation.type().oriented(), pair -> {
                    if (pair.getBeta().size() != obligation.domain() || !ExceptionalFifteenCycle.contains(pair)) {
                        throw new IllegalStateException("Invalid exceptional-fifteen representative: " + pair);
                    }
                    counts[0]++;
                    if (WitnessSearch.closesByCpuPrefix(pair, obligation.budget())) {
                        counts[1]++;
                        return;
                    }
                    final var result = Objects.requireNonNull(
                            search.search(pair, rate, obligation.budget()), "search result");
                    if (result.disposition() == WitnessSearch.Disposition.TRIVIALLY_SETTLED) {
                        throw new IllegalStateException("Exceptional-fifteen branch requires a complete witness");
                    }
                    if (result.disposition() == WitnessSearch.Disposition.CERTIFICATE) {
                        CertificateValidator.validate(pair, result.moves(), obligation.budget());
                        counts[2]++;
                    } else {
                        log.debug("Exceptional-fifteen search miss: obligation={} classKey={}",
                                obligation.key(), pair.extendedToricClassKey());
                    }
                });
        return new EmissionResult(obligation.key(), counts[0], counts[1], counts[2],
                counts[0] - counts[1] - counts[2]);
    }

    static long expectedClasses(final String obligation) {
        return switch (obligation) {
            case "15O-2U-2U@0" -> 90;
            case "15O-3U@1" -> 7;
            default -> throw new IllegalArgumentException("Unknown exceptional-fifteen obligation: " + obligation);
        };
    }

    public record EmissionResult(String obligation, long generated, long cpuClosures,
                                 long certificates, long unresolved) {
        public EmissionResult {
            Objects.requireNonNull(obligation, "obligation");
            if (generated != expectedClasses(obligation) || cpuClosures < 0 || cpuClosures > generated
                    || certificates < 0 || certificates > generated - cpuClosures
                    || unresolved != generated - cpuClosures - certificates) {
                throw new IllegalArgumentException("Incomplete or inconsistent exceptional-fifteen emission result");
            }
        }

        public boolean complete() {
            return unresolved == 0;
        }
    }

    @FunctionalInterface
    public interface PairSearch {
        WitnessSearch.SearchResult search(CyclicTargetPair pair, float minimumRate, int maximumMoves);
    }
}

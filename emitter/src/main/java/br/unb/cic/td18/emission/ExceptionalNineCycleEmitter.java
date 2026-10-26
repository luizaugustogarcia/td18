package br.unb.cic.td18.emission;

import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExceptionalNineCycle;
import br.unb.cic.td18.reduction.AnnotatedPartitions.AnnotatedPartition;
import br.unb.cic.td18.search.WitnessSearch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/** Full-domain closures for every exceptional-nine interleaving on 19 symbols. */
@Slf4j
public final class ExceptionalNineCycleEmitter {
    private ExceptionalNineCycleEmitter() {
    }

    public static void main(final String[] args) throws IOException {
        String selectedType = null;
        for (final var argument : args) {
            if (argument.startsWith("--type=")) {
                selectedType = argument.substring("--type=".length());
            } else {
                throw new IllegalArgumentException("Expected --type=9O-... or no arguments");
            }
        }
        final var types = selectedTypes(selectedType);
        final var database = Path.of(System.getProperty("td18.certificateDb", "certificates"));
        final long flushSeconds = Long.parseLong(System.getProperty("td18.certificateFlushSeconds", "5"));
        final boolean[] gpuStarted = {false};
        try (final var writer = new AsyncCertificateWriter(database, flushSeconds)) {
            WitnessSearch.installWriter(writer);
            ensure(writer, types, (pair, rate, budget) -> {
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

    public static void ensure(final AsyncCertificateWriter writer, final PairSearch search) {
        ensure(writer, selectedTypes(null), search);
    }

    private static void ensure(final AsyncCertificateWriter writer, final List<AnnotatedPartition> types,
                               final PairSearch search) {
        for (final var type : types) {
            log.info("Exceptional-nine type started: {}", ExceptionalNineCycle.typeKey(type));
            final var result = emitType(type, (pair, rate, budget) -> writer.find(pair, budget)
                    .map(WitnessSearch.SearchResult::certificate)
                    .orElseGet(() -> search.search(pair, rate, budget)));
            writer.flush();
            writer.throwIfFailed();
            log.info("Exceptional-nine type emitted: type={}, generated={}, certificates={}, unresolved={}",
                    result.type(), result.generated(), result.certificates(), result.unresolved());
            if (!result.complete()) {
                throw new IllegalStateException("Unresolved exceptional-nine root classes: " + result);
            }
        }
    }

    static List<AnnotatedPartition> selectedTypes(final String selectedType) {
        final var all = ExceptionalNineCycle.rootTypes();
        if (all.size() != 18) {
            throw new IllegalStateException("Exceptional-nine root inventory must have 18 annotations");
        }
        final var selected = all.stream().filter(type -> selectedType == null
                || ExceptionalNineCycle.typeKey(type).equals(selectedType)).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Unknown exceptional-nine type: " + selectedType);
        }
        return selected;
    }

    static EmissionResult emitType(final AnnotatedPartition type, final PairSearch search) {
        final long[] counts = {0, 0};
        final var rate = (float) (2 * java.util.Arrays.stream(type.partition()).map(size -> size / 2).sum())
                / ExceptionalNineCycle.MAXIMUM_MOVES;
        DirectTwistedBraceletGenerator.generateExceptionalNineRepresentatives(
                type.partition(), type.oriented(), pair -> {
                    if (pair.getBeta().size() != ExceptionalNineCycle.DOMAIN
                            || !ExceptionalNineCycle.contains(pair)) {
                        throw new IllegalStateException("Invalid exceptional-nine root: " + pair);
                    }
                    final var result = search.search(pair, rate, ExceptionalNineCycle.MAXIMUM_MOVES);
                    if (result.disposition() == WitnessSearch.Disposition.TRIVIALLY_SETTLED) {
                        throw new IllegalStateException("Exceptional-nine branch requires a complete witness");
                    }
                    if (result.disposition() == WitnessSearch.Disposition.CERTIFICATE) {
                        CertificateValidator.validate(pair, result.moves(), ExceptionalNineCycle.MAXIMUM_MOVES);
                        counts[1]++;
                    } else {
                        log.error("Exceptional-nine search miss: type={} classKey={}",
                                ExceptionalNineCycle.typeKey(type), pair.extendedToricClassKey());
                    }
                    counts[0]++;
                });
        return new EmissionResult(ExceptionalNineCycle.typeKey(type), counts[0], counts[1],
                counts[0] - counts[1]);
    }

    public record EmissionResult(String type, long generated, long certificates, long unresolved) {
        public EmissionResult {
            java.util.Objects.requireNonNull(type);
            if (generated < 0 || certificates < 0 || certificates > generated
                    || unresolved != generated - certificates) {
                throw new IllegalArgumentException("Inconsistent exceptional-nine emission result");
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

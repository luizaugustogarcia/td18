package br.unb.cic.td18.emission;

import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.base.GpuDeviceLayout;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.search.CommonOperations;
import br.unb.cic.td18.search.SortingSearch;
import br.unb.cic.td18.search.WitnessSearch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import lombok.extern.slf4j.Slf4j;

/** Emits sorting certificates for the two known oriented 19-cycle survivors. */
@Slf4j
public final class OrientedNineteenCycleEmitter {
    static final int CYCLE_LENGTH = 19;
    static final int MAXIMUM_MOVES = 10;
    static final float MINIMUM_RATE = 18 / 10.0f;

    private static final int[][] SURVIVOR_CYCLES = {
            {
                    0, 10, 1, 11, 2, 12, 3, 13, 4, 14,
                    5, 15, 6, 16, 7, 17, 8, 18, 9
            },
            {
                    0, 16, 13, 10, 7, 4, 1, 17, 14, 11,
                    8, 5, 2, 18, 15, 12, 9, 6, 3
            }
    };

    private OrientedNineteenCycleEmitter() {
    }

    public static void main(final String[] args) throws IOException {
        Path source = null;
        for (final var argument : args) {
            if (argument.startsWith("--witnesses=")) {
                source = Path.of(argument.substring("--witnesses=".length()));
            } else {
                throw new IllegalArgumentException("Expected --witnesses=path or no arguments");
            }
        }
        final Map<String, List<Cycle>> supplied = source == null ? null : readWitnesses(source);
        final var database = Path.of(System.getProperty(
                "td18.certificateDb", "certificates"));
        final long flushSeconds = Long.parseLong(System.getProperty(
                "td18.certificateFlushSeconds", "5"));

        final EmissionResult result;
        try (final var writer = new AsyncCertificateWriter(
                database, flushSeconds)) {
            WitnessSearch.installWriter(writer);
            result = ensure(writer, (pair, minimumRate, maximumMoves) -> {
                if (supplied != null) {
                    final var moves = supplied.get(HexFormat.of().formatHex(CertificateCodec.keyFor(pair)));
                    writer.enqueue(pair, moves);
                    return WitnessSearch.SearchResult.certificate(moves);
                }
                initializeGpu();
                return SortingSearch.searchForSorting(pair, minimumRate, maximumMoves);
            });
            writer.flush();
            writer.throwIfFailed();
        } finally {
            // The CPU import path must not initialize the native search class.
            if (supplied == null && gpuStarted) {
                GPUSortingSearch.shutdownStatsReporter();
            }
        }

        log.info("Oriented 19-cycle survivor emission complete: "
                        + "survivors={}, certificates={}, database={}",
                result.survivors(), result.certificates(),
                database.toAbsolutePath());
    }

    public static EmissionResult ensure(final AsyncCertificateWriter writer, final PairSearch search) {
        return emit((pair, rate, budget) -> writer.find(pair, budget)
                .map(WitnessSearch.SearchResult::certificate)
                .orElseGet(() -> search.search(pair, rate, budget)));
    }

    static Map<String, List<Cycle>> readWitnesses(final Path path) throws IOException {
        final var records = new HashMap<String, List<Cycle>>();
        for (final var line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            final var fields = line.split("\\s+", -1);
            if (fields.length != 2 || records.putIfAbsent(fields[0],
                    CertificateCodec.decodeValue(HexFormat.of().parseHex(fields[1]))) != null) {
                throw new IllegalArgumentException("Expected unique hex instance/witness pairs");
            }
        }
        final var pairs = survivorPairs();
        if (records.size() != pairs.size()) {
            throw new IllegalArgumentException("Expected both oriented 19-cycle witnesses");
        }
        for (final var pair : pairs) {
            final var moves = records.get(HexFormat.of().formatHex(CertificateCodec.keyFor(pair)));
            if (moves == null) {
                throw new IllegalArgumentException("Missing oriented 19-cycle witness");
            }
            CertificateValidator.validate(pair, moves, MAXIMUM_MOVES);
        }
        return Map.copyOf(records);
    }

    static EmissionResult emit(final PairSearch search) {
        Objects.requireNonNull(search, "search");
        final var pairs = survivorPairs();
        var certificates = 0;

        for (final var pair : pairs) {
            final var result = search.search(
                    pair, MINIMUM_RATE, MAXIMUM_MOVES);
            if (result.disposition() !=
                    WitnessSearch.Disposition.CERTIFICATE) {
                throw new IllegalStateException(
                        "Oriented 19-cycle survivor was not emitted as a "
                                + "complete sorting certificate: classKey="
                                + pair.extendedToricClassKey()
                                + ", disposition=" + result.disposition());
            }
            CertificateValidator.validate(
                    pair, result.moves(), MAXIMUM_MOVES);
            certificates++;
        }

        return new EmissionResult(pairs.size(), certificates);
    }

    static List<CyclicTargetPair> survivorPairs() {
        final var beta = Cycle.of(
                IntStream.range(0, CYCLE_LENGTH).toArray());
        final var pairs = new ArrayList<CyclicTargetPair>(
                SURVIVOR_CYCLES.length);
        for (final var symbols : SURVIVOR_CYCLES) {
            final var cycle = Cycle.of(symbols.clone());
            if (!CommonOperations.isOriented(beta, cycle)) {
                throw new IllegalStateException(
                        "Configured 19-cycle survivor is not oriented: "
                                + cycle);
            }
            final var pair = new CyclicTargetCandidate(
                    new MulticyclePermutation(cycle), beta)
                    .requireCyclicTargetPair()
                    .canonicalRepresentative();
            pairs.add(pair);
        }
        pairs.sort(Comparator.comparing(
                CyclicTargetPair::extendedToricClassKey,
                Comparator.comparing(key -> key.twistedBracelet())));
        if (pairs.size() != 2
                || pairs.get(0).extendedToricClassKey().equals(
                pairs.get(1).extendedToricClassKey())) {
            throw new IllegalStateException(
                    "Expected two distinct oriented 19-cycle survivor classes");
        }
        return List.copyOf(pairs);
    }

    private static boolean gpuStarted;

    static void initializeGpu() {
        final var devices = GpuDeviceLayout.parseDevices(
                System.getProperty("td18.gpuDevices", "0"));
        final int slots = Integer.parseInt(
                System.getProperty("td18.gpuSlots", "1"));
        final long budgetMiB = Long.parseLong(
                System.getProperty("td18.gpuBudgetMB", "1024"));
        GPUSortingSearch.init(devices, slots,
                Math.multiplyExact(budgetMiB, 1024L * 1024L));
        gpuStarted = true;
    }

    @FunctionalInterface
    public interface PairSearch {
        WitnessSearch.SearchResult search(
                CyclicTargetPair pair,
                float minimumRate,
                int maximumMoves);
    }

    public record EmissionResult(int survivors, int certificates) {
        public EmissionResult {
            if (survivors != 2 || certificates != survivors) {
                throw new IllegalArgumentException(
                        "Every configured survivor must have a certificate");
            }
        }
    }
}

package br.unb.cic.td18;

import br.unb.cic.td18.base.AsyncCertificateWriter;
import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CertificateValidator;
import br.unb.cic.td18.base.CommonOperations;
import br.unb.cic.td18.base.CyclicTargetCandidate;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.base.GpuDeviceLayout;
import br.unb.cic.td18.base.SortingSearch;
import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

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

    public static void main(final String[] args) {
        initializeGpu();
        final var database = Path.of(System.getProperty(
                "td18.certificateDb", "certificates"));
        final long flushSeconds = Long.parseLong(System.getProperty(
                "td18.certificateFlushSeconds", "5"));

        final EmissionResult result;
        try (final var writer = new AsyncCertificateWriter(
                database, flushSeconds)) {
            WitnessSearch.installWriter(writer);
            result = emit((pair, minimumRate, maximumMoves) ->
                    SortingSearch.searchForSorting(
                            pair, minimumRate, maximumMoves));
            writer.throwIfFailed();
        } finally {
            GPUSortingSearch.shutdownStatsReporter();
        }

        log.info("Oriented 19-cycle survivor emission complete: "
                        + "survivors={}, certificates={}, checksum={}, database={}",
                result.survivors(), result.certificates(), result.checksum(),
                database.toAbsolutePath());
    }

    static EmissionResult emit(final PairSearch search) {
        Objects.requireNonNull(search, "search");
        final var pairs = survivorPairs();
        long checksum = 0;
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
            checksum = Math.addExact(checksum,
                    CertificateCodec.checksumContribution(
                            pair, result.moves()));
            certificates++;
        }

        return new EmissionResult(pairs.size(), certificates, checksum);
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

    private static void initializeGpu() {
        final var devices = GpuDeviceLayout.parseDevices(
                System.getProperty("td18.gpuDevices", "0"));
        final int slots = Integer.parseInt(
                System.getProperty("td18.gpuSlots", "1"));
        final long budgetMiB = Long.parseLong(
                System.getProperty("td18.gpuBudgetMB", "1024"));
        GPUSortingSearch.init(devices, slots,
                Math.multiplyExact(budgetMiB, 1024L * 1024L));
    }

    @FunctionalInterface
    interface PairSearch {
        WitnessSearch.SearchResult search(
                CyclicTargetPair pair,
                float minimumRate,
                int maximumMoves);
    }

    record EmissionResult(int survivors, int certificates, long checksum) {
        EmissionResult {
            if (survivors != 2 || certificates != survivors) {
                throw new IllegalArgumentException(
                        "Every configured survivor must have a certificate");
            }
        }
    }
}

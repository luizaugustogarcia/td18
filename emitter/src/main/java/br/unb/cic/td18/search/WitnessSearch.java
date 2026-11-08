package br.unb.cic.td18.search;

import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.base.GpuDeviceLayout;
import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.certificate.SortingCertificate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.OneLinePermutation;
import br.unb.cic.td18.joint.KnownDiameters;

import java.util.List;
import java.util.Objects;

/** The only route from a sorting search result to a persisted certificate. */
public final class WitnessSearch {
    private static volatile AsyncCertificateWriter certificateWriter;
    private static volatile boolean gpuInitialized;

    private WitnessSearch() {
    }

    public static synchronized void installWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != null) {
            throw new IllegalStateException(
                    "A proof-scoped certificate writer is already installed");
        }
        certificateWriter = Objects.requireNonNull(writer);
    }

    public static synchronized void uninstallWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != writer) throw new IllegalStateException("Writer scope mismatch");
        certificateWriter = null;
    }

    public static void shutdownGpuStats() {
        if (gpuInitialized) GPUSortingSearch.shutdownStatsReporter();
    }

    private static GPUSortingSearch gpu() {
        final var devices = GpuDeviceLayout.parseDevices(System.getProperty("td18.gpuDevices", "0"));
        final int slots = Integer.parseInt(System.getProperty("td18.gpuSlots", "7"));
        final long budget = Long.parseLong(System.getProperty("td18.gpuBudgetMB", "1024"));
        GPUSortingSearch.init(devices, slots, Math.multiplyExact(budget, 1024L * 1024L));
        gpuInitialized = true;
        return GPUSortingSearch.getInstance();
    }

    /** Searches the full budget, accepting identity or a replayed known-diameter closure at any depth. */
    public static SearchResult search(final CyclicTargetPair input, final int maximumMoves) {
        Objects.requireNonNull(input);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET)
            throw new IllegalArgumentException("Sorting budget must lie between 1 and 10");
        final var pair = input.canonicalRepresentative();
        final var writer = certificateWriter;
        if (writer == null) throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        final var existing = writer.findResult(pair, maximumMoves);
        if (existing.isPresent()) return checked(existing.orElseThrow());
        final byte[] beta = new byte[pair.getBeta().size()];
        for (int i = 0; i < beta.length; i++) beta[i] = (byte) pair.getBeta().get(i);
        final var outcome = gpu().search(beta, new OneLinePermutation(pair.getOmega().getOneLineNotation()), maximumMoves, true);
        if (outcome.disposition() == GPUSortingSearch.Disposition.RESIDUAL) return SearchResult.residual();
        final var moves = outcome.moves().stream().map(move -> Cycle.of(move[0], move[1], move[2])).toList();
        final var result = outcome.disposition() == GPUSortingSearch.Disposition.COMPLETE
                ? SortingCertificate.complete(moves)
                : SortingCertificate.closure(moves);
        CertificateValidator.validate(pair, result, maximumMoves);
        if (result.kind() == SortingCertificate.Kind.COMPLETE) writer.enqueue(pair, moves);
        else writer.enqueueClosure(pair, moves, maximumMoves);
        writer.throwIfFailed();
        return checked(result);
    }

    private static SearchResult checked(SortingCertificate result) {
        return new SearchResult(result.kind() == SortingCertificate.Kind.COMPLETE
                ? Disposition.CERTIFICATE : Disposition.DIAMETER_CLOSURE, result.moves());
    }

    public enum Disposition { CERTIFICATE, DIAMETER_CLOSURE, RESIDUAL }
    public record SearchResult(Disposition disposition, List<Cycle> moves) {
        public SearchResult {
            Objects.requireNonNull(disposition);
            moves = List.copyOf(moves);
            if (disposition == Disposition.RESIDUAL && !moves.isEmpty())
                throw new IllegalArgumentException("A residual cannot contain moves");
        }
        public static SearchResult residual() { return new SearchResult(Disposition.RESIDUAL, List.of()); }
    }
}

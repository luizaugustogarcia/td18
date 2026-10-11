package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateValidator;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.GPUSortingSearch;
import br.unb.cic.tdp.base.GpuDeviceLayout;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.OneLinePermutation;

import java.util.List;
import java.util.Optional;

/** CUDA-backed exact bounded sorter with shared in-flight requests. */
public final class GpuBoundedSorter implements BoundedSorter {
    private final BoundedSorter requests = new CoalescingBoundedSorter(this::searchCanonical);

    public GpuBoundedSorter(
            final String devices,
            final int slotsPerDevice,
            final long memoryBudgetMiBPerSlot
    ) {
        if (memoryBudgetMiBPerSlot <= 0) {
            throw new IllegalArgumentException("GPU memory budget must be positive");
        }
        GPUSortingSearch.init(
                GpuDeviceLayout.parseDevices(devices),
                slotsPerDevice,
                Math.multiplyExact(memoryBudgetMiBPerSlot, 1024L * 1024L));
    }

    @Override
    public Optional<List<Cycle>> sort(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        return requests.sort(pair, maximumMoves);
    }

    private Optional<List<Cycle>> searchCanonical(
            final CyclicTargetPair canonical,
            final int maximumMoves
    ) {
        if (canonical.getOmega().isIdentity()) {
            return Optional.of(List.of());
        }
        final var beta = canonical.getBeta();
        final var betaBytes = new byte[beta.size()];
        for (var index = 0; index < beta.size(); index++) {
            betaBytes[index] = (byte) beta.get(index);
        }
        final var minRate = 2 / (maximumMoves / (float) canonical.getOmega().get3Norm());
        final var moves = GPUSortingSearch.getInstance().search(
                        betaBytes,
                        new OneLinePermutation(
                                canonical.getOmega().getOneLineNotation()),
                        canonical.getOmega().getNumberOfEvenCycles(),
                        minRate,
                        maximumMoves,
                        true)
                .stream()
                .map(move -> Cycle.of(move[0], move[1], move[2]))
                .toList();
        if (moves.isEmpty()) {
            return Optional.empty();
        }
        CertificateValidator.validate(canonical, moves, maximumMoves);
        return Optional.of(moves);
    }
}

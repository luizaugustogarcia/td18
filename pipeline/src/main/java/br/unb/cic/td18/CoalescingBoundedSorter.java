package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.permutation.Cycle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One overlapping backend search per canonical class and exact depth bound. */
final class CoalescingBoundedSorter implements BoundedSorter {
    private final BoundedSorter delegate;
    private final InFlightTasks<SearchKey, Optional<List<Cycle>>> searches =
            new InFlightTasks<>();

    CoalescingBoundedSorter(final BoundedSorter delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Optional<List<Cycle>> sort(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 0) {
            throw new IllegalArgumentException("Maximum moves must not be negative");
        }
        final var canonical = pair.canonicalRepresentative();
        final var key = new SearchKey(canonical.extendedToricClassKey(), maximumMoves);
        final var result = searches.run(key, () -> delegate.sort(canonical, maximumMoves));
        // Cycle exposes a mutable symbol array; each caller owns its witness.
        return result.map(moves -> moves.stream()
                .map(move -> Cycle.of(move.getSymbols().clone())).toList());
    }

    private record SearchKey(ExtendedToricClassKey classKey, int maximumMoves) {
    }
}

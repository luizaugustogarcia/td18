package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;

import java.util.List;
import java.util.Optional;

/** Exact bounded sorting search: empty means exhaustive failure within the bound. */
@FunctionalInterface
public interface BoundedSorter {
    Optional<List<Cycle>> sort(CyclicTargetPair pair, int maximumMoves);
}

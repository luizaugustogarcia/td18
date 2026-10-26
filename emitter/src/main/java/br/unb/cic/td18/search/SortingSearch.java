package br.unb.cic.td18.search;

import br.unb.cic.td18.model.CyclicTargetPair;

/** Compatibility facade for the native witness-and-persistence boundary. */
public final class SortingSearch {
    private SortingSearch() {
    }

    /** Classifies a CPU closure or searches, validates, and persists a GPU witness. */
    public static WitnessSearch.SearchResult searchForSorting(
            final CyclicTargetPair pair,
            final float minimumRate,
            final int maximumMoves) {
        return WitnessSearch.search(pair, minimumRate, maximumMoves);
    }

    /** Screens an avoidance intermediate using only the CPU prefix closure. */
    public static WitnessSearch.SearchResult screenAvoidanceIntermediate(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        return WitnessSearch.screenIntermediatePrefix(pair, maximumMoves);
    }
}

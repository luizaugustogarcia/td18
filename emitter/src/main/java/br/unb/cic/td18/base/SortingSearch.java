package br.unb.cic.td18.base;

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

    /** Classifies and persists an avoidance-intermediate search outcome. */
    public static WitnessSearch.SearchResult searchAvoidanceIntermediate(
            final CyclicTargetPair pair,
            final float minimumRate,
            final int maximumMoves
    ) {
        return WitnessSearch.searchIntermediate(
                pair, minimumRate, maximumMoves);
    }
}

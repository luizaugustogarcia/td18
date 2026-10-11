package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;

/** Established diameter bounds at ordinary orders 12 through 17. */
final class KnownDiameters {
    private KnownDiameters() {
    }

    static int forDomain(final int domainSize) {
        // A cyclic-target domain has one more symbol than the ordinary order.
        return switch (domainSize - 1) {
            case 12 -> 7;
            case 13, 14 -> 8;
            case 15, 16 -> 9;
            case 17 -> 10;
            default -> throw new IllegalArgumentException(
                    "No established diameter used for order " + (domainSize - 1));
        };
    }

    static void requireExactDepth(
            final int domainSize,
            final int excludedDepth,
            final int exactDepth
    ) {
        if (exactDepth != forDomain(domainSize)
                || excludedDepth != exactDepth - 1) {
            throw new IllegalArgumentException(
                    "Exact classification must exclude the preceding diameter depth");
        }
    }

    static boolean liesBeyondDepth(
            final CyclicTargetPair pair,
            final int excludedDepth,
            final BoundedSorter sorter
    ) {
        if (excludedDepth < 0 || excludedDepth >= forDomain(pair.getBeta().size())) {
            throw new IllegalArgumentException(
                    "Excluded depth must lie below the established diameter");
        }
        final var canonical = pair.canonicalRepresentative();
        return ApproximationBound.guaranteedMoves(canonical.getThreeNorm())
                > excludedDepth
                && sorter.sort(canonical, excludedDepth).isEmpty();
    }
}

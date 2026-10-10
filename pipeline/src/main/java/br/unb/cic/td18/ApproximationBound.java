package br.unb.cic.td18;

/** Integer upper bound supplied by the applicable 3-norm approximation guarantee. */
public final class ApproximationBound {
    private static final int ELEVEN_EIGHTHS_MINIMUM_THREE_NORM = 8;

    private ApproximationBound() {
    }

    public static int guaranteedMoves(final int threeNorm) {
        if (threeNorm < 0) {
            throw new IllegalArgumentException("3-norm must not be negative");
        }
        if (threeNorm >= ELEVEN_EIGHTHS_MINIMUM_THREE_NORM) {
            return Math.floorDiv(Math.multiplyExact(11, threeNorm), 8);
        }
        return Math.floorDiv(Math.multiplyExact(3, threeNorm), 2);
    }
}

package br.unb.cic.td18;

/** Integer consequence of the 1.375 guarantee measured against the 3-norm. */
public final class ApproximationBound {
    private ApproximationBound() {
    }

    public static int guaranteedMoves(final int threeNorm) {
        if (threeNorm < 0) {
            throw new IllegalArgumentException("3-norm must not be negative");
        }
        return Math.floorDiv(Math.multiplyExact(11, threeNorm), 8);
    }
}

package br.unb.cic.td18.verification;

import br.unb.cic.td18.joint.CyclicTargetMoves;
import br.unb.cic.td18.joint.KnownDiameters;
import br.unb.cic.td18.model.CyclicTargetPair;

import java.util.Objects;

/**
 * A contracted obligation with its explicit sorting budget.
 */
record SearchState(int maximumMoves, CyclicTargetPair pair) {

    SearchState {
        Objects.requireNonNull(pair);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET) {
            throw new IllegalArgumentException("Invalid reconstruction budget");
        }
        if (CyclicTargetMoves.fixed(CyclicTargetMoves.images(pair)) != 0) {
            throw new IllegalArgumentException("Reconstruction states must be contracted");
        }
    }
}

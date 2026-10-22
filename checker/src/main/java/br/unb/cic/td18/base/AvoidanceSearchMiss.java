package br.unb.cic.td18.base;

import java.util.Objects;

/** In-memory miss produced while sorting an S_18 avoidance-root class. */
public record AvoidanceSearchMiss(
        int maximumMoves,
        CyclicTargetPair pair
) {
    public AvoidanceSearchMiss {
        pair = Objects.requireNonNull(pair).canonicalRepresentative();
        if (maximumMoves != 10 || pair.getBeta().size() != 19
                || !pair.formsCyclicTargetPair()
                || pair.getOmega().stream().anyMatch(cycle -> cycle.size() == 1)) {
            throw new IllegalArgumentException(
                    "Inconsistent avoidance-search miss");
        }
    }

    public byte[] key() {
        return CertificateCodec.keyFor(pair);
    }
}

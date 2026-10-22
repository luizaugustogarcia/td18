package br.unb.cic.td18.base;

import java.util.Objects;
import java.util.zip.CRC32C;

/** In-memory terminal obstruction reconstructed in context from Table 2. */
public record ResidualObligation(
        int row,
        int maximumMoves,
        CyclicTargetPair pair
) {
    public ResidualObligation {
        pair = Objects.requireNonNull(pair).canonicalRepresentative();
        if (row <= 0 || row > 0xFFFF
                || maximumMoves <= 0
                || !pair.formsCyclicTargetPair()) {
            throw new IllegalArgumentException("Inconsistent residual obligation");
        }
        if (pair.getOmega().stream().anyMatch(cycle -> cycle.size() == 1)) {
            throw new IllegalArgumentException(
                    "Table 2 residual obligations must use a contracted fixed-point-free pair");
        }
    }

    public byte[] key() {
        return CertificateCodec.keyFor(pair);
    }

    public long checksumContribution() {
        final var checksum = new CRC32C();
        checksum.update(key());
        return checksum.getValue();
    }
}

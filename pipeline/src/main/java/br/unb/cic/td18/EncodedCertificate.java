package br.unb.cic.td18;

import java.util.Objects;

/** Compact encoded class key and its sorting witness for write-behind output. */
record EncodedCertificate(byte[] key, byte[] witness) {
    EncodedCertificate {
        Objects.requireNonNull(key);
        Objects.requireNonNull(witness);
    }
}

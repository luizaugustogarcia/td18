package br.unb.cic.td18;

import java.util.Objects;

/** Compact encoded class key with an optional sorting witness. */
record EncodedCertificate(byte[] key, byte[] witness) {
    EncodedCertificate {
        Objects.requireNonNull(key);
    }
}

package br.unb.cic.td18;

import br.unb.cic.tdp.base.CertificateCodec;
import br.unb.cic.tdp.base.CyclicTargetPair;

import java.util.Arrays;
import java.util.Objects;

/** Compact value key for a canonical extended-toric class. */
final class EncodedClassKey implements Comparable<EncodedClassKey> {
    private final byte[] bytes;
    private final int hashCode;

    private EncodedClassKey(final byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes);
        hashCode = Arrays.hashCode(bytes);
    }

    static EncodedClassKey of(final CyclicTargetPair pair) {
        final var canonical = Objects.requireNonNull(pair).canonicalRepresentative();
        return new EncodedClassKey(CertificateCodec.keyFor(canonical));
    }

    byte[] bytes() {
        return bytes;
    }

    @Override
    public int compareTo(final EncodedClassKey other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof EncodedClassKey other
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}

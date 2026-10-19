package br.unb.cic.td18.base;

import java.util.Arrays;
import java.util.Objects;

/** Durable miss produced while sorting an S_18 avoidance-root class. */
public record AvoidanceSearchMiss(
        int maximumMoves,
        CyclicTargetPair pair
) {
    private static final int RECORD_TYPE = 5;
    private static final int FORMAT_VERSION = 1;
    private static final int KEY_HEADER_BYTES = 3;

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
        final var pairKey = CertificateCodec.keyFor(pair);
        final var key = new byte[KEY_HEADER_BYTES + pairKey.length];
        key[0] = RECORD_TYPE;
        key[1] = FORMAT_VERSION;
        key[2] = (byte) maximumMoves;
        System.arraycopy(pairKey, 0, key, KEY_HEADER_BYTES, pairKey.length);
        return key;
    }

    public byte[] value() {
        return new byte[]{FORMAT_VERSION};
    }

    public static AvoidanceSearchMiss decode(
            final byte[] key,
            final byte[] value
    ) {
        if (key == null || key.length <= KEY_HEADER_BYTES
                || Byte.toUnsignedInt(key[0]) != RECORD_TYPE
                || Byte.toUnsignedInt(key[1]) != FORMAT_VERSION
                || value == null || value.length != 1
                || Byte.toUnsignedInt(value[0]) != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Malformed avoidance-search miss");
        }
        final int maximumMoves = Byte.toUnsignedInt(key[2]);
        final var pairKey = Arrays.copyOfRange(
                key, KEY_HEADER_BYTES, key.length);
        final var pair = CertificateCodec.pairForKey(
                pairKey, CertificateCodec.domainSizeForKey(pairKey));
        return new AvoidanceSearchMiss(maximumMoves, pair);
    }
}

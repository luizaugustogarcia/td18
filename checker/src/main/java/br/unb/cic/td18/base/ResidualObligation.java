package br.unb.cic.td18.base;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32C;

/** Durable terminal class left by bounded search for later obstruction handling. */
public record ResidualObligation(
        int row,
        String type,
        int maximumMoves,
        CyclicTargetPair pair
) {
    private static final int RESIDUAL_RECORD = 3;
    private static final int FORMAT_VERSION = 1;
    private static final int KEY_HEADER_BYTES = 4;
    private static final int VALUE_HEADER_BYTES = 3;

    public ResidualObligation {
        Objects.requireNonNull(type);
        pair = Objects.requireNonNull(pair).canonicalRepresentative();
        final int typeBytes = type.getBytes(StandardCharsets.UTF_8).length;
        if (row <= 0 || row > 0xFFFF || type.isBlank() || typeBytes > 255
                || maximumMoves <= 0 || maximumMoves > 255
                || !pair.formsCyclicTargetPair()) {
            throw new IllegalArgumentException("Inconsistent residual obligation");
        }
        if (pair.getOmega().stream().anyMatch(cycle -> cycle.size() == 1)) {
            throw new IllegalArgumentException(
                    "Table 2 residual obligations must use a contracted fixed-point-free pair");
        }
    }

    public byte[] key() {
        final var pairKey = CertificateCodec.keyFor(pair);
        return ByteBuffer.allocate(KEY_HEADER_BYTES + pairKey.length)
                .put((byte) RESIDUAL_RECORD)
                .put((byte) FORMAT_VERSION)
                .putShort((short) row)
                .put(pairKey)
                .array();
    }

    public byte[] value() {
        final var typeBytes = type.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(VALUE_HEADER_BYTES + typeBytes.length)
                .put((byte) FORMAT_VERSION)
                .put((byte) typeBytes.length)
                .put(typeBytes)
                .put((byte) maximumMoves)
                .array();
    }

    public long checksumContribution() {
        final var checksum = new CRC32C();
        checksum.update(key());
        checksum.update(value());
        return checksum.getValue();
    }

    public static ResidualObligation decode(final byte[] key, final byte[] value) {
        if (key == null || key.length <= KEY_HEADER_BYTES
                || Byte.toUnsignedInt(key[0]) != RESIDUAL_RECORD
                || Byte.toUnsignedInt(key[1]) != FORMAT_VERSION
                || value == null || value.length < VALUE_HEADER_BYTES
                || Byte.toUnsignedInt(value[0]) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Malformed residual obligation");
        }
        final int row = Byte.toUnsignedInt(key[2]) << Byte.SIZE
                | Byte.toUnsignedInt(key[3]);
        final var pairKey = Arrays.copyOfRange(key, KEY_HEADER_BYTES, key.length);
        final int typeLength = Byte.toUnsignedInt(value[1]);
        if (typeLength == 0 || value.length != VALUE_HEADER_BYTES + typeLength) {
            throw new IllegalArgumentException("Malformed residual-obligation type length");
        }
        final var typeBytes = Arrays.copyOfRange(value, 2, 2 + typeLength);
        final var type = new String(typeBytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(typeBytes, type.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Malformed residual-obligation type encoding");
        }
        final int maximumMoves = Byte.toUnsignedInt(value[value.length - 1]);
        final var pair = CertificateCodec.pairForKey(
                pairKey, CertificateCodec.domainSizeForKey(pairKey));
        return new ResidualObligation(row, type, maximumMoves, pair);
    }
}

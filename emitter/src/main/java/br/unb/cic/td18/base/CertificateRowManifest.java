package br.unb.cic.td18.base;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Versioned completion record for one Table 2 search partition. */
public record CertificateRowManifest(
        int row,
        String type,
        int maximumMoves,
        long expectedRealizableClasses,
        long expectedRemainingObligations,
        long generatedClasses,
        long trivialClosureCount,
        long certificateCount,
        long residualCount,
        long checksum,
        boolean complete
) {
    private static final int MANIFEST_RECORD = 2;
    private static final int FORMAT_VERSION = 3;
    private static final int KEY_BYTES = 4;
    private static final int FIXED_VALUE_BYTES = 7 * Long.BYTES + 3;

    public CertificateRowManifest {
        Objects.requireNonNull(type);
        final int typeBytes = type.getBytes(StandardCharsets.UTF_8).length;
        if (row <= 0 || type.isBlank() || typeBytes > 255
                || maximumMoves <= 0 || maximumMoves > 255
                || expectedRealizableClasses < 0
                || expectedRemainingObligations < 0
                || expectedRemainingObligations > expectedRealizableClasses
                || generatedClasses < 0 || trivialClosureCount < 0
                || certificateCount < 0
                || residualCount < 0
                || trivialClosureCount + certificateCount + residualCount
                != generatedClasses
                || generatedClasses != expectedRemainingObligations
                || complete != (residualCount == 0)) {
            throw new IllegalArgumentException("Inconsistent certificate row manifest");
        }
    }

    public byte[] key() {
        if (row > 0xFFFF) {
            throw new IllegalArgumentException("Manifest row exceeds two bytes");
        }
        return new byte[]{
                MANIFEST_RECORD,
                FORMAT_VERSION,
                (byte) (row >>> Byte.SIZE),
                (byte) row
        };
    }

    public byte[] value() {
        final var typeBytes = type.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(FIXED_VALUE_BYTES + typeBytes.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) typeBytes.length)
                .put(typeBytes)
                .put((byte) maximumMoves)
                .putLong(expectedRealizableClasses)
                .putLong(expectedRemainingObligations)
                .putLong(generatedClasses)
                .putLong(trivialClosureCount)
                .putLong(certificateCount)
                .putLong(residualCount)
                .putLong(checksum)
                .put((byte) (complete ? 1 : 0))
                .array();
    }

    public static CertificateRowManifest decode(final byte[] key, final byte[] value) {
        if (key == null || key.length != KEY_BYTES
                || Byte.toUnsignedInt(key[0]) != MANIFEST_RECORD
                || Byte.toUnsignedInt(key[1]) != FORMAT_VERSION
                || value == null || value.length < FIXED_VALUE_BYTES) {
            throw new IllegalArgumentException("Malformed certificate row manifest");
        }
        final int row = Byte.toUnsignedInt(key[2]) << Byte.SIZE
                | Byte.toUnsignedInt(key[3]);
        final var buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        final int typeLength = Byte.toUnsignedInt(buffer.get());
        if (typeLength == 0 || value.length != FIXED_VALUE_BYTES + typeLength) {
            throw new IllegalArgumentException("Malformed manifest type length");
        }
        final var typeBytes = new byte[typeLength];
        buffer.get(typeBytes);
        final var type = new String(typeBytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(typeBytes, type.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Malformed manifest type encoding");
        }
        final int maximumMoves = Byte.toUnsignedInt(buffer.get());
        final long expectedRealizableClasses = buffer.getLong();
        final long expectedRemainingObligations = buffer.getLong();
        final long generatedClasses = buffer.getLong();
        final long trivialClosureCount = buffer.getLong();
        final long certificateCount = buffer.getLong();
        final long residualCount = buffer.getLong();
        final long checksum = buffer.getLong();
        final byte completion = buffer.get();
        if (completion != 0 && completion != 1) {
            throw new IllegalArgumentException("Malformed manifest completion flag");
        }
        return new CertificateRowManifest(row, type, maximumMoves,
                expectedRealizableClasses, expectedRemainingObligations,
                generatedClasses, trivialClosureCount, certificateCount,
                residualCount, checksum, completion == 1);
    }
}

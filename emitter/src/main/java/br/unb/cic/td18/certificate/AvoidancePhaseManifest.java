package br.unb.cic.td18.certificate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Versioned completion record for one screened avoidance-preimage phase. */
public record AvoidancePhaseManifest(
        int firstRow,
        int lastRow,
        long terminalResidualCount,
        long reconstructionApplications,
        long intermediatePrefixObligationCount,
        long propagatedPrefixMissCount,
        long rootSearchObligationCount,
        long trivialClosureCount,
        long certificateCount,
        long unresolvedCount,
        long checksum,
        boolean complete
) {
    private static final int RECORD_TYPE = 4;
    // Version 6 records the policy that stops every totally unoriented type.
    private static final int FORMAT_VERSION = 7;
    private static final int KEY_BYTES = 6;
    private static final int VALUE_BYTES = 9 * Long.BYTES + 1;

    public AvoidancePhaseManifest {
        if (firstRow <= 0 || firstRow > lastRow || lastRow > 0xFFFF
                || terminalResidualCount < 0
                || reconstructionApplications < 0
                || intermediatePrefixObligationCount < 0
                || propagatedPrefixMissCount < 0
                || rootSearchObligationCount < 0
                || trivialClosureCount < 0
                || certificateCount < 0
                || unresolvedCount < 0
                || propagatedPrefixMissCount > intermediatePrefixObligationCount
                || trivialClosureCount + certificateCount
                + propagatedPrefixMissCount + unresolvedCount
                != intermediatePrefixObligationCount + rootSearchObligationCount
                || complete != (unresolvedCount == 0)
                || (terminalResidualCount == 0
                && (reconstructionApplications != 0
                || intermediatePrefixObligationCount != 0
                || propagatedPrefixMissCount != 0
                || rootSearchObligationCount != 0
                || unresolvedCount != 0))) {
            throw new IllegalArgumentException(
                    "Inconsistent avoidance-phase manifest");
        }
    }

    public byte[] key() {
        return ByteBuffer.allocate(KEY_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) RECORD_TYPE)
                .put((byte) FORMAT_VERSION)
                .putShort((short) firstRow)
                .putShort((short) lastRow)
                .array();
    }

    public byte[] value() {
        return ByteBuffer.allocate(VALUE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(terminalResidualCount)
                .putLong(reconstructionApplications)
                .putLong(intermediatePrefixObligationCount)
                .putLong(propagatedPrefixMissCount)
                .putLong(rootSearchObligationCount)
                .putLong(trivialClosureCount)
                .putLong(certificateCount)
                .putLong(unresolvedCount)
                .putLong(checksum)
                .put((byte) (complete ? 1 : 0))
                .array();
    }

    /** Standalone sidecar encoding; manifests are not RocksDB records. */
    public byte[] encoded() {
        final var key = key();
        final var value = value();
        return ByteBuffer.allocate(key.length + value.length)
                .put(key).put(value).array();
    }

    public static AvoidancePhaseManifest decode(final byte[] encoded) {
        if (encoded == null || encoded.length <= KEY_BYTES) {
            throw new IllegalArgumentException("Malformed avoidance-phase manifest");
        }
        return decode(Arrays.copyOf(encoded, KEY_BYTES),
                Arrays.copyOfRange(encoded, KEY_BYTES, encoded.length));
    }

    public static AvoidancePhaseManifest decode(
            final byte[] key,
            final byte[] value
    ) {
        if (key == null || key.length != KEY_BYTES
                || Byte.toUnsignedInt(key[0]) != RECORD_TYPE
                || Byte.toUnsignedInt(key[1]) != FORMAT_VERSION
                || value == null || value.length != VALUE_BYTES) {
            throw new IllegalArgumentException(
                    "Malformed avoidance-phase manifest");
        }
        final var keyBuffer = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN);
        keyBuffer.position(2);
        final int firstRow = Short.toUnsignedInt(keyBuffer.getShort());
        final int lastRow = Short.toUnsignedInt(keyBuffer.getShort());
        final var buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        final long terminalResidualCount = buffer.getLong();
        final long reconstructionApplications = buffer.getLong();
        final long intermediatePrefixObligationCount = buffer.getLong();
        final long propagatedPrefixMissCount = buffer.getLong();
        final long rootSearchObligationCount = buffer.getLong();
        final long trivialClosureCount = buffer.getLong();
        final long certificateCount = buffer.getLong();
        final long unresolvedCount = buffer.getLong();
        final long checksum = buffer.getLong();
        final byte completion = buffer.get();
        if (completion != 0 && completion != 1) {
            throw new IllegalArgumentException(
                    "Malformed avoidance-phase completion flag");
        }
        return new AvoidancePhaseManifest(firstRow, lastRow,
                terminalResidualCount, reconstructionApplications,
                intermediatePrefixObligationCount, propagatedPrefixMissCount,
                rootSearchObligationCount, trivialClosureCount, certificateCount,
                unresolvedCount, checksum, completion == 1);
    }
}

package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

/** Compact on-disk encoding for normalized certificate pairs and witnesses. */
public final class CertificateCodec {
    private static final int CERTIFICATE_RECORD = 1;
    private static final int FORMAT_VERSION = 1;
    private static final int KEY_HEADER_BYTES = 3;
    private static final int VALUE_HEADER_BYTES = 2;
    private static final int SYMBOL_BITS = 5;
    private static final int SYMBOL_LIMIT = 1 << SYMBOL_BITS;
    private static final int TRIPLE_BITS = 3 * SYMBOL_BITS;

    private CertificateCodec() {
    }

    /**
     * Encodes the algebraic permutation of a normalized pair in one-line
     * notation. The image of each symbol occupies five bits, most significant
     * bit first.
     */
    public static byte[] keyFor(final CyclicTargetPair pair) {
        final var oneLine = pair.getOmega().getOneLineNotation();
        if (oneLine.length != pair.getBeta().size()) {
            throw new IllegalArgumentException(
                    "Certificate keys require an algebraic permutation on the full beta domain");
        }
        requireDomainSize(oneLine.length);

        final var payload = new byte[byteLength((long) SYMBOL_BITS * oneLine.length)];
        var bitOffset = 0;
        for (final var image : oneLine) {
            bitOffset = writeSymbol(payload, bitOffset, Byte.toUnsignedInt(image));
        }
        final var encoded = new byte[KEY_HEADER_BYTES + payload.length];
        encoded[0] = CERTIFICATE_RECORD;
        encoded[1] = FORMAT_VERSION;
        encoded[2] = (byte) oneLine.length;
        System.arraycopy(payload, 0, encoded, KEY_HEADER_BYTES, payload.length);
        return encoded;
    }

    /** Decodes the one-line algebraic permutation stored in a key. */
    public static byte[] decodeKeyOmega(final byte[] encoded, final int domainSize) {
        requireDomainSize(domainSize);
        if (encoded == null || encoded.length < KEY_HEADER_BYTES
                || Byte.toUnsignedInt(encoded[0]) != CERTIFICATE_RECORD
                || Byte.toUnsignedInt(encoded[1]) != FORMAT_VERSION
                || Byte.toUnsignedInt(encoded[2]) != domainSize) {
            throw new IllegalArgumentException(
                    "Unsupported certificate key header");
        }
        final var payload = Arrays.copyOfRange(encoded, KEY_HEADER_BYTES, encoded.length);
        requirePayload(payload, (long) SYMBOL_BITS * domainSize, "key");

        final var oneLine = new byte[domainSize];
        var bitOffset = 0;
        for (var symbol = 0; symbol < domainSize; symbol++) {
            oneLine[symbol] = (byte) readSymbol(payload, bitOffset);
            bitOffset += SYMBOL_BITS;
        }
        return oneLine;
    }

    /** Reconstructs the canonical cyclic-target representative stored by a key. */
    public static CyclicTargetPair pairForKey(
            final byte[] encoded,
            final int domainSize
    ) {
        final var images = decodeKeyOmega(encoded, domainSize);
        final var seenImages = new boolean[domainSize];
        for (final var image : images) {
            final var unsigned = Byte.toUnsignedInt(image);
            if (unsigned >= domainSize || seenImages[unsigned]) {
                throw new IllegalArgumentException(
                        "Certificate key does not encode a permutation");
            }
            seenImages[unsigned] = true;
        }

        final var omega = new MulticyclePermutation();
        final var visited = new boolean[domainSize];
        for (var start = 0; start < domainSize; start++) {
            if (visited[start]) {
                continue;
            }
            final var buffer = new int[domainSize];
            var length = 0;
            var current = start;
            do {
                if (visited[current]) {
                    throw new IllegalArgumentException(
                            "Certificate key contains a non-cycle component");
                }
                visited[current] = true;
                buffer[length++] = current;
                current = Byte.toUnsignedInt(images[current]);
            } while (current != start);
            final var symbols = new int[length];
            System.arraycopy(buffer, 0, symbols, 0, length);
            omega.add(Cycle.of(symbols));
        }

        final var betaSymbols = new int[domainSize];
        for (var symbol = 0; symbol < domainSize; symbol++) {
            betaSymbols[symbol] = symbol;
        }
        return CyclicTargetPair.ofOwned(omega, Cycle.of(betaSymbols));
    }

    /**
     * Encodes the ordered move triples as consecutive fifteen-bit fields.
     * Each field stores its three symbols in their given order.
     */
    public static byte[] valueFor(final List<Cycle> moves) {
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("A certificate must contain at least one move");
        }

        if (moves.size() > 255) {
            throw new IllegalArgumentException(
                    "A certificate cannot contain more than 255 moves");
        }
        final var payload = new byte[byteLength((long) TRIPLE_BITS * moves.size())];
        var bitOffset = 0;
        for (final var move : moves) {
            if (move.size() != 3) {
                throw new IllegalArgumentException("A certificate move must be a symbol triple");
            }
            bitOffset = writeSymbol(payload, bitOffset, move.get(0));
            bitOffset = writeSymbol(payload, bitOffset, move.get(1));
            bitOffset = writeSymbol(payload, bitOffset, move.get(2));
        }
        final var encoded = new byte[VALUE_HEADER_BYTES + payload.length];
        encoded[0] = FORMAT_VERSION;
        encoded[1] = (byte) moves.size();
        System.arraycopy(payload, 0, encoded, VALUE_HEADER_BYTES, payload.length);
        return encoded;
    }

    /** Decodes the concatenated fifteen-bit move triples stored in a value. */
    public static List<Cycle> decodeValue(final byte[] encoded) {
        if (encoded == null || encoded.length < VALUE_HEADER_BYTES
                || Byte.toUnsignedInt(encoded[0]) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported certificate value header");
        }
        final int moveCount = Byte.toUnsignedInt(encoded[1]);
        if (moveCount == 0) {
            throw new IllegalArgumentException("Truncated certificate value");
        }
        final var payload = Arrays.copyOfRange(encoded, VALUE_HEADER_BYTES, encoded.length);
        requirePayload(payload, (long) TRIPLE_BITS * moveCount, "value");

        final var moves = new ArrayList<Cycle>(moveCount);
        var bitOffset = 0;
        for (var index = 0; index < moveCount; index++) {
            final int first = readSymbol(payload, bitOffset);
            final int second = readSymbol(payload, bitOffset + SYMBOL_BITS);
            final int third = readSymbol(payload, bitOffset + 2 * SYMBOL_BITS);
            moves.add(Cycle.of(first, second, third));
            bitOffset += TRIPLE_BITS;
        }
        return List.copyOf(moves);
    }

    /** Deterministic contribution to a row-level order-independent checksum. */
    public static long checksumContribution(final CyclicTargetPair pair,
                                            final List<Cycle> moves) {
        final var checksum = new CRC32C();
        checksum.update(keyFor(pair));
        checksum.update(valueFor(moves));
        return checksum.getValue();
    }

    private static int writeSymbol(final byte[] encoded, final int bitOffset, final int symbol) {
        requireSymbol(symbol);
        for (var bit = 0; bit < SYMBOL_BITS; bit++) {
            final int valueBit = (symbol >>> (SYMBOL_BITS - 1 - bit)) & 1;
            final int outputBit = bitOffset + bit;
            encoded[outputBit / Byte.SIZE] |= (byte) (valueBit << (Byte.SIZE - 1 - outputBit % Byte.SIZE));
        }
        return bitOffset + SYMBOL_BITS;
    }

    private static int readSymbol(final byte[] encoded, final int bitOffset) {
        var symbol = 0;
        for (var bit = 0; bit < SYMBOL_BITS; bit++) {
            final int inputBit = bitOffset + bit;
            symbol = (symbol << 1)
                    | ((Byte.toUnsignedInt(encoded[inputBit / Byte.SIZE])
                    >>> (Byte.SIZE - 1 - inputBit % Byte.SIZE)) & 1);
        }
        return symbol;
    }

    private static void requirePayload(final byte[] encoded, final long payloadBits, final String kind) {
        if (encoded == null || encoded.length != byteLength(payloadBits)) {
            throw new IllegalArgumentException("Malformed certificate " + kind + " length");
        }
        final int paddingBits = (int) ((long) Byte.SIZE * encoded.length - payloadBits);
        if (paddingBits != 0 && (Byte.toUnsignedInt(encoded[encoded.length - 1])
                & ((1 << paddingBits) - 1)) != 0) {
            throw new IllegalArgumentException("Nonzero certificate " + kind + " padding");
        }
    }

    private static void requireDomainSize(final int size) {
        if (size <= 0 || size > SYMBOL_LIMIT) {
            throw new IllegalArgumentException(
                    "The compact certificate format supports domains of size 1.." + SYMBOL_LIMIT);
        }
    }

    private static void requireSymbol(final int symbol) {
        if (symbol < 0 || symbol >= SYMBOL_LIMIT) {
            throw new IllegalArgumentException(
                    "Certificate symbols must lie in 0.." + (SYMBOL_LIMIT - 1) + ": " + symbol);
        }
    }

    private static int byteLength(final long bitLength) {
        return Math.toIntExact((bitLength + Byte.SIZE - 1) / Byte.SIZE);
    }
}

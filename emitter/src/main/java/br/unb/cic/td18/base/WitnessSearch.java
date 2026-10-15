package br.unb.cic.td18.base;

import br.unb.cic.td18.ReductionTypeEnumerator;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.OneLinePermutation;

import java.util.List;
import java.util.Objects;

/** The only route from a sorting search result to a persisted certificate. */
public final class WitnessSearch {
    private static volatile AsyncCertificateWriter certificateWriter;

    private WitnessSearch() {
    }

    public static void installWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != null) {
            throw new IllegalStateException(
                    "A proof-scoped certificate writer is already installed");
        }
        certificateWriter = Objects.requireNonNull(writer);
    }

    public static SearchResult search(final CyclicTargetPair pair,
                                      final float minimumRate,
                                      final int maximumMoves) {
        final var omega = pair.getOmega();
        final var beta = pair.getBeta();
        final var betaBytes = new byte[beta.size()];
        for (var index = 0; index < beta.size(); index++) {
            betaBytes[index] = (byte) beta.get(index);
        }
        final byte[] omegaBytes = omega.getOneLineNotation();

        // This prefix is a proof closure, not a complete sorting witness. If
        // its contracted remainder closes by a known diameter, stop here.
        final var prefix = CommonOperations.searchForSorting(
                omegaBytes, betaBytes, maximumMoves);
        if (prefix.isPresent()) {
            validateCpuClosure(omegaBytes, betaBytes,
                    prefix.orElseThrow(), maximumMoves);
            return SearchResult.triviallySettled();
        }

        // CUDA is reached only when no qualifying CPU prefix exists.
        final var moves = GPUSortingSearch.getInstance().search(
                        betaBytes, new OneLinePermutation(omegaBytes),
                        omega.getNumberOfEvenCycles(),
                        minimumRate, maximumMoves, true)
                .stream()
                .map(move -> Cycle.of(move[0], move[1], move[2]))
                .toList();
        if (moves.isEmpty()) {
            return SearchResult.residual();
        }

        // Only a complete, independently replayed GPU witness crosses the
        // certificate persistence boundary.
        CertificateValidator.validate(pair, moves, maximumMoves);
        final var writer = certificateWriter;
        if (writer == null) {
            throw new IllegalStateException(
                    "Native witness succeeded without a proof-scoped certificate writer");
        }
        writer.enqueue(pair, moves);
        writer.throwIfFailed();
        return SearchResult.certificate(moves);
    }

    /** Replays the non-persisted prefix before classifying its proof closure. */
    static void validateCpuClosure(final byte[] omega,
                                   final byte[] beta,
                                   final List<int[]> prefix,
                                   final int maximumMoves) {
        if (omega.length != beta.length || prefix.isEmpty()
                || prefix.size() > CommonOperations.CPU_PREFIX_MAX_DEPTH) {
            throw new IllegalStateException(
                    "Malformed CPU prefix closure");
        }
        final int initialFixedPoints =
                CommonOperations.countFixedPoints(omega);
        final byte[] currentOmega = omega.clone();
        byte[] currentBeta = beta.clone();
        for (final var move : prefix) {
            if (move.length != 3) {
                throw new IllegalStateException(
                        "A CPU prefix move must contain three symbols");
            }
            final int a = move[0];
            final int b = move[1];
            final int c = move[2];
            if (a < 0 || a >= currentOmega.length
                    || b < 0 || b >= currentOmega.length
                    || c < 0 || c >= currentOmega.length
                    || a == b || a == c || b == c) {
                throw new IllegalStateException(
                        "CPU prefix move has invalid symbols");
            }
            final int i = indexOf(currentBeta, a);
            final int j = indexOf(currentBeta, b);
            final int k = indexOf(currentBeta, c);
            if (!(i < j && j < k)
                    || Byte.toUnsignedInt(currentOmega[a]) == a
                    || Byte.toUnsignedInt(currentOmega[b]) == b
                    || Byte.toUnsignedInt(currentOmega[c]) == c) {
                throw new IllegalStateException(
                        "CPU prefix move is not applicable");
            }

            final byte imageA = currentOmega[a];
            final byte imageB = currentOmega[b];
            final byte imageC = currentOmega[c];
            currentOmega[a] = imageC;
            currentOmega[b] = imageA;
            currentOmega[c] = imageB;
            currentBeta = CommonOperations.applyTransposition(
                    currentBeta, i, j, k);
        }

        final int fixedPoints =
                CommonOperations.countFixedPoints(currentOmega);
        final int createdFixedPoints = fixedPoints - initialFixedPoints;
        final int remainingSymbols = currentOmega.length - fixedPoints;
        final int remainingDiameter =
                ReductionTypeEnumerator.knownDiameter(remainingSymbols);
        if (createdFixedPoints
                < Math.ceil(CommonOperations.CPU_PREFIX_MIN_RATE
                * prefix.size())
                || remainingDiameter == Integer.MAX_VALUE
                || prefix.size() + remainingDiameter > maximumMoves) {
            throw new IllegalStateException(
                    "CPU prefix does not close the move budget");
        }
    }

    private static int indexOf(final byte[] permutation, final int symbol) {
        if (symbol < 0 || symbol >= permutation.length) {
            return -1;
        }
        for (var index = 0; index < permutation.length; index++) {
            if (Byte.toUnsignedInt(permutation[index]) == symbol) {
                return index;
            }
        }
        return -1;
    }

    public enum Disposition {
        TRIVIALLY_SETTLED,
        CERTIFICATE,
        RESIDUAL
    }

    public record SearchResult(Disposition disposition, List<Cycle> moves) {
        public SearchResult {
            Objects.requireNonNull(disposition);
            moves = List.copyOf(moves);
            if ((disposition == Disposition.CERTIFICATE) != !moves.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only a certificate result may contain moves");
            }
        }

        public static SearchResult triviallySettled() {
            return new SearchResult(Disposition.TRIVIALLY_SETTLED, List.of());
        }

        public static SearchResult certificate(final List<Cycle> moves) {
            return new SearchResult(Disposition.CERTIFICATE, moves);
        }

        public static SearchResult residual() {
            return new SearchResult(Disposition.RESIDUAL, List.of());
        }
    }
}

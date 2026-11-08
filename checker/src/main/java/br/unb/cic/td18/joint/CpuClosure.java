package br.unb.cic.td18.joint;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.search.CommonOperations;
import java.util.List;

/** Independently replays bounded CPU prefixes against established diameters. */
public final class CpuClosure {
    private CpuClosure() { }
    public static boolean closes(final CyclicTargetPair pair,
                              final int maximumMoves) {
        final byte[] omega = new byte[pair.getBeta().size()];
        for (int symbol = 0; symbol < omega.length; symbol++)
            omega[symbol] = (byte) pair.getOmega().image(symbol);
        final byte[] beta = betaBytes(pair);
        final var prefix = CommonOperations.searchForSorting(
                omega, beta, maximumMoves);
        if (prefix.isEmpty()) {
            return false;
        }
        validateCpuClosure(omega, beta, prefix.orElseThrow(), maximumMoves);
        return true;
    }

    /** Independently replays and classifies a non-certificate CPU prefix. */
    private static void validateCpuClosure(final byte[] initialOmega,
                                           final byte[] initialBeta,
                                           final List<int[]> prefix,
                                           final int maximumMoves) {
        if (initialOmega.length != initialBeta.length || prefix.isEmpty()
                || prefix.size() > 3) {
            throw new IllegalStateException("Malformed CPU prefix closure");
        }
        final byte[] omega = initialOmega.clone();
        byte[] beta = initialBeta.clone();
        final int initialFixedPoints = countFixedPoints(omega);
        for (final var move : prefix) {
            if (move.length != 3) {
                throw new IllegalStateException(
                        "A CPU prefix move must have three symbols");
            }
            final int first = move[0];
            final int second = move[1];
            final int third = move[2];
            final int firstPosition = indexOf(beta, first);
            final int secondPosition = indexOf(beta, second);
            final int thirdPosition = indexOf(beta, third);
            if (first < 0 || first >= omega.length
                    || second < 0 || second >= omega.length
                    || third < 0 || third >= omega.length
                    || first == second || first == third || second == third
                    || !(firstPosition < secondPosition
                    && secondPosition < thirdPosition)
                    || Byte.toUnsignedInt(omega[first]) == first
                    || Byte.toUnsignedInt(omega[second]) == second
                    || Byte.toUnsignedInt(omega[third]) == third) {
                throw new IllegalStateException(
                        "CPU prefix contains an inapplicable move");
            }

            final byte imageFirst = omega[first];
            final byte imageSecond = omega[second];
            final byte imageThird = omega[third];
            omega[first] = imageThird;
            omega[second] = imageFirst;
            omega[third] = imageSecond;
            beta = applyTransposition(beta, firstPosition, secondPosition,
                    thirdPosition);
        }

        final int fixedPoints = countFixedPoints(omega);
        final int gain = fixedPoints - initialFixedPoints;
        final int remainingSymbols = omega.length - fixedPoints;
        final int diameter = KnownDiameters.knownDiameter(
                remainingSymbols);
        if (5 * gain < 8 * prefix.size()
                || diameter == Integer.MAX_VALUE
                || prefix.size() + diameter > maximumMoves) {
            throw new IllegalStateException(
                    "CPU prefix does not satisfy the paper closure bound");
        }
    }

    private static int countFixedPoints(final byte[] omega) {
        var result = 0;
        for (var symbol = 0; symbol < omega.length; symbol++) {
            if (Byte.toUnsignedInt(omega[symbol]) == symbol) {
                result++;
            }
        }
        return result;
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

    private static byte[] applyTransposition(final byte[] beta,
                                             final int first,
                                             final int second,
                                             final int third) {
        final byte[] result = beta.clone();
        System.arraycopy(beta, second, result, first, third - second);
        System.arraycopy(beta, first, result, first + third - second,
                second - first);
        return result;
    }

    private static byte[] betaBytes(final CyclicTargetPair pair) {
        final var beta = pair.getBeta();
        final var result = new byte[beta.size()];
        for (var index = 0; index < beta.size(); index++) {
            result[index] = (byte) beta.get(index);
        }
        return result;
    }

}

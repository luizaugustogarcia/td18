package br.unb.cic.td18.joint;

import br.unb.cic.td18.model.CyclicTargetPair;
import lombok.val;

import java.util.Objects;

/**
 * The support powers whose cited clearing bounds fit the ten-move root budget.
 */
public final class SupportClearing {

    private SupportClearing() {
    }

    public static boolean closes(final CyclicTargetPair pair) {
        return closingLength(pair) != 0;
    }

    /**
     * Returns the least closing support length, or zero. These root closures
     * apply only on 19 symbols; a caller must contract fixed points first.
     */
    public static int closingLength(final CyclicTargetPair pair) {
        Objects.requireNonNull(pair);

        final int n = pair.getBeta().size();
        if (n != KnownDiameters.ROOT_SYMBOLS) {
            return 0;
        }

        final int[] beta = pair.getBeta().getSymbols();
        val position = new int[n];
        for (int i = 0; i < n; i++) {
            position[beta[i]] = i;
        }

        val omega = new int[n];
        for (int i = 0; i < n; i++) {
            omega[i] = position[pair.getOmega().image(beta[i])];
            if (omega[i] == i) {
                throw new IllegalArgumentException("Contract algebraic fixed points before testing root support clearing");
            }
        }

        return closingLengthCanonicalUnchecked(omega);
    }

    /**
     * The caller supplies a validated fixed-point-free root with canonical beta.
     */
    static int closingLengthCanonicalUnchecked(final int[] omega) {
        int seen = 0;
        int chosen = 0;
        val support = new int[omega.length];
        for (int first = 0; first < omega.length; first++) {
            if ((seen & (1 << first)) != 0) {
                continue;
            }

            int x = first;
            int length = 0;
            int cycleMask = 0;
            do {
                cycleMask |= 1 << x;
                length++;
                x = omega[x];
            } while (x != first);

            seen |= cycleMask;
            if (length != 7 && length != 9 && length != 11 && length != 13 || chosen != 0 && chosen <= length) {
                continue;
            }

            int index = 0;
            for (int symbol = first; symbol < omega.length; symbol++) {
                if ((cycleMask & (1 << symbol)) != 0) {
                    support[index++] = symbol;
                }
            }
            if (admittedPower(omega, support, length)) {
                chosen = length;
            }
        }

        return chosen;
    }

    static boolean admittedPower(final int[] omega, final int[] support, final int length) {
        return switch(length) {
            case 7 ->
                powerOnSupport(omega, support, length, 4);
            case 9 ->
                powerOnSupport(omega, support, length, 7);
            case 11 ->
                powerOnSupport(omega, support, length, 6);
            case 13 ->
                powerOnSupport(omega, support, length, 7)
                        || powerOnSupport(omega, support, length, 10)
                        || powerOnSupport(omega, support, length, 11);
            default ->
                false;
        };
    }

    private static boolean powerOnSupport(final int[] omega, final int[] support, final int length, final int exponent) {
        for (int rank = 0; rank < length; rank++) {
            if (omega[support[rank]] != support[(rank + exponent) % length]) {
                return false;
            }
        }

        return true;
    }
}

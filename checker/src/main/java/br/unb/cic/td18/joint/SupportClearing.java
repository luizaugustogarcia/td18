package br.unb.cic.td18.joint;

import br.unb.cic.td18.model.CyclicTargetPair;

/** Budget-valid local support-clearing powers listed in the paper. */
public final class SupportClearing {
    private SupportClearing() { }
    public static boolean closes(final CyclicTargetPair pair) { return closingLength(pair) != 0; }
    public static int closingLength(final CyclicTargetPair pair) {
        if (pair.getBeta().size() != 19) return 0;
        if (JointMoves.fixed(JointMoves.images(pair)) != 0)
            throw new IllegalArgumentException("Support-clearing roots require 19 nonfixed symbols");
        int smallest = Integer.MAX_VALUE;
        for (final var cycle : pair.getOmega()) {
            final int length = cycle.size();
            final int[] powers = switch (length) {
                case 7 -> new int[]{4};
                case 9 -> new int[]{7};
                case 11 -> new int[]{6};
                case 13 -> new int[]{7, 10, 11};
                default -> new int[0];
            };
            if (powers.length == 0) continue;
            final int[] support = new int[length];
            int at = 0;
            final boolean[] member = new boolean[19];
            for (final int symbol : cycle.getSymbols()) member[symbol] = true;
            for (final int symbol : pair.getBeta().getSymbols())
                if (member[symbol]) support[at++] = symbol;
            for (final int power : powers) {
                boolean match = true;
                for (int i = 0; i < length; i++)
                    if (pair.getOmega().image(support[i]) != support[(i + power) % length]) { match = false; break; }
                if (match) {
                    if ((length + 1) / 2 + KnownDiameters.knownDiameter(19 - length) > 10)
                        throw new IllegalStateException("Unsupported clearing budget");
                    smallest = Math.min(smallest, length);
                }
            }
        }
        return smallest == Integer.MAX_VALUE ? 0 : smallest;
    }
}

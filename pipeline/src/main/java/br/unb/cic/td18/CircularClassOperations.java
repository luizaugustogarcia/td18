package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.PermutationGroups;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Symbol deletion and insertion on cyclic-target pairs.
 *
 * <p>A deletion removes the same symbol from the current cycle {@code beta}
 * and target cycle {@code rho}, then relabels the remaining domain in its
 * natural order. Its raw inverse inserts the new largest symbol independently
 * into one gap of each cycle, producing {@code N^2} children from an
 * {@code N}-symbol parent.
 */
public final class CircularClassOperations {

    private CircularClassOperations() {
    }

    /** One raw insertion, with gaps identified by predecessor positions. */
    public record Insertion(
            int betaGap,
            int rhoGap,
            CyclicTargetPair child
    ) {
        public Insertion {
            Objects.requireNonNull(child);
        }
    }

    /** Removes {@code symbol} from both full cycles and closes the labels. */
    public static CyclicTargetPair removeSymbol(
            final CyclicTargetPair pair,
            final int symbol
    ) {
        Objects.requireNonNull(pair);
        final var domainSize = pair.getBeta().size();
        if (domainSize <= 1) {
            throw new IllegalArgumentException(
                    "Cannot remove the only symbol of a cyclic-target pair");
        }
        if (symbol < 0 || symbol >= domainSize) {
            throw new IllegalArgumentException(
                    "Removed symbol must belong to 0.." + (domainSize - 1));
        }

        final var beta = Cycle.of(deleteAndRelabel(
                pair.getBeta().getSymbols(), symbol));
        final var rho = Cycle.of(deleteAndRelabel(
                pair.getRho().getSymbols(), symbol));
        return pairFromCycles(beta, rho);
    }

    /** Returns the {@code N} circular removals, one for each domain symbol. */
    public static List<CyclicTargetPair> removals(final CyclicTargetPair pair) {
        Objects.requireNonNull(pair);
        final var result = new ArrayList<CyclicTargetPair>(pair.getBeta().size());
        for (var symbol = 0; symbol < pair.getBeta().size(); symbol++) {
            result.add(removeSymbol(pair, symbol));
        }
        return List.copyOf(result);
    }

    /**
     * Returns all {@code N^2} raw children. Gap {@code i} is immediately after
     * position {@code i} in the stored cyclic order.
     */
    public static List<Insertion> insertions(final CyclicTargetPair pair) {
        Objects.requireNonNull(pair);
        final var domainSize = pair.getBeta().size();
        final var result = new ArrayList<Insertion>(domainSize * domainSize);
        for (var betaGap = 0; betaGap < domainSize; betaGap++) {
            final var beta = Cycle.of(insertAfter(
                    pair.getBeta().getSymbols(), betaGap, domainSize));
            for (var rhoGap = 0; rhoGap < domainSize; rhoGap++) {
                final var rho = Cycle.of(insertAfter(
                        pair.getRho().getSymbols(), rhoGap, domainSize));
                result.add(new Insertion(
                        betaGap, rhoGap, pairFromCycles(beta, rho)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the {@code N} raw insertions that make the new symbol a fixed
     * point.  Such an insertion must use gaps with the same predecessor
     * symbol in {@code beta} and {@code rho}.
     */
    public static List<Insertion> fixedPointInsertions(
            final CyclicTargetPair pair
    ) {
        Objects.requireNonNull(pair);
        final var domainSize = pair.getBeta().size();
        final var result = new ArrayList<Insertion>(domainSize);
        for (var predecessor = 0;
             predecessor < domainSize;
             predecessor++) {
            final var betaGap = pair.getBeta().indexOf(predecessor);
            final var rhoGap = pair.getRho().indexOf(predecessor);
            final var beta = Cycle.of(insertAfter(
                    pair.getBeta().getSymbols(), betaGap, domainSize));
            final var rho = Cycle.of(insertAfter(
                    pair.getRho().getSymbols(), rhoGap, domainSize));
            result.add(new Insertion(
                    betaGap, rhoGap, pairFromCycles(beta, rho)));
        }
        return List.copyOf(result);
    }

    /**
     * Canonicalizes the raw insertions at the child order and returns one
     * representative per extended-toric class in deterministic key order.
     */
    public static List<CyclicTargetPair> childClasses(final CyclicTargetPair pair) {
        final Map<ExtendedToricClassKey, CyclicTargetPair> byKey =
                new LinkedHashMap<>();
        for (final var insertion : insertions(pair)) {
            final var child = insertion.child();
            byKey.putIfAbsent(child.extendedToricClassKey(), child);
        }
        return byKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(
                        ExtendedToricClassKey::twistedBracelet)))
                .map(entry -> entry.getValue().canonicalRepresentative())
                .toList();
    }

    /** Returns the class-key multiset of all circular removals. */
    public static Map<ExtendedToricClassKey, Long> removalClassMultiset(
            final CyclicTargetPair pair
    ) {
        return removals(pair).stream()
                .map(CyclicTargetPair::extendedToricClassKey)
                .collect(Collectors.groupingBy(
                        Function.identity(), Collectors.counting()));
    }

    /** Counts algebraic fixed points, including ones omitted from cycle form. */
    public static int algebraicFixedPointCount(final CyclicTargetPair pair) {
        Objects.requireNonNull(pair);
        var count = 0;
        for (var symbol = 0; symbol < pair.getBeta().size(); symbol++) {
            if (pair.getOmega().image(symbol) == symbol) {
                count++;
            }
        }
        return count;
    }

    private static CyclicTargetPair pairFromCycles(
            final Cycle beta,
            final Cycle rho
    ) {
        final var omega = PermutationGroups.computeProduct(
                true, beta.size(), rho, beta.getInverse());
        return CyclicTargetPair.ofKnownTargetOwned(omega, beta, rho);
    }

    private static int[] deleteAndRelabel(
            final int[] cycle,
            final int removedSymbol
    ) {
        final var result = new int[cycle.length - 1];
        var target = 0;
        for (final var symbol : cycle) {
            if (symbol == removedSymbol) {
                continue;
            }
            result[target++] = symbol > removedSymbol ? symbol - 1 : symbol;
        }
        if (target != result.length) {
            throw new IllegalArgumentException(
                    "Removed symbol does not occur exactly once in the cycle");
        }
        return result;
    }

    private static int[] insertAfter(
            final int[] cycle,
            final int predecessorPosition,
            final int newSymbol
    ) {
        if (predecessorPosition < 0 || predecessorPosition >= cycle.length) {
            throw new IllegalArgumentException("Gap position lies outside the cycle");
        }
        final var result = new int[cycle.length + 1];
        final var prefixLength = predecessorPosition + 1;
        System.arraycopy(cycle, 0, result, 0, prefixLength);
        result[prefixLength] = newSymbol;
        System.arraycopy(cycle, prefixLength, result, prefixLength + 1,
                cycle.length - prefixLength);
        return result;
    }
}

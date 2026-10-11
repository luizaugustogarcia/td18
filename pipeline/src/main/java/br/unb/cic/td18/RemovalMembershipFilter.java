package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Batches the same deletion round across children, rejecting misses immediately. */
public final class RemovalMembershipFilter {
    private RemovalMembershipFilter() {
    }

    public static void forEachQualified(
            final List<CyclicTargetPair> children,
            final ClassStore parents,
            final int batchSize,
            final Consumer<CyclicTargetPair> consumer
    ) {
        Objects.requireNonNull(children);
        Objects.requireNonNull(parents);
        Objects.requireNonNull(consumer);
        if (batchSize < 1) {
            throw new IllegalArgumentException("Lookup batch size must be positive");
        }
        final var childDomain = parents.domainSize() + 1;
        for (final var child : children) {
            if (child.getBeta().size() != childDomain) {
                throw new IllegalArgumentException("Child domain does not match parent store");
            }
        }
        for (var offset = 0; offset < children.size();) {
            final var end = offset + Math.min(batchSize, children.size() - offset);
            var survivors = new ArrayList<>(children.subList(offset, end));
            for (var symbol = 0; symbol < childDomain && !survivors.isEmpty(); symbol++) {
                final var removals = new ArrayList<CyclicTargetPair>(survivors.size());
                for (final var child : survivors) {
                    removals.add(CircularClassOperations.removeSymbol(child, symbol));
                }
                final boolean[] present;
                if (batchSize == 1) {
                    present = new boolean[]{parents.contains(removals.getFirst())};
                } else {
                    present = parents.containsMany(removals);
                }
                final var next = new ArrayList<CyclicTargetPair>(survivors.size());
                for (var index = 0; index < survivors.size(); index++) {
                    if (present[index]) {
                        next.add(survivors.get(index));
                    }
                }
                survivors = next;
            }
            survivors.forEach(consumer);
            offset = end;
        }
    }
}

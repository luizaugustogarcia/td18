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

    /**
     * Emits each qualified canonical child only from its unique owner parent.
     * The owner is the lexicographically least canonical removal class. Since
     * insertion class sets are representative-invariant, that parent always
     * regenerates the child even when its raw insertion is not canonical.
     */
    public static void forEachQualifiedOwnedBy(
            final CyclicTargetPair parent,
            final List<CyclicTargetPair> canonicalChildren,
            final ClassStore parents,
            final int batchSize,
            final Consumer<CyclicTargetPair> consumer
    ) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(canonicalChildren);
        Objects.requireNonNull(parents);
        Objects.requireNonNull(consumer);
        if (batchSize < 1) {
            throw new IllegalArgumentException("Lookup batch size must be positive");
        }
        if (parent.getBeta().size() != parents.domainSize()) {
            throw new IllegalArgumentException("Parent domain does not match store");
        }
        final var childDomain = parents.domainSize() + 1;
        final var parentKey = EncodedClassKey.of(parent);
        for (var offset = 0; offset < canonicalChildren.size();) {
            final var end = offset + Math.min(
                    batchSize, canonicalChildren.size() - offset);
            var survivors = new ArrayList<OwnedCandidate>(end - offset);
            for (var index = offset; index < end; index++) {
                final var child = canonicalChildren.get(index)
                        .canonicalRepresentative();
                if (child.getBeta().size() != childDomain) {
                    throw new IllegalArgumentException(
                            "Child domain does not match parent store");
                }
                survivors.add(new OwnedCandidate(child));
            }

            for (var symbol = 0;
                 symbol < childDomain && !survivors.isEmpty();
                 symbol++) {
                final var removalKeys = new ArrayList<EncodedClassKey>(
                        survivors.size());
                for (final var candidate : survivors) {
                    final var removal = CircularClassOperations.removeSymbol(
                            candidate.child, symbol);
                    removalKeys.add(EncodedClassKey.of(removal));
                }
                final var present = parents.containsEncoded(removalKeys);
                final var next = new ArrayList<OwnedCandidate>(survivors.size());
                for (var index = 0; index < survivors.size(); index++) {
                    if (present[index]) {
                        final var candidate = survivors.get(index);
                        candidate.includeRemoval(removalKeys.get(index));
                        next.add(candidate);
                    }
                }
                survivors = next;
            }
            for (final var candidate : survivors) {
                if (parentKey.equals(candidate.minimumRemoval)) {
                    consumer.accept(candidate.child);
                }
            }
            offset = end;
        }
    }

    private static final class OwnedCandidate {
        private final CyclicTargetPair child;
        private EncodedClassKey minimumRemoval;

        private OwnedCandidate(final CyclicTargetPair child) {
            this.child = child;
        }

        private void includeRemoval(final EncodedClassKey key) {
            if (minimumRemoval == null || key.compareTo(minimumRemoval) < 0) {
                minimumRemoval = key;
            }
        }
    }
}

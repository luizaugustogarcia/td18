package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemovalMembershipFilterTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void batchesMatchIndependentScalarFilteringIncludingPartialBatchesAndDuplicates(
            final int domain
    ) {
        final var identityCycle = Cycle.of(IntStream.range(0, domain).toArray());
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(), identityCycle);
        final var children = new ArrayList<CyclicTargetPair>();
        for (var repeat = 0; repeat < 57; repeat++) {
            CircularClassOperations.insertions(parent)
                    .forEach(insertion -> children.add(insertion.child()));
        }
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("parents"), domain, true)) {
            store.add(parent);
            final var expected = children.stream()
                    .filter(child -> CircularClassOperations.removals(child).stream()
                            .allMatch(store::contains))
                    .toList();
            assertFalse(expected.isEmpty());
            assertTrue(expected.size() < children.size());
            for (final var batchSize : List.of(1, 2, 9, 500)) {
                final var actual = new ArrayList<CyclicTargetPair>();
                RemovalMembershipFilter.forEachQualified(children, store, batchSize, actual::add);
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void emptyParentStoreRejectsAllChildrenAndRejectsInvalidBatchSizes() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        final var children = CircularClassOperations.insertions(parent).stream()
                .map(CircularClassOperations.Insertion::child).toList();
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("empty"), 3, true)) {
            final var actual = new ArrayList<CyclicTargetPair>();
            RemovalMembershipFilter.forEachQualified(children, store, 500, actual::add);
            assertTrue(actual.isEmpty());
            assertThrows(IllegalArgumentException.class, () ->
                    RemovalMembershipFilter.forEachQualified(children, store, 0, actual::add));
        }
    }
}

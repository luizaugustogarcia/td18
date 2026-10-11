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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            RemovalMembershipFilter.forEachQualifiedInsertionOwnedBy(
                    parent, store, 500, actual::add);
            assertTrue(actual.isEmpty());
            assertThrows(IllegalArgumentException.class, () ->
                    RemovalMembershipFilter.forEachQualified(children, store, 0, actual::add));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void canonicalOwnershipEmitsEveryQualifiedChildExactlyOnce(final int order) {
        final Map<br.unb.cic.tdp.base.ExtendedToricClassKey, CyclicTargetPair>
                parentClasses = new LinkedHashMap<>();
        ordinaryPairs(order).forEach(pair -> parentClasses.putIfAbsent(
                pair.extendedToricClassKey(), pair.canonicalRepresentative()));
        final Map<br.unb.cic.tdp.base.ExtendedToricClassKey, Integer> expected =
                new LinkedHashMap<>();
        parentClasses.values().forEach(parent ->
                CircularClassOperations.childClasses(parent).forEach(child ->
                        expected.put(child.extendedToricClassKey(), 1)));

        try (final var store = new ClassStore(
                temporaryDirectory.resolve("owner-parents"), order + 1, true)) {
            store.putAll(parentClasses.values().stream().toList());
            for (final var batchSize : List.of(1, 2, 9, 500)) {
                final Map<br.unb.cic.tdp.base.ExtendedToricClassKey, Integer>
                        actual = new LinkedHashMap<>();
                final var ownedWithoutRawCanonicalInsertion = new boolean[1];
                parentClasses.values().forEach(parent ->
                        RemovalMembershipFilter.forEachQualifiedOwnedBy(
                                parent,
                                CircularClassOperations.childClasses(parent),
                                store,
                                batchSize,
                                child -> {
                                    actual.merge(child.extendedToricClassKey(),
                                            1, Integer::sum);
                                    ownedWithoutRawCanonicalInsertion[0] |=
                                            CircularClassOperations.insertions(parent)
                                                    .stream()
                                                    .noneMatch(insertion ->
                                                            insertion.child().equals(child));
                                }));
                assertEquals(expected, actual);
                assertTrue(ownedWithoutRawCanonicalInsertion[0]);
                final Map<br.unb.cic.tdp.base.ExtendedToricClassKey, Integer>
                        fromInsertions = new LinkedHashMap<>();
                parentClasses.values().forEach(parent ->
                        RemovalMembershipFilter.forEachQualifiedInsertionOwnedBy(
                                parent, store, batchSize,
                                child -> fromInsertions.merge(
                                        child.extendedToricClassKey(), 1, Integer::sum)));
                assertEquals(expected, fromInsertions);
            }
        }
    }

    @Test
    void ownedBatchesMatchScalarOwnershipWithMissingParentClasses() {
        final Map<br.unb.cic.tdp.base.ExtendedToricClassKey, CyclicTargetPair>
                parentClasses = new LinkedHashMap<>();
        ordinaryPairs(4).forEach(pair -> parentClasses.putIfAbsent(
                pair.extendedToricClassKey(), pair.canonicalRepresentative()));
        final var orderedParents = parentClasses.values().stream()
                .sorted((left, right) -> EncodedClassKey.of(left)
                        .compareTo(EncodedClassKey.of(right)))
                .toList();
        final var selectedParents = orderedParents.subList(
                0, orderedParents.size() - 1);
        var sawMissingRemoval = false;
        var sawQualifiedNonOwner = false;
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("partial-owner-parents"), 5, true)) {
            store.putAll(selectedParents);
            // Include noncanonical representatives to exercise the different
            // deletion orders used by raw and canonical child representatives.
            for (final var parent : ordinaryPairs(4).stream()
                    .filter(store::contains).toList()) {
                final var parentKey = EncodedClassKey.of(parent);
                final var children = CircularClassOperations.childClasses(parent);
                final var expected = new ArrayList<
                        br.unb.cic.tdp.base.ExtendedToricClassKey>();
                for (final var child : children) {
                    final var removals = CircularClassOperations.removals(child);
                    if (!removals.stream().allMatch(store::contains)) {
                        sawMissingRemoval = true;
                        continue;
                    }
                    final var owner = removals.stream()
                            .map(EncodedClassKey::of)
                            .min(EncodedClassKey::compareTo)
                            .orElseThrow();
                    if (parentKey.equals(owner)) {
                        expected.add(child.extendedToricClassKey());
                    } else {
                        sawQualifiedNonOwner = true;
                    }
                }
                for (final var batchSize : List.of(1, 2, 9, 500)) {
                    final var actual = new ArrayList<
                            br.unb.cic.tdp.base.ExtendedToricClassKey>();
                    RemovalMembershipFilter.forEachQualifiedOwnedBy(
                            parent, children, store, batchSize,
                            child -> actual.add(child.extendedToricClassKey()));
                    assertEquals(expected, actual);
                    final var fromInsertions = new ArrayList<
                            br.unb.cic.tdp.base.ExtendedToricClassKey>();
                    RemovalMembershipFilter.forEachQualifiedInsertionOwnedBy(
                            parent, store, batchSize,
                            child -> fromInsertions.add(child.extendedToricClassKey()));
                    assertEquals(expected, fromInsertions);
                }
            }
        }
        assertTrue(sawMissingRemoval);
        assertTrue(sawQualifiedNonOwner);
    }

    private static List<CyclicTargetPair> ordinaryPairs(final int order) {
        final var values = new int[order];
        for (var index = 0; index < order; index++) {
            values[index] = index + 1;
        }
        final var result = new ArrayList<CyclicTargetPair>();
        permute(values, 0, result);
        return result;
    }

    private static void permute(
            final int[] values,
            final int index,
            final List<CyclicTargetPair> result
    ) {
        if (index == values.length) {
            final var betaSymbols = new int[values.length + 1];
            System.arraycopy(values, 0, betaSymbols, 1, values.length);
            final var rhoSymbols = new int[betaSymbols.length];
            Arrays.setAll(rhoSymbols, symbol -> symbol);
            final var beta = Cycle.of(betaSymbols);
            final var rho = Cycle.of(rhoSymbols);
            final var omega = br.unb.cic.tdp.permutation.PermutationGroups
                    .computeProduct(true, beta.size(), rho, beta.getInverse());
            result.add(CyclicTargetPair.ofKnownTargetOwned(omega, beta, rho));
            return;
        }
        for (var candidate = index; candidate < values.length; candidate++) {
            final var temporary = values[index];
            values[index] = values[candidate];
            values[candidate] = temporary;
            permute(values, index + 1, result);
            values[candidate] = values[index];
            values[index] = temporary;
        }
    }
}

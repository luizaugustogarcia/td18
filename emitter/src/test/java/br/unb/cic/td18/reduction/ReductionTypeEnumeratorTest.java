package br.unb.cic.td18.reduction;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression guard for the TD(18) two-reduction enumeration. */
class ReductionTypeEnumeratorTest {
    @Test
    void restrictedOddPairsRetainAllQualifyingChildTypesAndThe292TypeInventory() {
        final var expectedChildren = Map.of(
                "13O,5O", Set.of("[13, 3]", "[11, 5]", "[9, 5, 3]", "[7, 5, 5]"),
                "11O,7O", Set.of("[11, 5]", "[11, 3, 3]", "[9, 7]", "[7, 7, 3]", "[7, 5, 5]"),
                "9O,9O", Set.of("[9, 7]", "[9, 5, 3]"));
        final var obligations = ReductionTypeEnumerator.enumerate();
        final int[] rows = {267, 270, 274};
        final long[] counts = {391L, 321L, 1_172L};
        for (var index = 0; index < rows.length; index++) {
            final var obligation = obligations.get(rows[index] - 1);
            final var options = ReductionTypeEnumerator.reductionOptions(
                    obligation.lengths(), obligation.oriented(), obligation.movesUsed());
            assertEquals(expectedChildren.get(obligation.typeKey()), options.stream()
                    .map(option -> Arrays.toString(option.childLengths())).collect(Collectors.toSet()));
            assertTrue(options.stream().allMatch(option -> option.kind()
                    == ReductionTypeEnumerator.ReductionKind.ORIENTED_ODD));
            assertTrue(ReductionTypeEnumerator.mayHaveNoAvailableReduction(
                    obligation.lengths(), obligation.oriented(), 1));
            assertEquals(counts[index], ReductionTypeEnumerator.remainingClassObligations(
                    obligation, Long.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () ->
                    ReductionTypeEnumerator.remainingClassObligations(obligation, 0L));
        }
        assertEquals(292, obligations.size());
        assertEquals(Map.of(0, 59, 1, 45, 2, 188), depthDistribution());
    }

    @Test
    void commandValidationChecksTheSourceTable() throws java.io.IOException {
        ReductionTypeEnumerator.main(new String[]{"--validate"});
    }


    private static TreeMap<Integer, Integer> depthDistribution() {
        final var depth = new TreeMap<Integer, Integer>();
        for (final var obligation : ReductionTypeEnumerator.enumerate()) {
            depth.merge(obligation.movesUsed(), 1, Integer::sum);
        }
        return depth;
    }

    @Test
    void rootEnumerationMatchesTheClosedFormCount() {
        final var partitions = Partitions.generateValidPartitions(
                ReductionTypeEnumerator.ROOT_SYMBOLS);
        assertEquals(53, partitions.size());
        var annotatedRoots = 0;
        for (final var partition : partitions) {
            final int[] descending = partition.clone();
            Arrays.sort(descending);
            for (int i = 0, j = descending.length - 1; i < j; i++, j--) {
                final int tmp = descending[i];
                descending[i] = descending[j];
                descending[j] = tmp;
            }
            annotatedRoots += AnnotatedPartitions.of(descending).size();
        }
        assertEquals(360, annotatedRoots);
    }

    @Test
    void enumeratorYieldsThe292ReductionNoteTypes() {
        assertEquals(292, ReductionTypeEnumerator.distinctStatePairs());
        final var obligations = ReductionTypeEnumerator.enumerate();
        assertEquals(292, obligations.size());
        assertEquals(new TreeMap<>(java.util.Map.of(0, 59, 1, 45, 2, 188)),
                depthDistribution());
    }

    @Test
    void everyReachableStateKeepsTheThreeNormBelowItsBudget() {
        for (final var obligation : ReductionTypeEnumerator.enumerate()) {
            assertTrue(obligation.threeNorm() < obligation.budget(),
                    () -> "kappa < b must hold at " + obligation.typeKey());
        }
    }

    @Test
    void totallyUnorientedTypesStopAtEveryDepthIncludingWithEvenPairs() {
        for (var symbols = 14; symbols <= 19; symbols++) {
            for (final var lengths : Partitions.generateValidPartitions(symbols)) {
                final var orientations = new boolean[lengths.length];
                for (var depth = 0; depth <= 2; depth++) {
                    assertTrue(ReductionTypeEnumerator.reductionOptions(lengths, orientations, depth).isEmpty());
                    assertTrue(ReductionTypeEnumerator.selectedChildPartitions(lengths, orientations, depth).isEmpty());
                    assertTrue(ReductionTypeEnumerator.mayHaveNoAvailableReduction(lengths, orientations, depth));
                }
            }
        }
        assertEquals(53, ReductionTypeEnumerator.enumerate().stream()
                .filter(o -> o.movesUsed() == 0 && ReductionTypeEnumerator.isTotallyUnoriented(o.oriented())).count());
        assertTrue(ReductionTypeEnumerator.enumerate().stream().anyMatch(o ->
                o.typeKey().equals("5U,4U,4U,3U,3U") && o.movesUsed() == 0 && o.budget() == 10));
        assertTrue(ReductionTypeEnumerator.enumerate().stream().anyMatch(o ->
                o.typeKey().equals("6U,6U,3U,3U") && o.movesUsed() == 1 && o.budget() == 9));
    }

    @Test
    void checkedTd16ComputationReducesTheClassObligations() {
        final var obligations = ReductionTypeEnumerator.enumerate();
        assertEquals(Map.of(
                        ReductionTypeEnumerator.Td16Disposition.NOT_COVERED, 188L,
                        ReductionTypeEnumerator.Td16Disposition.SETTLED, 102L,
                        ReductionTypeEnumerator.Td16Disposition.PARTIAL, 2L),
                obligations.stream().collect(Collectors.groupingBy(
                        ReductionTypeEnumerator::td16Disposition,
                        Collectors.counting())));
        assertEquals(52, obligations.stream()
                .filter(ReductionTypeEnumerator::td16DirectClosure)
                .count());
        assertEquals(52, obligations.stream()
                .filter(ReductionTypeEnumerator::td16SuccessorSearch)
                .count());

        final var partial = obligations.stream()
                .filter(obligation -> ReductionTypeEnumerator.td16Disposition(obligation)
                        == ReductionTypeEnumerator.Td16Disposition.PARTIAL)
                .collect(Collectors.toMap(
                        ReductionTypeEnumerator.Obligation::typeKey,
                        ReductionTypeEnumerator::td16ResidualClasses));
        assertEquals(Map.of("11O,5O", 495, "9O,7O", 1_541), partial);
    }
    @Test
    void offersEvenPairWhenOrientedFiveHasNoQualifyingMove() {
        final int[] lengths = {5, 4, 4, 3, 3};
        final boolean[] orientations = {true, false, false, false, false};
        assertTrue(ReductionTypeEnumerator.allowsTransition(lengths, orientations, 0,
                ReductionTypeEnumerator.ReductionKind.ORIENTED_ODD, new int[]{4, 4, 3, 3, 3}));
        assertTrue(ReductionTypeEnumerator.allowsTransition(lengths, orientations, 0,
                ReductionTypeEnumerator.ReductionKind.EVEN_PAIR, new int[]{7, 5, 3, 3}));
        assertTrue(!ReductionTypeEnumerator.mayHaveNoAvailableReduction(lengths, orientations, 0));
        assertTrue(ReductionTypeEnumerator.enumerate().stream().noneMatch(o ->
                o.typeKey().equals("5O,4U,4U,3U,3U")));
    }

    @Test
    void retainsOneMoveTypesWhenClearingAlternativesExceedTheBudget() {
        final int[] lengths = {7, 5, 3, 3};
        final boolean[] orientations = {true, true, false, false};
        assertTrue(ReductionTypeEnumerator.mayHaveNoAvailableReduction(lengths, orientations, 1));
        assertEquals(java.util.Set.of(5, 7), ReductionTypeEnumerator.reductionOptions(lengths, orientations, 1)
                .stream().map(ReductionTypeEnumerator.ReductionOption::cycleLength)
                .collect(Collectors.toSet()));
        assertTrue(ReductionTypeEnumerator.enumerate().stream().anyMatch(o ->
                o.typeKey().equals("7O,5O,3U,3U") && o.movesUsed() == 1 && o.budget() == 9));
    }

    @Test
    void orientedThreeStillClearsBeforeConditionalBranches() {
        final int[] lengths = {5, 4, 4, 3, 3};
        final boolean[] orientations = {true, false, true, false, true};
        final var options = ReductionTypeEnumerator.reductionOptions(lengths, orientations, 0);
        assertEquals(1, options.size());
        assertEquals(ReductionTypeEnumerator.ReductionKind.ORIENTED_THREE, options.getFirst().kind());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{5, 4, 4, 3}, options.getFirst().childLengths());
        assertTrue(!ReductionTypeEnumerator.mayHaveNoAvailableReduction(lengths, orientations, 0));
    }

    @Test
    void orientedFiveCounterexampleHasNoTwoFixedPointTransposition() {
        final var beta = br.unb.cic.td18.permutation.Cycle.of(0, 1, 2, 3, 4);
        final var omega = new br.unb.cic.td18.permutation.MulticyclePermutation(java.util.List.of(
                br.unb.cic.td18.permutation.Cycle.of(0, 3, 1, 4, 2)));
        final var pair = br.unb.cic.td18.model.CyclicTargetPair.of(omega, beta);
        assertTrue(br.unb.cic.td18.search.CommonOperations.isOriented(beta, omega.asNCycle()));
        for (var a = 0; a < 3; a++) for (var b = a + 1; b < 4; b++) for (var c = b + 1; c < 5; c++) {
            final var move = br.unb.cic.td18.permutation.Cycle.of(a, b, c);
            final var childOmega = br.unb.cic.td18.permutation.PermutationGroups.computeProduct(
                    pair.getOmega(), move.getInverse());
            final var parts = childOmega.stream().mapToInt(cycle -> cycle.size()).sorted().toArray();
            assertTrue(!Arrays.equals(new int[]{1, 1, 3}, parts));
        }
    }

}

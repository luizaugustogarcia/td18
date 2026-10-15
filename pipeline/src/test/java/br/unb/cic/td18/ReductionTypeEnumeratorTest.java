package br.unb.cic.td18;

import br.unb.cic.tdp.Partitions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression guard for the TD(18) two-reduction enumeration. */
class ReductionTypeEnumeratorTest {

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
    void enumeratorYieldsThe204ReductionNoteTypes() {
        assertEquals(204, ReductionTypeEnumerator.distinctStatePairs());
        final var obligations = ReductionTypeEnumerator.enumerate();
        assertEquals(204, obligations.size());
        assertEquals(new TreeMap<>(java.util.Map.of(0, 8, 1, 8, 2, 188)),
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
    void checkedTd16ComputationReducesTheClassObligations() {
        final var obligations = ReductionTypeEnumerator.enumerate();
        assertEquals(Map.of(
                        ReductionTypeEnumerator.Td16Disposition.NOT_COVERED, 100L,
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
}

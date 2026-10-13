package br.unb.cic.td18;

import br.unb.cic.tdp.Partitions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the recursive reduction enumeration. The baseline policy
 * must reproduce the published 106-type count exactly before the risk-taking
 * two-reduction policy is trusted; the current policy must yield the 204 types
 * recorded in the reduction note.
 */
class ReductionTypeEnumeratorTest {

    private static TreeMap<Integer, Integer> depthDistribution(
            final ReductionTypeEnumerator.Policy policy) {
        final var depth = new TreeMap<Integer, Integer>();
        for (final var obligation : ReductionTypeEnumerator.enumerate(policy)) {
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
    void baselinePolicyReproducesThePublished106TypeCount() {
        final var baseline = ReductionTypeEnumerator.Policy.BASELINE;
        assertEquals(107, ReductionTypeEnumerator.distinctStatePairs(baseline));
        final var obligations = ReductionTypeEnumerator.enumerate(baseline);
        assertEquals(106, obligations.size());
        assertEquals(new TreeMap<>(java.util.Map.of(0, 10, 1, 21, 2, 18, 3, 57)),
                depthDistribution(baseline));
    }

    @Test
    void currentPolicyYieldsThe204ReductionNoteTypes() {
        final var current = ReductionTypeEnumerator.Policy.CURRENT;
        assertEquals(204, ReductionTypeEnumerator.distinctStatePairs(current));
        final var obligations = ReductionTypeEnumerator.enumerate(current);
        assertEquals(204, obligations.size());
        assertEquals(new TreeMap<>(java.util.Map.of(0, 8, 1, 8, 2, 188)),
                depthDistribution(current));
    }

    @Test
    void everyReachableStateKeepsTheThreeNormBelowItsBudget() {
        for (final var obligation : ReductionTypeEnumerator.enumerate(
                ReductionTypeEnumerator.Policy.CURRENT)) {
            assertTrue(obligation.threeNorm() < obligation.budget(),
                    () -> "kappa < b must hold at " + obligation.typeKey());
        }
    }
}

package br.unb.cic.td18.search;

import br.unb.cic.td18.permutation.Cycle;

import java.util.Arrays;

import lombok.val;
import org.junit.jupiter.api.Test;

import static br.unb.cic.td18.search.CommonOperations.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonOperationsTest {
    final private Cycle alpha = Cycle.of("0 2 7");
    final private Cycle beta = Cycle.of("0 5 4 3 8 7 6 2 1");

    @Test
    void testApplyTransposition() {
        assertEquals(Cycle.of("0 1 2 3 4 5 6"), applyTranspositionOptimized(Cycle.of("0 4 5 6 1 2 3"), Cycle.of("0 4 1")));
    }

    @Test
    void testCycleIndex() {
        val beta = Cycle.of("0 5 4 3 2 1");
        val c0 = Cycle.of("0 2 4");
        val c1 = Cycle.of("1 3 5");

        val index = CommonOperations.cycleIndex(Arrays.asList(c0, c1), beta);
        assertArrayEquals(new Cycle[]{c0, c1, c0, c1, c0, c1}, index);
    }

    @Test
    void testAreSymbolsInCyclicOrder() {
        assertTrue(areSymbolsInCyclicOrder(beta.getInverse(), alpha.getSymbols()));
        assertFalse(areSymbolsInCyclicOrder(beta, alpha.getSymbols()));
    }

    @Test
    void testIsOriented() {
        assertTrue(isOriented(beta, alpha.getInverse()));
        assertFalse(isOriented(beta, alpha));
    }

    @Test
    void cpuPrefixRequiresBothRateAndKnownDiameterBudget() {
        final byte[] omega = {1, 2, 0, 4, 5, 3};
        final byte[] beta = {0, 1, 2, 3, 4, 5};

        final var prefix = searchForSorting(omega, beta, 2);

        assertTrue(prefix.isPresent());
        assertEquals(1, prefix.orElseThrow().size());
        assertArrayEquals(new int[]{0, 1, 2},
                prefix.orElseThrow().getFirst());
        assertArrayEquals(new byte[]{1, 2, 0, 4, 5, 3}, omega);
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5}, beta);
        assertTrue(searchForSorting(omega, beta, 1).isEmpty());
    }

    @Test
    void cpuPrefixTreatsExistingFixedPointsAsDataNotGain() {
        assertTrue(searchForSorting(
                new byte[]{0, 2, 3, 1},
                new byte[]{0, 1, 2, 3}, 1).isPresent());
        assertTrue(searchForSorting(
                new byte[]{0, 1, 2, 3},
                new byte[]{0, 1, 2, 3}, 3).isEmpty());
    }

    @Test
    void cpuPrefixCanAccumulateItsRateAtDepthThree() {
        final var prefix = searchForSorting(
                new byte[]{1, 4, 3, 0, 2},
                new byte[]{0, 1, 2, 3, 4}, 3);

        assertTrue(prefix.isPresent());
        assertEquals(3, prefix.orElseThrow().size());
        assertArrayEquals(new int[]{0, 1, 2},
                prefix.orElseThrow().get(0));
        assertArrayEquals(new int[]{0, 2, 3},
                prefix.orElseThrow().get(1));
        assertArrayEquals(new int[]{2, 3, 4},
                prefix.orElseThrow().get(2));
    }

    @Test
    void byteTranspositionMatchesTheCyclicBlockMove() {
        assertArrayEquals(new byte[]{0, 4, 5, 6, 1, 2, 3},
                applyTransposition(
                        new byte[]{0, 1, 2, 3, 4, 5, 6},
                        1, 4, 7));
    }
}

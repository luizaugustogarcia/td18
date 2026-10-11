package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.PermutationGroups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class KnownDiametersTest {
    @ParameterizedTest
    @CsvSource({"13,6,7", "14,7,8", "14,6,8", "15,7,8", "16,8,9", "17,8,9", "18,9,10"})
    void boundedFailureNeedsNoAdditionalWitnessSearch(
            final int domain, final int excluded, final int diameter
    ) {
        final var depths = new ArrayList<Integer>();
        final var pair = reversePair(domain);
        assertEquals(diameter, KnownDiameters.forDomain(domain));
        assertTrue(KnownDiameters.liesBeyondDepth(pair, excluded, (canonical, depth) -> {
            assertSame(canonical, canonical.canonicalRepresentative());
            depths.add(depth);
            return Optional.empty();
        }));
        assertEquals(List.of(excluded), depths);
    }

    @ParameterizedTest
    @CsvSource({"13,6", "14,7", "14,6", "15,7", "16,8", "17,8", "18,9"})
    void boundedSuccessRejectsTheClassWithoutAnAdditionalSearch(
            final int domain, final int excluded
    ) {
        final var depths = new ArrayList<Integer>();
        assertFalse(KnownDiameters.liesBeyondDepth(reversePair(domain), excluded,
                (pair, depth) -> {
                    depths.add(depth);
                    return Optional.of(List.of(Cycle.of(0, 1, 2)));
                }));
        assertEquals(List.of(excluded), depths);
    }

    @Test
    void cannotAssumeTheTargetOrder18UpperBound() {
        assertThrows(IllegalArgumentException.class, () -> KnownDiameters.forDomain(19));
        assertThrows(IllegalArgumentException.class, () ->
                KnownDiameters.liesBeyondDepth(reversePair(19), 9,
                        (pair, depth) -> { throw new AssertionError("Must not search"); }));
        assertThrows(IllegalArgumentException.class, () ->
                KnownDiameters.requireExactDepth(15, 6, 8));
    }

    static CyclicTargetPair reversePair(final int domain) {
        final var symbols = new int[domain];
        for (var index = 1; index < domain; index++) {
            symbols[index] = domain - index;
        }
        final var beta = Cycle.of(symbols);
        final var rho = Cycle.of(IntStream.range(0, domain).toArray());
        return CyclicTargetPair.ofKnownTargetOwned(PermutationGroups.computeProduct(
                true, domain, rho, beta.getInverse()), beta, rho);
    }
}

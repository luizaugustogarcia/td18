package br.unb.cic.td18;

import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.permutation.Cycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientedNineteenCycleEmitterTest {
    private static final List<List<Cycle>> WITNESSES = List.of(
            cycles(new int[][]{
                    {1, 5, 12}, {3, 12, 17}, {12, 13, 4},
                    {11, 2, 12}, {10, 1, 18}, {7, 17, 15},
                    {0, 10, 9}, {7, 6, 16}, {14, 5, 11},
                    {3, 8, 18}
            }),
            cycles(new int[][]{
                    {0, 13, 18}, {4, 8, 18}, {3, 12, 6},
                    {4, 10, 7}, {1, 11, 8}, {5, 2, 18},
                    {16, 6, 9}, {15, 12, 0}, {13, 16, 6},
                    {17, 14, 11}
            }));

    @Test
    void emitsOneValidatedCertificatePerConfiguredSurvivor() {
        final var calls = new AtomicInteger();
        final var pairs = OrientedNineteenCycleEmitter.survivorPairs();

        final var result = OrientedNineteenCycleEmitter.emit(
                (pair, minimumRate, maximumMoves) -> {
                    final int index = pairs.indexOf(pair);
                    assertTrue(index >= 0);
                    assertEquals(1.8f, minimumRate);
                    assertEquals(10, maximumMoves);
                    calls.incrementAndGet();
                    return WitnessSearch.SearchResult.certificate(
                            WITNESSES.get(index));
                });

        assertEquals(2, calls.get());
        assertEquals(2, result.survivors());
        assertEquals(2, result.certificates());
        assertEquals(5_945_871_690L, result.checksum());
    }

    @Test
    void rejectsANativeMiss() {
        final var failure = assertThrows(IllegalStateException.class,
                () -> OrientedNineteenCycleEmitter.emit(
                        (pair, rate, moves) ->
                                WitnessSearch.SearchResult.residual()));

        assertTrue(failure.getMessage().contains(
                "was not emitted as a complete sorting certificate"));
    }

    private static List<Cycle> cycles(final int[][] moves) {
        return java.util.Arrays.stream(moves)
                .map(move -> Cycle.of(move.clone()))
                .toList();
    }
}

package br.unb.cic.td18;

import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.permutation.Cycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitnessEmitterTest {
    @Test
    void sendsEveryGeneratedClassThroughTheSearchBoundary() {
        final var partition = new int[]{3, 3};
        final var oriented = new boolean[]{true, true};
        final long expected = DirectTwistedBraceletGenerator.countRepresentatives(
                partition, oriented);
        assertTrue(expected > 0);
        final var entry = new CountTD18Bracelets.TableEntry(
                1, partition, oriented, 6, 13, 2, 8, 2, expected, expected);
        final var calls = new AtomicInteger();
        final var observedRate = new AtomicReference<Float>();

        final var result = WitnessEmitter.emitEntry(entry, (pair, rate, moves) -> {
            calls.incrementAndGet();
            observedRate.compareAndSet(null, rate);
            assertEquals(8, moves);
            return WitnessSearch.SearchResult.certificate(
                    List.of(Cycle.of(0, 1, 2)));
        });

        assertEquals(expected, calls.get());
        assertEquals(expected, result.generated());
        assertEquals(0, result.trivialClosures());
        assertEquals(expected, result.certificates());
        assertEquals(0, result.residuals());
        assertNull(result.firstResidual());
        assertEquals(2.0f, observedRate.get());
    }

    @Test
    void keepsNativeMissesAsResidualObligations() {
        final var partition = new int[]{3, 3};
        final var oriented = new boolean[]{true, true};
        final long expected = DirectTwistedBraceletGenerator.countRepresentatives(
                partition, oriented);
        final var entry = new CountTD18Bracelets.TableEntry(
                1, partition, oriented, 6, 13, 2, 8, 2, expected, expected);

        final var result = WitnessEmitter.emitEntry(
                entry, (pair, rate, moves) ->
                        WitnessSearch.SearchResult.residual());

        assertEquals(expected, result.generated());
        assertEquals(0, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertEquals(expected, result.residuals());
        assertNotNull(result.firstResidual());
    }

    @Test
    void countsCpuDiameterClosuresWithoutCertificates() {
        final var partition = new int[]{3, 3};
        final var oriented = new boolean[]{true, true};
        final long expected = DirectTwistedBraceletGenerator.countRepresentatives(
                partition, oriented);
        final var entry = new CountTD18Bracelets.TableEntry(
                1, partition, oriented, 6, 13, 2, 8, 2, expected, expected);

        final var result = WitnessEmitter.emitEntry(
                entry, (pair, rate, moves) ->
                        WitnessSearch.SearchResult.triviallySettled());

        assertEquals(expected, result.generated());
        assertEquals(expected, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertEquals(0, result.residuals());
        assertNull(result.firstResidual());
    }
}

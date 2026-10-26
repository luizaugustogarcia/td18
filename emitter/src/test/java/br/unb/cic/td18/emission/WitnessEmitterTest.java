package br.unb.cic.td18.emission;

import br.unb.cic.td18.avoidance.ResidualObligation;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.search.WitnessSearch;
import br.unb.cic.td18.table.TableEntry;

import java.util.List;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitnessEmitterTest {
    @Test
    void sendsEveryRestrictedSeedDirectlyToRootLiftingWithoutNineMoveSearch() {
        final int[] rows = {267, 270, 274};
        final int[][] types = {{13, 5}, {11, 7}, {9, 9}};
        final long[] fullCounts = {284_235_604_416L, 243_117_785_088L, 115_561_900_003L};
        final long[] restrictedCounts = {391L, 321L, 1_172L};
        for (var index = 0; index < rows.length; index++) {
            final var entry = new TableEntry(rows[index], types[index], new boolean[]{true, true},
                    18, 1, 1, 9, 8, fullCounts[index], restrictedCounts[index]);
            final var persisted = new ConcurrentLinkedQueue<ResidualObligation>();
            final var result = WitnessEmitter.emitEntry(entry, (pair, rate, budget) -> {
                throw new AssertionError("A restricted seed must bypass nine-move search");
            }, persisted::add);
            assertEquals(restrictedCounts[index], result.generated());
            assertEquals(restrictedCounts[index], result.residuals());
            assertEquals(0L, result.trivialClosures());
            assertEquals(0L, result.certificates());
            final var keys = new HashSet<ExtendedToricClassKey>();
            for (final var obligation : persisted) {
                assertEquals(entry.number(), obligation.row());
                assertEquals(9, obligation.maximumMoves());
                assertTrue(RestrictedOddPair.contains(obligation.pair()));
                assertTrue(keys.add(obligation.pair().extendedToricClassKey()));
            }
            assertEquals(restrictedCounts[index], keys.size());
            assertEquals(persisted.stream().mapToLong(ResidualObligation::checksumContribution).sum(),
                    result.checksum());
        }
    }

    @Test
    void rejectsMissingTruncatedAndObsoleteWholeTypeCountsForRestrictedSeeds() {
        for (final long count : new long[]{0L, 390L, 392L, 284_235_604_416L}) {
            final var entry = new TableEntry(267, new int[]{13, 5}, new boolean[]{true, true},
                    18, 1, 1, 9, 8, 284_235_604_416L, count);
            assertThrows(IllegalStateException.class, () -> WitnessEmitter.emitEntry(entry,
                    (pair, rate, budget) -> { throw new AssertionError("Invalid count reached search"); },
                    obligation -> { throw new AssertionError("Invalid count persisted a seed"); }));
        }
    }

    @Test
    void skipsRowsWithNoRemainingClassObligations() {
        final var entry = new TableEntry(
                126, new int[]{13, 3}, new boolean[]{true, true},
                16, 3, 2, 8, 7, 1_197_556_864L, 0L);
        final var calls = new AtomicInteger();

        final var result = WitnessEmitter.emitEntry(entry,
                (pair, rate, moves) -> {
                    calls.incrementAndGet();
                    return WitnessSearch.SearchResult.residual();
                }, obligation -> { });

        assertEquals(0, calls.get());
        assertEquals(0, result.generated());
        assertEquals(0, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertEquals(0, result.residuals());
        assertEquals(0, result.checksum());
        assertNull(result.firstResidual());
    }

    @Test
    void rejectsRowsWhoseObligationIsNeitherZeroNorTheFullClassCount() {
        final var entry = new TableEntry(
                130, new int[]{11, 5}, new boolean[]{true, true},
                16, 3, 2, 8, 7, 1_384_025_296L, 495L);

        final var error = assertThrows(IllegalStateException.class,
                () -> WitnessEmitter.emitEntry(entry,
                        (pair, rate, moves) ->
                                WitnessSearch.SearchResult.residual(),
                        obligation -> { }));

        assertTrue(error.getMessage().contains(
                "only Q=0 or Q=R"));
    }

    @Test
    void sendsEveryGeneratedClassThroughTheSearchBoundary() {
        final var partition = new int[]{3, 3};
        final var oriented = new boolean[]{true, true};
        final long expected = DirectTwistedBraceletGenerator.countRepresentatives(
                partition, oriented);
        assertTrue(expected > 0);
        final var entry = new TableEntry(
                1, partition, oriented, 6, 13, 2, 8, 2, expected, expected);
        final var calls = new AtomicInteger();
        final var observedRate = new AtomicReference<Float>();

        final var result = WitnessEmitter.emitEntry(entry,
                (pair, rate, moves) -> {
                    calls.incrementAndGet();
                    observedRate.compareAndSet(null, rate);
                    assertEquals(8, moves);
                    return WitnessSearch.SearchResult.certificate(
                            List.of(Cycle.of(0, 1, 2)));
                }, obligation -> { });

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
        final var entry = new TableEntry(
                1, partition, oriented, 6, 13, 2, 8, 2, expected, expected);
        final var persisted = new ConcurrentLinkedQueue<ResidualObligation>();

        final var result = WitnessEmitter.emitEntry(
                entry, (pair, rate, moves) ->
                        WitnessSearch.SearchResult.residual(), persisted::add);

        assertEquals(expected, result.generated());
        assertEquals(0, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertEquals(expected, result.residuals());
        assertNotNull(result.firstResidual());
        assertEquals(expected, persisted.size());
        assertTrue(persisted.stream().allMatch(obligation ->
                obligation.row() == entry.number()
                        && obligation.maximumMoves() == entry.remainingMoves()));
        assertEquals(persisted.stream()
                        .mapToLong(ResidualObligation::checksumContribution)
                        .sum(),
                result.checksum());
    }

    @Test
    void countsCpuDiameterClosuresWithoutCertificates() {
        final var partition = new int[]{3, 3};
        final var oriented = new boolean[]{true, true};
        final long expected = DirectTwistedBraceletGenerator.countRepresentatives(
                partition, oriented);
        final var entry = new TableEntry(
                1, partition, oriented, 6, 13, 2, 8, 2, expected, expected);

        final var result = WitnessEmitter.emitEntry(
                entry, (pair, rate, moves) ->
                        WitnessSearch.SearchResult.triviallySettled(),
                obligation -> { });

        assertEquals(expected, result.generated());
        assertEquals(expected, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertEquals(0, result.residuals());
        assertNull(result.firstResidual());
    }
}

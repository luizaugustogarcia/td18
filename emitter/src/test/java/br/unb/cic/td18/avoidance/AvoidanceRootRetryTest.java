package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.search.WitnessSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AvoidanceRootRetryTest {
    @TempDir Path directory;

    private static List<CyclicTargetPair> roots() {
        final var pairs = new ArrayList<CyclicTargetPair>();
        DirectTwistedBraceletGenerator.generateRepresentatives(
                new int[]{9, 7, 3}, new boolean[3], pairs::add);
        return pairs.subList(0, 2);
    }

    @Test
    void retriesOnlyPreviouslyUnresolvedRoots() {
        final var pairs = roots();
        try (final var store = new AvoidancePreimageStore(directory.resolve("retry"))) {
            store.putPairs(10, pairs);
            store.putHardPairs(10, List.of(pairs.get(1)));
            final var previous = new AvoidancePreimageStore.SearchManifest(10,
                    store.inventory(10), 1, 0, store.hardInventory(10), 123);
            store.recordSearch(previous);
            final var result = ObstructionAvoidanceRunner.retryUnresolvedRoots(store, previous,
                    miss -> fail("No unresolved result expected"),
                    (pair, rate, budget, fullDomain) -> {
                        assertEquals(pairs.get(1), pair);
                        assertTrue(fullDomain);
                        assertEquals(10, budget);
                        return WitnessSearch.SearchResult.triviallySettled();
                    }, () -> { });
            assertEquals(2, result.trivialClosures());
            assertEquals(0, result.residuals().count());
            assertEquals(123, result.checksum());
            assertEquals(result, store.searchManifest(10).orElseThrow());
            assertEquals(2, store.inventory(10).count());
        }
    }

    @Test
    void interruptedRetryInvalidatesItsOldCompletionBeforeChangingTheFrontier() {
        final var pairs = roots();
        final var path = directory.resolve("crashed-retry");
        try (final var store = new AvoidancePreimageStore(path)) {
            store.putPairs(10, pairs);
            store.putHardPairs(10, pairs);
            final var previous = new AvoidancePreimageStore.SearchManifest(10,
                    store.inventory(10), 0, 0, store.hardInventory(10), 123);
            store.recordSearch(previous);
            assertThrows(IllegalStateException.class, () ->
                    ObstructionAvoidanceRunner.retryUnresolvedRoots(store, previous,
                            miss -> { }, (pair, rate, budget, fullDomain) -> {
                                throw new IllegalStateException("simulated interrupted search");
                            }, () -> { }));
            assertTrue(store.searchManifest(10).isEmpty());
        }
        try (final var store = new AvoidancePreimageStore(path)) {
            assertEquals(2, store.inventory(10).count());
            assertTrue(store.searchManifest(10).isEmpty());
            store.resetHard(10, List.of());
            final var result = ObstructionAvoidanceRunner.searchWave(store, 10, 2, true,
                    miss -> fail("No miss expected"), (pair, rate, budget, fullDomain) ->
                            WitnessSearch.SearchResult.triviallySettled());
            assertEquals(2, result.trivialClosures());
        }
    }
}

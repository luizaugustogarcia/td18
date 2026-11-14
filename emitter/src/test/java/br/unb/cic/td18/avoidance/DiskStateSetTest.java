package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiskStateSetTest {
    @Test void concurrentGeneratedBatchesMatchPerKeyInsertionAndReopen(@TempDir Path directory) throws Exception {
        var parents = new ArrayList<CyclicTargetPair>();
        ErikssonInverseReconstruction.preimages(ActiveErikssonAvoidanceTest.pair(5), 1, parents::add);
        assertFalse(parents.isEmpty());
        var lastPair = ActiveErikssonAvoidanceTest.pair(3);
        String oldBuffer = System.getProperty("td18.reconstructionBufferKeys");
        System.setProperty("td18.reconstructionBufferKeys", "3");
        Path batchedPath = directory.resolve("batched");
        List<String> expected;
        try {
            try (var baseline = new DiskStateSet(directory.resolve("baseline"));
                 var batched = new DiskStateSet(batchedPath)) {
                for (int budget = 8; budget <= 10; budget++) {
                    for (int i = 0; i < 777; i++) baseline.add(parents.get(i % parents.size()), budget);
                    baseline.add(lastPair, budget);
                }
                expected = keys(baseline);
                var start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(6)) {
                    var futures = new ArrayList<Future<?>>();
                    for (int task = 0; task < 6; task++) {
                        int budget = 8 + task % 3;
                        futures.add(workers.submit(() -> {
                            try {
                                if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("Workers did not start");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(failure);
                            }
                            batched.addGenerated(budget, output -> {
                                for (int i = 0; i < 777; i++) {
                                    var pair = parents.get(i % parents.size());
                                    output.accept(i % 2 == 0 ? pair : pair.canonicalRepresentative());
                                }
                                // A distinct final key detects a dropped partial insertion batch.
                                output.accept(lastPair);
                            });
                        }));
                    }
                    start.countDown();
                    for (var future : futures) future.get(30, TimeUnit.SECONDS);
                }
                assertEquals(expected.size(), batched.seal());
                assertEquals(expected, keys(batched));
                assertThrows(IllegalStateException.class, () -> batched.addGenerated(8, output -> fail("Sealed set accepted a generator")));
            }
            try (var reopened = DiskStateSet.openSealed(batchedPath, expected.size())) {
                assertEquals(expected, keys(reopened));
                assertThrows(IllegalStateException.class, () -> reopened.addGenerated(8, output -> fail("Read-only set accepted a generator")));
            }
        } finally {
            restore("td18.reconstructionBufferKeys", oldBuffer);
        }
    }

    @Test void failedGenerationCannotSealPartialOutput(@TempDir Path directory) {
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        String oldBuffer = System.getProperty("td18.reconstructionBufferKeys");
        System.setProperty("td18.reconstructionBufferKeys", "1");
        try {
            for (int emitted : new int[]{7, 300}) {
                try (var states = new DiskStateSet(directory.resolve("failed-" + emitted))) {
                    var failure = new IllegalArgumentException("Inverse generation failed");
                    assertSame(failure, assertThrows(IllegalArgumentException.class, () -> states.addGenerated(8, output -> {
                        for (int i = 0; i < emitted; i++) output.accept(pair);
                        throw failure;
                    })));
                    assertSame(failure, assertThrows(IllegalStateException.class, states::seal).getCause());
                    assertThrows(IllegalStateException.class, () -> states.forEach(state -> fail("Incomplete wave was scanned")));
                    assertThrows(IllegalStateException.class, () -> states.add(pair, 8));
                    assertThrows(IllegalStateException.class, () -> states.addGenerated(8, output -> fail("Failed set accepted more generation")));
                }
            }
        } finally {
            restore("td18.reconstructionBufferKeys", oldBuffer);
        }
    }

    @Test void emptyGenerationAndInvalidBudgetsPreserveSetRules(@TempDir Path directory) {
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            states.addGenerated(8, output -> { });
            assertThrows(IllegalArgumentException.class, () -> states.addGenerated(0, output -> fail("Invalid budget accepted")));
            assertThrows(IllegalArgumentException.class, () -> states.addGenerated(11, output -> fail("Invalid budget accepted")));
            states.addGenerated(9, output -> output.accept(pair));
            assertEquals(1, states.seal());
        }
    }

    private static List<String> keys(DiskStateSet states) {
        var result = new ArrayList<String>();
        states.forEach(state -> result.add(HexFormat.of().formatHex(DiskStateSet.encode(state))));
        return result;
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }

    @Test void deduplicatesClassAndBudgetBeforeSealedScan(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            states.add(pair,8); states.add(pair.canonicalRepresentative(),8); states.add(pair,9);
            assertEquals(2,states.seal());
            assertThrows(IllegalStateException.class, () -> states.add(pair,10));
            var result = new ArrayList<DiskStateSet.State>(); states.forEach(result::add);
            assertEquals(2,result.size()); assertEquals(8,result.getFirst().maximumMoves());
            assertEquals(9,result.getLast().maximumMoves());
            assertEquals(pair.extendedToricClassKey(),result.getFirst().pair().extendedToricClassKey());
        }
        assertThrows(IllegalStateException.class, () -> new DiskStateSet(directory.resolve("states")));
    }

    @Test void reopenedCheckpointsRejectMissingRecordsAndStayReadOnly(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        Path path = directory.resolve("states");
        try (var states = new DiskStateSet(path)) {
            states.add(pair, 8);
            states.add(pair, 9);
            assertEquals(2, states.seal());
        }
        assertThrows(IllegalStateException.class, () -> DiskStateSet.openSealed(path, 3));
        assertThrows(IllegalStateException.class, () -> DiskStateSet.openSealed(directory.resolve("missing"), 0));
        try (var states = DiskStateSet.openSealed(path, 2)) {
            assertThrows(IllegalStateException.class, () -> states.add(pair, 10));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            states.forEachConcurrently(2, state -> count.incrementAndGet());
            assertEquals(2, count.get());
            assertThrows(IllegalStateException.class, () -> states.forEachConcurrently(2, state -> {
                throw new IllegalArgumentException("search failed");
            }));
        }
    }

    @Test void bulkLookupSkipsHitsAndKeepsCrossBufferDuplicatesAndThePartialBatch(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        String oldBatch = System.getProperty("td18.certificateLookupBatchSize");
        String oldBuffer = System.getProperty("td18.reconstructionBufferKeys");
        System.setProperty("td18.certificateLookupBatchSize","3");
        System.setProperty("td18.reconstructionBufferKeys","2");
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            for (int budget=1;budget<=7;budget++) states.add(pair,budget);
            for (int budget=1;budget<=7;budget++) states.add(pair,budget);
            assertEquals(7,states.seal());
            var sizes = new ArrayList<Integer>();
            var hits = new java.util.concurrent.atomic.AtomicInteger();
            var misses = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
            states.forEachConcurrently(2,batch->{
                sizes.add(batch.size());var found=new boolean[batch.size()];
                for(int i=0;i<found.length;i++)found[i]=batch.get(i).maximumMoves()%2==0;
                return found;
            },state->hits.incrementAndGet(),state->{
                assertEquals(1,state.maximumMoves()%2);
                assertTrue(misses.add(state.maximumMoves()),"A duplicate reached sorting");
            });
            assertEquals(java.util.List.of(3,3,1),sizes);
            assertEquals(3,hits.get());assertEquals(java.util.Set.of(1,3,5,7),misses);
            assertThrows(IllegalStateException.class,()->states.forEachConcurrently(2,
                    batch->new boolean[batch.size()-1],state->fail("Incomplete lookup cannot skip work"),
                    state->fail("Incomplete lookup cannot dispatch work")));
        } finally {
            if(oldBatch==null)System.clearProperty("td18.certificateLookupBatchSize");else System.setProperty("td18.certificateLookupBatchSize",oldBatch);
            if(oldBuffer==null)System.clearProperty("td18.reconstructionBufferKeys");else System.setProperty("td18.reconstructionBufferKeys",oldBuffer);
        }
    }
}

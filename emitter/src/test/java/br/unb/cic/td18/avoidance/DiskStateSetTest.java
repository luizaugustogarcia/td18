package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiskStateSetTest {
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
}

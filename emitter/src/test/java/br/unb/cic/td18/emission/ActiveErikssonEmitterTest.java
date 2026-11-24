package br.unb.cic.td18.emission;

import br.unb.cic.td18.joint.*;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

class ActiveErikssonEmitterTest {

    @Test
    void zeroDirectInventoryRowsSkipRootGenerationIncludingSupportClearedTypes() {
        // This small domain actually has direct roots. A zero inventory entry
        // still controls emission; only independent verification audits it.
        try (val progress = new ErikssonWitnessEmitter.Progress()) {
            for (val row : java.util.List.of(new ErikssonInventory.Root(1, new int[] { 7 }, 0, 0, 0),
                    new ErikssonInventory.Root(2, new int[] { 7 }, 0, 1, 1))) {
                val result = ErikssonWitnessEmitter.emitRoot(row,
                        (pair, budget) -> {
                            fail("Zero direct inventory rows must not generate obligations");
                            return false;
                        },
                        progress);
                assertEquals(new ErikssonWitnessEmitter.RootResult(row.unoriented(), row.oriented(), row.supportCleared(), 0),
                        result);
            }
        }
    }

    @Test
    void queuedRootFailuresAreObservedAfterTheQueueIsDrained() throws Exception {
        final int[] partition = { 7 };
        val counts = ErikssonRootGenerator.count(partition, 2, new LongAdder());
        assertTrue(counts.direct() > 0);

        val row = new ErikssonInventory.Root(1, partition, counts.unoriented(), counts.oriented(), counts.supportCleared());
        val called = new LongAdder();

        try (val progress = new ErikssonWitnessEmitter.Progress()) {
            val result = ErikssonWitnessEmitter.emitRoot(row,
                    (pair, budget) -> {
                        assertEquals(ErikssonRoute.ROOT_BUDGET, budget);

                        try {
                            Thread.sleep(20);
                        } catch (final InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }

                        called.increment();

                        return false;
                    },
                    progress);
            assertEquals(counts.direct(), called.sum());
            assertEquals(counts.direct(), result.unresolved());

            val pending = new ErikssonInventory.Root(1, partition, -1, -1, -1);
            assertThrows(IllegalStateException.class, () -> ErikssonWitnessEmitter.emitRoot(pending, (p, b) -> true, progress));
        }
    }
}

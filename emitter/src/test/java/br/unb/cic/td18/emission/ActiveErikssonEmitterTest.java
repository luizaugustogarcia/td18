package br.unb.cic.td18.emission;
import br.unb.cic.td18.joint.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.LongAdder;
import static org.junit.jupiter.api.Assertions.*;
class ActiveErikssonEmitterTest {
    @Test void zeroDirectInventoryRowsSkipRootGenerationIncludingSupportClearedTypes() {
        // This small domain actually has direct roots. A zero inventory entry
        // still controls emission; only independent verification audits it.
        try (var progress=new ErikssonWitnessEmitter.Progress()) {
            for (var row:java.util.List.of(
                    new ErikssonInventory.Root(1,new int[]{7},0,0,0),
                    new ErikssonInventory.Root(2,new int[]{7},0,1,1))) {
                var result=ErikssonWitnessEmitter.emitRoot(row,(pair,budget)->{
                    fail("Zero direct inventory rows must not generate obligations"); return false;
                },progress);
                assertEquals(new ErikssonWitnessEmitter.RootResult(
                        row.unoriented(),row.oriented(),row.supportCleared(),0),result);
            }
        }
    }

    @Test void queuedRootFailuresAreObservedAfterTheQueueIsDrained() throws Exception {
        int[] partition={7};
        var counts=ErikssonRootGenerator.count(partition,2,new LongAdder()); assertTrue(counts.direct()>0);
        var row=new ErikssonInventory.Root(1,partition,counts.unoriented(),counts.oriented(),counts.supportCleared());
        LongAdder called=new LongAdder();
        try (var progress=new ErikssonWitnessEmitter.Progress()) {
            var result=ErikssonWitnessEmitter.emitRoot(row,(pair,budget)->{
                assertEquals(ErikssonRoute.ROOT_BUDGET,budget);
                try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                called.increment(); return false;
            },progress);
            assertEquals(counts.direct(),called.sum()); assertEquals(counts.direct(),result.unresolved());
            var pending=new ErikssonInventory.Root(1,partition,-1,-1,-1);
            assertThrows(IllegalStateException.class,()->ErikssonWitnessEmitter.emitRoot(pending,(p,b)->true,progress));
        }
    }
}

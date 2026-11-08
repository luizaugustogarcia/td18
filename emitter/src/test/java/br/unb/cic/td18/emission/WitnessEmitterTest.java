package br.unb.cic.td18.emission;

import br.unb.cic.td18.avoidance.DiskStateSet;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.joint.JointInventory;
import br.unb.cic.td18.joint.JointRootGenerator;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WitnessEmitterTest {
    @Test void totallyUnorientedRootsRequireCompleteWitnessesAndCountsMatch() {
        int[] partition = {3,2,2};
        long u = DirectTwistedBraceletGenerator.countRepresentatives(partition,new boolean[3]);
        var oriented = JointRootGenerator.count(partition);
        assertTrue(u > 0);
        var row = new JointInventory.Root(1,partition,u,oriented.jointResidualClasses(),0,oriented.jointResidualClasses());
        var complete = new LongAdder();
        try (var progress = new WitnessEmitter.Progress()) {
            var result = WitnessEmitter.emitRoot(row,(pair,budget,witness) -> {
                assertEquals(10,budget);
                if (witness) complete.increment();
                return false;
            },progress);
            assertEquals(u,complete.sum());
            assertEquals(u+oriented.jointResidualClasses(),result.unresolved());
            var wrong = new JointInventory.Root(1,partition,u+1,oriented.jointResidualClasses(),0,oriented.jointResidualClasses());
            assertThrows(IllegalStateException.class, () -> WitnessEmitter.emitRoot(wrong,(p,b,w) -> true,progress));
        }
    }
    @Test void missingEndpointWitnessesPopulateFinalWorkWithoutMarkers(@TempDir Path directory) {
        int[] partition = {3,2,2};
        long expected = DirectTwistedBraceletGenerator.countRepresentatives(partition,new boolean[3]);
        var endpoint = new JointInventory.Endpoint(1,partition,new boolean[3]);
        try (var states = new DiskStateSet(directory.resolve("states")); var progress = new WitnessEmitter.Progress()) {
            WitnessEmitter.emitEndpoint(endpoint,(pair,budget,complete) -> {
                assertEquals(8,budget); assertFalse(complete); return false;
            },states,progress);
            assertTrue(expected > 0); assertEquals(expected,states.seal());
        }
    }
    @Test void malformedScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WitnessEmitter.Options.parse(new String[]{"--partitions=0-53"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> WitnessEmitter.Options.parse(new String[]{"--phase=unknown"},Path.of(".")));
    }
}

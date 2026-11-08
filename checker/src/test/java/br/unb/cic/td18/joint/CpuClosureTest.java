package br.unb.cic.td18.joint;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuClosureTest {
    @Test void prefixReplayPreservesImplicitFixedPoints() {
        final var pair=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(1,2,3)),Cycle.of(0,1,2,3));
        assertTrue(CpuClosure.closes(pair,1));
        assertThrows(IllegalArgumentException.class,()->new JointInverseReconstruction.SearchState(8,pair));
        assertThrows(IllegalArgumentException.class,()->JointInverseReconstruction.preimages(pair,1,ignored->{ }));
    }
}

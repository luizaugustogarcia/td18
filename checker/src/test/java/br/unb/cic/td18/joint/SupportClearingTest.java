package br.unb.cic.td18.joint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SupportClearingTest {
    @Test
    @EnabledIfSystemProperty(named="td18.verifyPartitionCounts",matches="true")
    void independentGenerationReproducesAllFourClearingLengthSamples() {
        sample(new int[]{13,4,2},333,2);
        sample(new int[]{11,4,4},5899,154);
        sample(new int[]{9,6,4},2856,601);
        sample(new int[]{8,7,4},59,12);
    }
    @Test void allAdmittedPowersAndTheirRelabelingsMatchTheRootBudget() {
        power(13,new int[]{7,8,9,10,11,12,0,1,2,3,4,5,6,17,18,13,14,15,16});
        power(13,new int[]{10,11,12,0,1,2,3,4,5,6,7,8,9,17,18,13,14,15,16});
        power(13,new int[]{11,12,0,1,2,3,4,5,6,7,8,9,10,17,18,13,14,15,16});
        power(11,new int[]{6,7,8,9,10,0,1,2,3,4,5,17,18,11,12,13,14,15,16});
        power(9,new int[]{8,10,0,1,2,3,4,17,5,7,6,9,18,11,12,13,14,15,16});
        power(0,new int[]{5,6,8,10,0,1,2,17,3,7,4,9,18,11,12,13,14,15,16});
        power(7,new int[]{14,16,0,17,2,18,4,1,6,15,8,9,10,11,12,13,3,5,7});
    }
    private static void power(final int expected,final int[] images) {
        final var pair=IndependentJointRootGeneratorTest.pair(images);
        assertEquals(expected,SupportClearing.closingLength(pair));
        final var relabeled=new MulticyclePermutation();
        for(final var cycle:pair.getOmega())
            relabeled.add(Cycle.of(Arrays.stream(cycle.getSymbols()).map(x->(5*x+3)%19).toArray()));
        final var beta=Cycle.of(Arrays.stream(pair.getBeta().getSymbols()).map(x->(5*x+3)%19).toArray());
        assertEquals(expected,SupportClearing.closingLength(CyclicTargetPair.of(relabeled,beta)));
    }
    private static void sample(final int[] partition,final long expectedJoint,final long expectedCleared) {
        final long[] counts={0,0};
        IndependentJointRootGenerator.generateResiduals(partition,pair->{
            counts[0]++;
            if(SupportClearing.closes(pair))counts[1]++;
        });
        assertEquals(expectedJoint,counts[0]);
        assertEquals(expectedCleared,counts[1]);
    }
}

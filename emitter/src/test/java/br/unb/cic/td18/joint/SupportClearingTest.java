package br.unb.cic.td18.joint;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SupportClearingTest {
    @Test
    void rootClearingCountMatchesTheAuditAndIsIndependentOfSymbolLabels() {
        final long[] counts = {0, 0};
        JointRootGenerator.generateResiduals(new int[]{13, 4, 2}, pair -> {
            counts[0]++;
            final int length = SupportClearing.closingLength(pair);
            if (length != 0) counts[1]++;
            final MulticyclePermutation relabeledOmega = new MulticyclePermutation();
            for (final Cycle cycle : pair.getOmega()) {
                relabeledOmega.add(Cycle.of(IntStream.of(cycle.getSymbols())
                        .map(x -> (7 * x + 3) % 19).toArray()));
            }
            final Cycle relabeledBeta = Cycle.of(IntStream.of(pair.getBeta().getSymbols())
                    .map(x -> (7 * x + 3) % 19).toArray());
            assertEquals(length, SupportClearing.closingLength(
                    CyclicTargetPair.of(relabeledOmega, relabeledBeta)));
        });
        assertArrayEquals(new long[]{333, 2}, counts);
    }

    @Test
    void onlyTheBudgetAdmittedLocalPowersAreAccepted() {
        final int[][] candidates = {
                {5, 3, 0}, {7, 4, 1}, {9, 5, 0}, {9, 7, 1},
                {11, 6, 1}, {13, 7, 1}, {13, 10, 1}, {13, 11, 1}, {15, 8, 0}
        };
        for (final int[] candidate : candidates) {
            final int length = candidate[0];
            final int exponent = candidate[1];
            final int[] omega = IntStream.range(0, 19).toArray();
            final int[] support = IntStream.range(0, length).toArray();
            for (int x = 0; x < length; x++) omega[x] = (x + exponent) % length;
            assertEquals(candidate[2] == 1, SupportClearing.admittedPower(omega, support, length),
                    "length=" + length + ", exponent=" + exponent);
            // An admitted local power has no applicable qualifying support.
            for (int a = 0; a < length - 2; a++) {
                for (int b = a + 1; b < length - 1; b++) {
                    for (int c = b + 1; c < length; c++) {
                        final var move = new JointReductionAnalysis.Move(a, b, c);
                        if (JointReductionAnalysis.gain(omega, move) == 0) continue;
                        final int[] next = JointReductionAnalysis.applyToOmega(omega, move);
                        assertNotEquals(JointReductionAnalysis.oddCycleCount(omega) + 2,
                                JointReductionAnalysis.oddCycleCount(next));
                    }
                }
            }
        }
    }
}

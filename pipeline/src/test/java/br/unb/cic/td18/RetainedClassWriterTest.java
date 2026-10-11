package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedClassWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsOnlyEncodedOutputBufferedUntilFlush() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var moves = List.of(Cycle.of(0, 1, 2));
        try (final var classes = new ClassStore(
                temporaryDirectory.resolve("classes"), 3, true);
             final var witnesses = new WitnessStore(
                     temporaryDirectory.resolve("witnesses"), true);
             final var writer = new RetainedClassWriter(
                     classes, witnesses, 60)) {
            writer.add(pair, moves);

            assertFalse(classes.contains(pair));
            assertFalse(witnesses.contains(pair));

            writer.flush();

            assertTrue(classes.contains(pair));
            assertTrue(witnesses.contains(pair));
        }
    }
}

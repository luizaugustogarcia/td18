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
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetainedClassWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classOnlyWriterFlushesOnCloseWithoutRequiringAWitness() {
        final var pair = KnownDiametersTest.reversePair(15);
        final var directory = temporaryDirectory.resolve("class-only");
        try (final var classes = new ClassStore(directory, 15, true)) {
            try (final var writer = new RetainedClassWriter(classes, 60)) {
                writer.add(pair);
                assertFalse(classes.contains(pair));
            }
            assertTrue(classes.contains(pair));
        }
        try (final var classes = new ClassStore(directory, 15, false)) {
            assertTrue(classes.contains(pair));
        }
    }

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
            assertThrows(IllegalStateException.class, () -> writer.add(pair));
            writer.add(pair, moves);

            assertFalse(classes.contains(pair));
            assertFalse(witnesses.contains(pair));

            writer.flush();

            assertTrue(classes.contains(pair));
            assertTrue(witnesses.contains(pair));
        }
    }
}

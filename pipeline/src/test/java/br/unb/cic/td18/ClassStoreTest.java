package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsCanonicalClassesAndReconstructsFixedPoints() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var pair = CircularClassOperations.insertions(parent).stream()
                .map(CircularClassOperations.Insertion::child)
                .filter(child -> child.getOmega().image(3) == 3)
                .findFirst()
                .orElseThrow();
        final ClassStore.Summary written;
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("classes"), 4, true)) {
            assertTrue(store.add(pair));
            assertFalse(store.add(pair.canonicalRepresentative()));
            assertTrue(store.contains(pair));
            written = store.summarize();
            assertEquals(1, written.count());
        }

        try (final var store = new ClassStore(
                temporaryDirectory.resolve("classes"), 4, false)) {
            final var restored = new ArrayList<CyclicTargetPair>();
            store.forEach(restored::add);
            assertEquals(1, restored.size());
            assertEquals(pair.extendedToricClassKey(),
                    restored.getFirst().extendedToricClassKey());
            assertEquals(1, CircularClassOperations.algebraicFixedPointCount(
                    restored.getFirst()));
            assertEquals(written, store.summarize());
        }
    }

    @Test
    void manifestChecksCountAndChecksum() {
        final var stage = temporaryDirectory.resolve("stage");
        try (final var store = new ClassStore(stage.resolve("classes"), 3, true)) {
            store.add(CyclicTargetPair.of(
                    new MulticyclePermutation(Cycle.of(0, 1, 2)),
                    Cycle.of(0, 1, 2)));
            StageManifest.complete(stage, "TEST", 3, store.summarize());
        }
        try (final var store = new ClassStore(stage.resolve("classes"), 3, false)) {
            StageManifest.verify(stage, "TEST", 3, store.summarize());
        }
    }

    @Test
    void boundedParallelIterationVisitsEveryClass() {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final Set<ExtendedToricClassKey> expected =
                ConcurrentHashMap.newKeySet();
        try (final var store = new ClassStore(
                temporaryDirectory.resolve("parallel"), 4, true)) {
            CircularClassOperations.childClasses(parent).forEach(child -> {
                store.add(child);
                expected.add(child.extendedToricClassKey());
            });

            final Set<ExtendedToricClassKey> actual =
                    ConcurrentHashMap.newKeySet();
            store.forEachParallel(4,
                    pair -> actual.add(pair.extendedToricClassKey()));
            assertEquals(expected, actual);
        }
    }
}

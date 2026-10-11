package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static br.unb.cic.td18.ConcurrencyTestSupport.await;
import static org.junit.jupiter.api.Assertions.*;

class ClassStoreProcessingTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void duplicatesSkipBeforeOwnerFinishesAndOtherClassesProceed() throws Exception {
        final var parent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        final var children = CircularClassOperations.childClasses(parent);
        final var pair = children.getFirst();
        final var entered = new CountDownLatch(1);
        final var finish = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var directory = temporaryDirectory.resolve("processed");
        try (final var store = new ClassStore(directory, 4, true);
             final var executor = Executors.newFixedThreadPool(2)) {
            final var owner = executor.submit(() -> store.processIfAbsent(pair, () -> {
                calls.incrementAndGet();
                entered.countDown();
                await(finish);
            }));
            try {
                await(entered);
                assertFalse(store.contains(pair), "Completion must follow the whole action");
                for (var index = 0; index < 8; index++) {
                    final var duplicate = executor.submit(() -> store.processIfAbsent(
                            pair.canonicalRepresentative(), calls::incrementAndGet));
                    assertFalse(duplicate.get(2, TimeUnit.SECONDS),
                            "Duplicates must skip while their owner is still running");
                }
                assertTrue(store.processIfAbsent(children.getLast(), () -> {}));
                assertEquals(1, calls.get());
            } finally {
                finish.countDown();
            }
            assertTrue(owner.get(10, TimeUnit.SECONDS));
            assertTrue(store.contains(pair));
            assertFalse(store.processIfAbsent(pair, calls::incrementAndGet));
            assertEquals(1, calls.get());
        }
        try (final var reopened = new ClassStore(directory, 4, true)) {
            assertFalse(reopened.processIfAbsent(pair, calls::incrementAndGet));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void failedOwnerIsResumableEvenIfTheDuplicateParentWasCheckpointed() throws Exception {
        final var ownerParent = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        final var duplicateParent = CyclicTargetPair.of(
                new MulticyclePermutation(), Cycle.of(0, 1, 2));
        final var child = CircularClassOperations.childClasses(ownerParent).getFirst();
        final var entered = new CountDownLatch(1);
        final var finish = new CountDownLatch(1);
        final var failure = new IllegalStateException("Classification failed");
        final var candidatesDirectory = temporaryDirectory.resolve("candidates");
        final var parentsDirectory = temporaryDirectory.resolve("parents");
        try (final var candidates = new ClassStore(candidatesDirectory, 4, true);
             final var parents = new ClassStore(parentsDirectory, 3, true);
             final var executor = Executors.newSingleThreadExecutor()) {
            final var owner = executor.submit(() -> {
                candidates.processIfAbsent(child, () -> {
                    entered.countDown();
                    await(finish);
                    throw failure;
                });
                parents.add(ownerParent);
            });
            try {
                await(entered);
                assertFalse(candidates.processIfAbsent(child, () -> fail("Duplicate ran")));
                parents.add(duplicateParent);
            } finally {
                finish.countDown();
            }
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> owner.get(10, TimeUnit.SECONDS)).getCause());
            assertFalse(parents.contains(ownerParent));
            assertTrue(parents.contains(duplicateParent));
            assertFalse(candidates.contains(child));
        }
        try (final var candidates = new ClassStore(candidatesDirectory, 4, true);
             final var parents = new ClassStore(parentsDirectory, 3, true)) {
            assertFalse(parents.contains(ownerParent));
            assertTrue(candidates.processIfAbsent(child, () -> {}));
            parents.add(ownerParent);
            assertTrue(candidates.contains(child));
            assertTrue(parents.contains(ownerParent));
        }
    }

    @Test
    void rejectsWrongDomainAndReadOnlyProcessing() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        final var directory = temporaryDirectory.resolve("validation");
        try (final var store = new ClassStore(directory, 4, true)) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.processIfAbsent(pair, () -> fail("Wrong domain ran")));
        }
        try (final var store = new ClassStore(directory, 4, false)) {
            assertThrows(IllegalStateException.class,
                    () -> store.processIfAbsent(pair, () -> fail("Read-only action ran")));
        }
    }
}

package br.unb.cic.td18.emission;

import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExceptionalFifteenCycle;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.search.WitnessSearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionalFifteenCycleEmitterTest {
    @Test
    void selectionsIncludeBothDepthsAndRequireTheirDeterministicKeys() {
        assertEquals(List.of("15O-2U-2U@0", "15O-3U@1"),
                ExceptionalFifteenCycleEmitter.selectedObligations(null).stream()
                        .map(ExceptionalFifteenCycle.Obligation::key).toList());
        assertEquals(1, ExceptionalFifteenCycleEmitter.selectedObligations("15O-3U@1").size());
        for (final var key : List.of("15O-3U", "15O-3U@0", "15O-3O@1", "15O-2U-2U@1")) {
            assertThrows(IllegalArgumentException.class, () ->
                    ExceptionalFifteenCycleEmitter.selectedObligations(key));
        }
    }

    @Test
    void validatedPrefixesCloseEightySixClassesBeforeAnyCompleteSearch() {
        for (final var obligation : ExceptionalFifteenCycle.obligations()) {
            final var calls = new AtomicInteger();
            final var result = ExceptionalFifteenCycleEmitter.emitObligation(obligation, (pair, rate, budget) -> {
                calls.incrementAndGet();
                assertEquals(obligation.domain(), pair.getBeta().size());
                assertTrue(ExceptionalFifteenCycle.contains(pair));
                assertEquals(obligation.budget(), budget);
                assertEquals(obligation.movesUsed() == 0 ? 1.8f : 16 / 9.0f, rate, 1e-6f);
                return WitnessSearch.SearchResult.residual();
            });
            final long expected = obligation.movesUsed() == 0 ? 90 : 7;
            final long expectedClosures = obligation.movesUsed() == 0 ? 79 : 7;
            assertEquals(expected - expectedClosures, calls.get());
            assertEquals(expected, result.generated());
            assertEquals(expectedClosures, result.cpuClosures());
            assertEquals(expected - expectedClosures, result.unresolved());
            assertEquals(0, result.certificates());
            assertEquals(obligation.movesUsed() == 1, result.complete());
        }
    }

    @Test
    void intermediatePrefixClosuresSucceedWithAnEmptyDatabaseAndNoSearch(@TempDir final Path directory) {
        final var obligation = ExceptionalFifteenCycleEmitter.selectedObligations("15O-3U@1");
        try (final var writer = new AsyncCertificateWriter(directory.resolve("database"), 60)) {
            final var results = ExceptionalFifteenCycleEmitter.ensure(writer, obligation, (pair, rate, budget) -> {
                throw new AssertionError("Every intermediate is closed by the regenerated CPU prefix");
            });
            final var result = results.getFirst();
            assertEquals(7, result.generated());
            assertEquals(7, result.cpuClosures());
            assertEquals(0, result.certificates());
            assertEquals(0, result.unresolved());
            assertTrue(result.complete());
            final var type = obligation.getFirst().type();
            DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                    type.partition(), type.oriented(), pair -> assertTrue(writer.find(pair, 9).isEmpty()));
        }
    }

    @Test
    void intermediateClosuresUseTheActualRemainingBudget() {
        final var type = ExceptionalFifteenCycleEmitter.selectedObligations("15O-3U@1").getFirst().type();
        final var classes = new AtomicInteger();
        DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                type.partition(), type.oriented(), pair -> {
                    classes.incrementAndGet();
                    assertTrue(WitnessSearch.closesByCpuPrefix(pair, 9));
                    assertFalse(WitnessSearch.closesByCpuPrefix(pair, 8));
                });
        assertEquals(7, classes.get());
    }

    @Test
    void missingRootWitnessesFailThePersistedBranch(@TempDir final Path directory) {
        final var obligation = ExceptionalFifteenCycleEmitter.selectedObligations("15O-2U-2U@0");
        try (final var writer = new AsyncCertificateWriter(directory.resolve("database"), 60)) {
            final var calls = new AtomicInteger();
            final var failure = assertThrows(IllegalStateException.class, () ->
                    ExceptionalFifteenCycleEmitter.ensure(writer, obligation, (pair, rate, budget) -> {
                        calls.incrementAndGet();
                        assertEquals(10, budget);
                        return WitnessSearch.SearchResult.residual();
                    }));
            assertEquals(11, calls.get());
            assertTrue(failure.getMessage().contains("Unresolved exceptional-fifteen classes"));
            assertTrue(failure.getMessage().contains("unresolved=11"));
        }
    }

    @Test
    void rejectsUnverifiedClosureClaimsAndOverBudgetRootCertificates() {
        final var obligation = ExceptionalFifteenCycleEmitter.selectedObligations("15O-2U-2U@0").getFirst();
        final var failure = assertThrows(IllegalStateException.class, () ->
                ExceptionalFifteenCycleEmitter.emitObligation(obligation,
                        (pair, rate, budget) -> WitnessSearch.SearchResult.triviallySettled()));
        assertTrue(failure.getMessage().contains("complete witness"));
        final var excessive = assertThrows(IllegalArgumentException.class, () ->
                ExceptionalFifteenCycleEmitter.emitObligation(obligation, (pair, rate, budget) ->
                        WitnessSearch.SearchResult.certificate(
                                Collections.nCopies(budget + 1, Cycle.of(0, 1, 2)))));
        assertTrue(excessive.getMessage().contains("required range is 1..10"));
    }

    @Test
    void invalidWitnessNeverCrossesThePersistenceBoundary(@TempDir final Path directory) {
        final var obligation = ExceptionalFifteenCycleEmitter.selectedObligations("15O-2U-2U@0");
        final var attempted = new AtomicReference<CyclicTargetPair>();
        try (final var writer = new AsyncCertificateWriter(directory.resolve("database"), 60)) {
            assertThrows(IllegalArgumentException.class, () ->
                    ExceptionalFifteenCycleEmitter.ensure(writer, obligation, (pair, rate, budget) -> {
                        attempted.set(pair);
                        return WitnessSearch.SearchResult.certificate(List.of(Cycle.of(0, 1, 2)));
                    }));
            assertNotNull(attempted.get());
            writer.flush();
            assertTrue(writer.find(attempted.get(), 10).isEmpty());
        }
    }

    @Test
    void incompleteGenerationCannotMasqueradeAsACompleteResult() {
        assertThrows(IllegalArgumentException.class, () ->
                new ExceptionalFifteenCycleEmitter.EmissionResult("15O-3U@1", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new ExceptionalFifteenCycleEmitter.EmissionResult("15O-2U-2U@0", 89, 79, 10, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new ExceptionalFifteenCycleEmitter.EmissionResult("15O-3U@1", 7, 0, 0, 0));
    }
}

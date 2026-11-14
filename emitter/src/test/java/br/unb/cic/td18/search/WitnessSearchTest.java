package br.unb.cic.td18.search;

import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.base.GPUSortingSearch;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WitnessSearchTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5)).canonicalRepresentative();
    }
    @Test void sortingRequiresPersistenceBeforeAnyGpuWork() {
        assertThrows(IllegalStateException.class, () -> WitnessSearch.search(pair(), 2));
        assertThrows(IllegalStateException.class, () -> WitnessSearch.searchGpu(pair(), 2));
    }

    @Test void reconstructionDispatchesUncachedClassesWithoutAnyCpuPrefix(@TempDir Path path) {
        String previous=System.getProperty("td18.cpuPrefixDepth");
        System.setProperty("td18.cpuPrefixDepth","-1");
        try (var writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var dispatched=new java.util.concurrent.atomic.AtomicInteger();
                var result=WitnessSearch.searchGpu(pair(),2,(input,budget)->{
                    assertEquals(2,budget);assertEquals(pair().extendedToricClassKey(),input.extendedToricClassKey());
                    dispatched.incrementAndGet();
                    return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                            java.util.List.of(new int[]{0,1,2}));
                });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                writer.flush();
                assertEquals(result,WitnessSearch.searchGpu(pair(),2,(input,budget)->{
                    fail("A stored certificate must avoid GPU dispatch");return null;
                }));
                assertEquals(1,dispatched.get());
                assertTrue(writer.findResult(pair(), 2).isPresent());
                assertTrue(writer.findResult(pair(), 1).isEmpty());
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            if(previous==null)System.clearProperty("td18.cpuPrefixDepth");else System.setProperty("td18.cpuPrefixDepth",previous);
        }
    }

    @Test void gpuResidualsAndInvalidCertificatesCannotBecomeDischarges(@TempDir Path path) {
        try(var writer=new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertEquals(WitnessSearch.Disposition.RESIDUAL,WitnessSearch.searchGpu(pair(),2,(input,budget)->
                        new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.RESIDUAL,java.util.List.of())).disposition());
                assertThrows(IllegalArgumentException.class,()->WitnessSearch.searchGpu(pair(),2,(input,budget)->
                        new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.COMPLETE,java.util.List.of())));
                writer.flush();assertTrue(writer.findResult(pair(),2).isEmpty());
            } finally {WitnessSearch.uninstallWriter(writer);}
        }
    }

    @Test void reconstructionTimeoutsPreserveObligationBudgetsAndNeverPersistWitnesses(@TempDir Path path) {
        String previous = System.getProperty("td18.reconstructionGpuMillis");
        System.setProperty("td18.reconstructionGpuMillis", "37");
        try (var writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var dispatched = new java.util.concurrent.atomic.AtomicInteger();
                for (int maximumMoves : new int[]{8, 9, 10}) {
                    var result = WitnessSearch.searchReconstructedGpu(pair(), maximumMoves, (input, budget, timeout, depth) -> {
                        assertEquals(maximumMoves, budget);
                        assertEquals(maximumMoves, depth);
                        assertEquals(maximumMoves == 10 ? 0 : 37, timeout);
                        assertEquals(pair().extendedToricClassKey(), input.extendedToricClassKey());
                        dispatched.incrementAndGet();
                        return new GPUSortingSearch.SearchOutcome(maximumMoves == 10
                                ? GPUSortingSearch.Disposition.RESIDUAL : maximumMoves == 8
                                ? GPUSortingSearch.Disposition.TIMED_OUT : GPUSortingSearch.Disposition.DEPTH_LIMIT,
                                java.util.List.of());
                    });
                    assertEquals(WitnessSearch.Disposition.RESIDUAL, result.disposition());
                    assertTrue(result.moves().isEmpty());
                }
                assertEquals(3, dispatched.get());
                writer.flush();
                assertTrue(writer.findResult(pair(), 10).isEmpty());
                writer.forEachObstruction(key -> fail("An incomplete search cannot record a certificate or a proven failure"));
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            if (previous == null) System.clearProperty("td18.reconstructionGpuMillis");
            else System.setProperty("td18.reconstructionGpuMillis", previous);
        }
    }

    @Test void reconstructionTimeoutDefaultsAreValidatedAndFinalBudgetAlwaysHasNoCutoff() {
        String previous = System.getProperty("td18.reconstructionGpuMillis");
        try {
            System.clearProperty("td18.reconstructionGpuMillis");
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(8));
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(9));
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(10));
            System.setProperty("td18.reconstructionGpuMillis", "0");
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(9));
            for (String invalid : new String[]{"-1", "invalid", Long.toString(Long.MAX_VALUE)}) {
                System.setProperty("td18.reconstructionGpuMillis", invalid);
                assertThrows(IllegalArgumentException.class, () -> WitnessSearch.reconstructionGpuMillis(9));
                assertEquals(0, WitnessSearch.reconstructionGpuMillis(10));
            }
        } finally {
            if (previous == null) System.clearProperty("td18.reconstructionGpuMillis");
            else System.setProperty("td18.reconstructionGpuMillis", previous);
        }
    }

    @Test void advisoryGpuSlicesPreserveFullDepthAndDoNotChangeFinalSearchLimits(@TempDir Path path) {
        String previous = System.getProperty("td18.rootPrefetchGpuMillis");
        String previousCpu = System.getProperty("td18.cpuPrefixDepth");
        System.clearProperty("td18.rootPrefetchGpuMillis");
        System.setProperty("td18.cpuPrefixDepth", "-1");
        try (var writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result = WitnessSearch.searchGpuPrefetch(pair(), 10, (input, budget, timeout, depth) -> {
                    assertEquals(10, budget); assertEquals(10, depth); assertEquals(100, timeout);
                    return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.TIMED_OUT, java.util.List.of());
                });
                assertEquals(WitnessSearch.Disposition.RESIDUAL, result.disposition());
                WitnessSearch.searchReconstructedGpu(pair(), 10, (input, budget, timeout, depth) -> {
                    assertEquals(10, budget); assertEquals(10, depth); assertEquals(0, timeout);
                    return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.RESIDUAL, java.util.List.of());
                });
                writer.flush();
                assertTrue(writer.findResult(pair(), 10).isEmpty());
                writer.forEachObstruction(key -> fail("An advisory timeout cannot persist an obstruction"));
                for (String invalid : new String[]{"0", "-1", "invalid", Long.toString(Long.MAX_VALUE)}) {
                    System.setProperty("td18.rootPrefetchGpuMillis", invalid);
                    assertThrows(IllegalArgumentException.class, () -> WitnessSearch.searchGpuPrefetch(pair(), 10,
                            (input, budget, timeout, depth) -> { fail("Invalid time slice must not dispatch"); return null; }));
                }
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            if (previous == null) System.clearProperty("td18.rootPrefetchGpuMillis");
            else System.setProperty("td18.rootPrefetchGpuMillis", previous);
            if (previousCpu == null) System.clearProperty("td18.cpuPrefixDepth");
            else System.setProperty("td18.cpuPrefixDepth", previousCpu);
        }
    }

    @Test void reconstructionAlwaysUsesFullDepthEvenWithAnOldDepthCapProperty() {
        String previous = System.getProperty("td18.reconstructionGpuDepth");
        try {
            System.clearProperty("td18.reconstructionGpuDepth");
            assertEquals(8, WitnessSearch.reconstructionGpuDepth(8));
            assertEquals(9, WitnessSearch.reconstructionGpuDepth(9));
            assertEquals(3, WitnessSearch.reconstructionGpuDepth(3));
            assertEquals(10, WitnessSearch.reconstructionGpuDepth(10));
            System.setProperty("td18.reconstructionGpuDepth", "2");
            assertEquals(9, WitnessSearch.reconstructionGpuDepth(9));
            assertEquals(10, WitnessSearch.reconstructionGpuDepth(10));
            for (String invalid : new String[]{"0", "-1", "11", "invalid"}) {
                System.setProperty("td18.reconstructionGpuDepth", invalid);
                assertEquals(9, WitnessSearch.reconstructionGpuDepth(9));
                assertEquals(10, WitnessSearch.reconstructionGpuDepth(10));
            }
        } finally {
            if (previous == null) System.clearProperty("td18.reconstructionGpuDepth");
            else System.setProperty("td18.reconstructionGpuDepth", previous);
        }
    }

    @Test void preparedGpuMissStillReplaysAndPersistsTheActualWitness(@TempDir Path path) {
        try (var writer=new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result=WitnessSearch.searchGpuPrefetch(pair(),2,(input,budget,timeout,depth) ->
                        new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                                java.util.List.of(new int[]{0,1,2})),false);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                writer.flush(); assertTrue(writer.findResult(pair(),2).isPresent());
                assertThrows(IllegalArgumentException.class, () -> WitnessSearch.searchGpuPrefetch(pair(),2,
                        (input,budget,timeout,depth) -> new GPUSortingSearch.SearchOutcome(
                                GPUSortingSearch.Disposition.COMPLETE, java.util.List.of()),false));
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
    }

    @Test void interruptedCpuSearchStopsWithoutReturningACertificate() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> CpuPrefixSearch.search(pair(), 2, 10, 16777216));
        } finally { Thread.interrupted(); }
    }

    @Test void largeBatchReplaysEveryHitBeforeDispatch(@TempDir Path path) {
        try(var writer=new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                writer.enqueueClosure(pair(),java.util.List.of(Cycle.of(0,1,2)),2);writer.flush();
                var batch=java.util.Collections.nCopies(4096,pair());
                var hits=WitnessSearch.lookupBatch(batch,2);
                assertEquals(4096,hits.length);
                for(boolean hit:hits)assertTrue(hit);
                for(boolean hit:WitnessSearch.lookupBatch(batch,1))assertFalse(hit);
            } finally {WitnessSearch.uninstallWriter(writer);}
        }
    }

    @Test void timedCpuPrefixEitherSettlesOrFallsThroughWithTheFullBudget(@TempDir Path path) {
        try (var writer = new AsyncCertificateWriter(path.resolve("settled"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result = WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, 1000,
                        (input, budget) -> { fail("The CPU prefix should settle this pair"); return null; });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, result.moves().size());
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
        try (var writer = new AsyncCertificateWriter(path.resolve("timeout"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var dispatched = new java.util.concurrent.atomic.AtomicInteger();
                var result = WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, 0, (input, budget) -> {
                    assertEquals(2, budget);
                    assertEquals(pair().extendedToricClassKey(), input.extendedToricClassKey());
                    dispatched.incrementAndGet();
                    return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                            java.util.List.of(new int[]{0,1,2}));
                });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, dispatched.get());
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
        assertThrows(IllegalArgumentException.class,
                () -> WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, -1, (input,budget) -> null));
    }

    @Test void cpuStagePersistsClosuresAndLeavesMissesForTheGpuWithoutObstructionMarkers(@TempDir Path path) {
        try (var writer = new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertEquals(WitnessSearch.Disposition.RESIDUAL,WitnessSearch.searchCpu(pair(),2,0).disposition());
                writer.flush(); assertTrue(writer.findResult(pair(),2).isEmpty());
                writer.forEachObstruction(key -> fail("A heuristic CPU miss is not a final discharge miss"));
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,WitnessSearch.searchCpu(pair(),2,1000).disposition());
                writer.flush();
                assertEquals(1,writer.findResult(pair(),2).orElseThrow().moves().size());
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,WitnessSearch.searchCpu(pair(),2,0).disposition());
                assertThrows(IllegalArgumentException.class,()->WitnessSearch.searchCpu(pair(),2,-1));
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
    }

    @Test void reconstructionDefaultsToFivePrefixMovesAndHonorsAnExplicitDepth(@TempDir Path path) {
        var input = CyclicTargetPair.of(new MulticyclePermutation("(0 1 11 6 8 12 9 3 5 2 10 7 4)"),
                Cycle.of(0,1,2,3,4,5,6,7,8,9,10,11,12)).canonicalRepresentative();
        String previous = System.getProperty("td18.cpuPrefixDepth");
        try (var writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);
            try {
                System.setProperty("td18.cpuPrefixDepth", "4");
                assertEquals(WitnessSearch.Disposition.RESIDUAL, WitnessSearch.searchCpu(input, 6, 1000).disposition());
                System.clearProperty("td18.cpuPrefixDepth");
                assertTrue(CpuPrefixSearch.search(input, 6).isEmpty());
                var result = WitnessSearch.searchCpu(input, 6, 1000);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(5, result.moves().size());
                writer.flush();
                var stored = writer.findResult(input, 6).orElseThrow();
                assertEquals(result.moves(), stored.moves());
                CertificateValidator.validate(input, stored, 6);
                assertTrue(writer.findResult(input, 5).isEmpty());
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            if (previous == null) System.clearProperty("td18.cpuPrefixDepth");
            else System.setProperty("td18.cpuPrefixDepth", previous);
        }
    }

    @Test
    void cpuClosureIsPersistedReplayedAndNotACompleteWitness(@TempDir Path path) {
        try (var writer = new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result = WitnessSearch.search(pair(),2);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                assertEquals(1,result.moves().size());
                assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair(),result.moves(),2));
                writer.flush();
                assertTrue(writer.find(pair(),1).isEmpty());
                assertEquals(result.moves(),writer.find(pair(),2).orElseThrow());
                assertEquals(result,WitnessSearch.search(pair(),2));
            } finally {
                WitnessSearch.uninstallWriter(writer);
                WitnessSearch.shutdownGpuStats();
            }
        }
    }
}

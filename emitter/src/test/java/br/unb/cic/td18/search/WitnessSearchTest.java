package br.unb.cic.td18.search;

import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
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
        assertThrows(IllegalStateException.class, () -> WitnessSearch.prefetchCpu(pair(), 2));
    }

    @Test void cpuPrefetchPersistsReplayedClosuresButNeverMarksItsMisses(@TempDir Path path) {
        try (var writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertTrue(WitnessSearch.prefetchCpu(pair(), 2));
                assertFalse(WitnessSearch.prefetchCpu(pair(), 1));
                writer.flush();
                assertTrue(writer.findResult(pair(), 2).isPresent());
                assertTrue(writer.findResult(pair(), 1).isEmpty());
                var markers = new java.util.concurrent.atomic.AtomicInteger();
                writer.forEachObstruction(key -> markers.incrementAndGet());
                assertEquals(0, markers.get());
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
                assertTrue(WitnessSearch.prefetchCpu(pair(),2));writer.flush();
                var batch=java.util.Collections.nCopies(4096,pair());
                var hits=WitnessSearch.lookupBatch(batch,2);
                assertEquals(4096,hits.length);
                for(boolean hit:hits)assertTrue(hit);
                for(boolean hit:WitnessSearch.lookupBatch(batch,1))assertFalse(hit);
            } finally {WitnessSearch.uninstallWriter(writer);}
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

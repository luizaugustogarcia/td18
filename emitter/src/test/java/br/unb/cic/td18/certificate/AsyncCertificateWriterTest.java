package br.unb.cic.td18.certificate;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCertificateWriterTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
    }
    @Test void drainsUnderQueuePressureAndReopens(@TempDir Path directory) throws Exception {
        String previous = System.getProperty("td18.certificateQueueCapacity");
        System.setProperty("td18.certificateQueueCapacity","2");
        try {
            try (var writer = new AsyncCertificateWriter(directory,60); var pool = Executors.newFixedThreadPool(4)) {
                var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i=0;i<100;i++) tasks.add(pool.submit(() -> writer.enqueue(pair(),List.of(Cycle.of(0,1,2)))));
                for (var task : tasks) task.get();
                writer.enqueueObstruction(pair(),10);
            }
            try (var writer = new AsyncCertificateWriter(directory,60)) {
                assertEquals(List.of(Cycle.of(0,1,2)),writer.find(pair(),10).orElseThrow());
                var keys = new ArrayList<byte[]>(); writer.forEachObstruction(keys::add); assertTrue(keys.isEmpty());
            }
        } finally {
            if (previous == null) System.clearProperty("td18.certificateQueueCapacity");
            else System.setProperty("td18.certificateQueueCapacity",previous);
        }
    }
    @Test void witnessRemovesAnUnresolvedKey(@TempDir Path directory) {
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            writer.enqueueObstruction(pair(),10); writer.flush();
            var keys = new ArrayList<byte[]>(); writer.forEachObstruction(keys::add);
            assertEquals(1,keys.size()); assertTrue(writer.find(pair(),10).isEmpty());
            writer.enqueue(pair(),List.of(Cycle.of(0,1,2))); writer.flush();
            keys.clear(); writer.forEachObstruction(keys::add); assertTrue(keys.isEmpty());
        }
    }
    @Test void validLongerWitnessIsABudgetMiss(@TempDir Path directory) {
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            var moves = List.of(Cycle.of(0,1,2),Cycle.of(1,0,2),Cycle.of(0,1,2));
            writer.enqueue(pair(),moves); writer.flush();
            assertTrue(writer.find(pair(),2).isEmpty()); assertEquals(moves,writer.find(pair(),3).orElseThrow());
        }
    }
    @Test void rejectsUnversionedDatabaseAndInvalidWitness(@TempDir Path directory) throws Exception {
        RocksDB.loadLibrary();
        try (var options = new Options().setCreateIfMissing(true); var ignored = RocksDB.open(options,directory.resolve("source").toString())) { }
        assertThrows(IllegalStateException.class, () -> new AsyncCertificateWriter(directory.resolve("source"),60));
        try (var writer = new AsyncCertificateWriter(directory.resolve("proof"),60)) {
            assertThrows(IllegalArgumentException.class, () -> writer.enqueue(pair(),List.of(Cycle.of(0,2,1))));
            assertThrows(IllegalArgumentException.class, () -> writer.enqueueObstruction(pair(),8));
        }
    }
}

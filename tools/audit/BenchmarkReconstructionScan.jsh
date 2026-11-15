import br.unb.cic.td18.avoidance.*;
import br.unb.cic.td18.certificate.*;
import br.unb.cic.td18.model.*;
import br.unb.cic.td18.runtime.*;
import br.unb.cic.td18.search.*;
import java.lang.management.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.rocksdb.*;

/** Read-only production inputs; bounded sample writes disable WAL and flush explicitly. */
class ReconstructionScanBenchmark {
    record Trial(double seconds, long cached, long closed, long residual) { }
    static int positive(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    static void sample(Path source, Path destination, int count) throws Exception {
        try (var inputOptions = new Options().setCreateIfMissing(false);
             var input = RocksDB.openReadOnly(inputOptions, source.toString());
             var reads = new ReadOptions().setFillCache(false);
             var iterator = input.newIterator(reads);
             var outputOptions = new Options().setCreateIfMissing(true);
             var output = RocksDB.open(outputOptions, destination.toString());
             var writes = new WriteOptions().setDisableWAL(true);
             var batch = new WriteBatch();
             var flush = new FlushOptions().setWaitForFlush(true)) {
            String startKey = System.getProperty("td18.scanBenchmarkStartKey", "").trim();
            if (startKey.isEmpty()) iterator.seekToFirst(); else iterator.seek(HexFormat.of().parseHex(startKey));
            if (iterator.isValid()) System.out.println("SAMPLE firstKey=" + HexFormat.of().formatHex(iterator.key()));
            for (int i = 0; i < count; i++) {
                if (!iterator.isValid()) throw new IllegalStateException("Missing sample record " + i);
                byte[] key = iterator.key();
                if (key.length != 15 || key[0] != 2 || key[1] != 10 || key[2] != 19 || iterator.value().length != 0)
                    throw new IllegalArgumentException("Expected versioned 19-symbol budget-10 root keys");
                batch.put(key, new byte[0]);
                if (batch.count() == 65536) { output.write(writes, batch); batch.clear(); }
                iterator.next();
            }
            iterator.status();
            if (batch.count() != 0) output.write(writes, batch);
            output.flush(flush);
        }
    }
    static class Lookup implements AutoCloseable {
        final DBOptions options = new DBOptions().setCreateIfMissing(false).setMaxOpenFiles(128);
        final ColumnFamilyOptions familyOptions = new ColumnFamilyOptions();
        final List<ColumnFamilyHandle> handles = new ArrayList<>();
        final RocksDB database;
        Lookup(Path directory) throws Exception {
            database = RocksDB.openReadOnly(options, directory.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), familyOptions)), handles);
            if (!Arrays.equals("joint-selection-v3".getBytes(StandardCharsets.UTF_8),
                    database.get(handles.getFirst(), "td18-schema".getBytes(StandardCharsets.UTF_8))))
                throw new IllegalArgumentException("Unsupported certificate schema");
        }
        boolean[] apply(List<DiskStateSet.State> states) {
            var pairs = states.stream().map(state -> state.pair().canonicalRepresentative()).toList();
            var keys = pairs.stream().map(CertificateCodec::keyFor).toList();
            try {
                var encoded = database.multiGetAsList(Collections.nCopies(keys.size(), handles.getFirst()), keys);
                if (encoded.size() != states.size()) throw new IllegalStateException("Incomplete multiGet");
                boolean[] hits = new boolean[states.size()];
                for (int i = 0; i < hits.length; i++) if (encoded.get(i) != null) {
                    int required = CertificateValidator.requiredBudget(pairs.get(i), CertificateCodec.decodeResult(encoded.get(i)));
                    if (required > 10) throw new IllegalArgumentException("Invalid stored certificate");
                    hits[i] = required <= states.get(i).maximumMoves();
                }
                return hits;
            } catch (RocksDBException failure) { throw new IllegalStateException(failure); }
        }
        public void close() {
            handles.forEach(ColumnFamilyHandle::close); database.close(); familyOptions.close(); options.close();
        }
    }
    static Trial trial(boolean parallel, DiskStateSet states, Lookup lookup, int workers,
                       ConcurrentHashMap<ByteBuffer, Byte> expected) {
        long started = System.nanoTime();
        long cpuStarted = ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        var cached = new LongAdder(); var closed = new LongAdder(); var residual = new LongAdder();
        BiConsumer<DiskStateSet.State, Byte> record = (state, result) -> {
            var key = ByteBuffer.wrap(CertificateCodec.keyFor(state.pair()));
            if (parallel) {
                if (!Objects.equals(expected.remove(key), result)) throw new AssertionError("Outcome mismatch or duplicate key");
            } else if (expected.putIfAbsent(key, result) != null) throw new AssertionError("Duplicate legacy callback");
        };
        Consumer<DiskStateSet.State> hit = state -> { record.accept(state, (byte) 0); cached.increment(); };
        Consumer<DiskStateSet.State> miss = state -> {
            var result = CpuPrefixSearch.search(state.pair(), state.maximumMoves(), 5, 8192);
            if (result.isPresent()) {
                CertificateValidator.validate(state.pair(), result.orElseThrow(), state.maximumMoves());
                record.accept(state, (byte) 1); closed.increment();
            } else { record.accept(state, (byte) 2); residual.increment(); }
        };
        if (parallel) states.forEachConcurrently(workers, lookup::apply, hit, miss);
        else try (var tasks = new BoundedTasks(workers, "legacy-scan-")) {
            states.forEachBatch(batch -> {
                boolean[] hits = lookup.apply(batch);
                for (int i = 0; i < hits.length; i++) if (hits[i]) hit.accept(batch.get(i));
                for (int i = 0; i < hits.length; i++) if (!hits[i]) {
                    var state = batch.get(i); tasks.submit(() -> miss.accept(state));
                }
            });
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        double cpu = (((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() - cpuStarted) / 1e9;
        long count = cached.sum() + closed.sum() + residual.sum();
        if (count != states.knownSize() || parallel && !expected.isEmpty()) throw new AssertionError("Missing scan callbacks");
        System.out.printf(Locale.ROOT, "SCAN mode=%s records=%d cached=%d cpuClosed=%d residual=%d seconds=%.6f recordsPerSecond=%.3f averageCpuCores=%.2f%n",
                parallel ? "parallel" : "legacy", count, cached.sum(), closed.sum(), residual.sum(), seconds, count / seconds, cpu / seconds);
        return new Trial(seconds, cached.sum(), closed.sum(), residual.sum());
    }
    static void run() throws Exception {
        RocksDB.loadLibrary();
        int count = positive("td18.scanBenchmarkRecords", 1048579);
        int workers = positive("td18.scanBenchmarkWorkers", Runtime.getRuntime().availableProcessors());
        int rounds = positive("td18.scanBenchmarkRounds", 2);
        Path source = Path.of(System.getProperty("td18.scanBenchmarkSource"));
        Path certificates = Path.of(System.getProperty("td18.scanBenchmarkCertificates"));
        Path output = Files.createTempDirectory("td18-reconstruction-scan-");
        Path sampled = output.resolve("sample");
        System.out.println("FORMAT td18-reconstruction-scan-benchmark-v1 source=" + source + " output=" + output
                + " records=" + count + " workers=" + workers + " rounds=" + rounds);
        sample(source, sampled, count);
        try (var states = DiskStateSet.openSealed(sampled, count); var lookup = new Lookup(certificates)) {
            for (int round = 1; round <= rounds; round++) {
                var expected = new ConcurrentHashMap<ByteBuffer, Byte>();
                var legacy = trial(false, states, lookup, workers, expected);
                var parallel = trial(true, states, lookup, workers, expected);
                if (legacy.cached() != parallel.cached() || legacy.closed() != parallel.closed() || legacy.residual() != parallel.residual())
                    throw new AssertionError("Classification totals disagree");
                System.out.printf(Locale.ROOT, "COMPARE round=%d exactPerRecordOutcomesEqual=true checkedRecords=%d speedup=%.3f%n",
                        round, count, legacy.seconds() / parallel.seconds());
            }
        }
    }
}
int status = 1;
try { ReconstructionScanBenchmark.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status

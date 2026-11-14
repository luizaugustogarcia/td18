// Compare individual and batched inverse-wave persistence through JShell.
// Requires td18.inverseBenchmarkQueue pointing to an immutable after-one queue sample.
// Cases=237, Workers=availableProcessors(), Rounds=3 and Warmup=16 are defaults.
// Each comparison checks complete raw RocksDB inventories after sealing.
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.lang.management.*;
import br.unb.cic.td18.avoidance.*;
import br.unb.cic.td18.certificate.*;
import br.unb.cic.td18.joint.*;
import br.unb.cic.td18.model.*;
import br.unb.cic.td18.runtime.*;
import org.rocksdb.*;

class InverseBatchingBenchmark {
    record Input(CyclicTargetPair pair, int budget) {}
    record Trial(String mode, Path directory, long sealed, long[] generated, double generationSeconds, double wallSeconds) {}
    static int positive(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    static List<Input> readInput(Path path, int count) throws Exception {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            long originalBytes = file.length();
            if (!file.readUTF().equals("td-reconstruction-queue-v1")
                    || !file.readUTF().equals(ErikssonRoute.ID)
                    || file.readInt() != 19 || file.readInt() != 10 || file.readInt() != 16)
                throw new IllegalArgumentException("Incompatible queue header");
            long declared = file.readLong(), headerBytes = file.getFilePointer();
            long payload = originalBytes - headerBytes;
            if (payload < 0 || payload % 16 != 0 || declared < 0 || declared != payload / 16)
                throw new IllegalArgumentException("Snapshot must have a complete consistent record count");
            if (count > declared) throw new IllegalArgumentException("Missing benchmark records");
            var inputs = new ArrayList<Input>(count);
            byte[] record = new byte[16];
            var digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < count; index++) {
                file.readFully(record); digest.update(record);
                int length = Byte.toUnsignedInt(record[0]);
                if (length < 4 || length > 15 || record[1] != 2)
                    throw new IllegalArgumentException("Invalid state key version/length at " + index);
                for (int i = length + 1; i < 16; i++) if (record[i] != 0)
                    throw new IllegalArgumentException("Nonzero record padding at " + index);
                int budget = Byte.toUnsignedInt(record[2]), n = Byte.toUnsignedInt(record[3]);
                if (budget != 9 || (n != 18 && n != 19))
                    throw new IllegalArgumentException("Expected after-one states on 18/19 symbols with budget 9");
                var pair = CertificateCodec.pairForKey(Arrays.copyOfRange(record, 4, length + 1), n);
                for (int symbol = 0; symbol < n; symbol++) if (pair.getOmega().image(symbol) == symbol)
                    throw new IllegalArgumentException("Fixed symbol in inverse input " + index);
                inputs.add(new Input(pair, budget));
            }
            if (file.length() != originalBytes) throw new IllegalStateException("Input changed during sampling");
            System.out.println("INPUT snapshot=" + path + " selected=" + count + " totalRecords=" + declared
                    + " selectedRecordSha256=" + HexFormat.of().formatHex(digest.digest()));
            return List.copyOf(inputs);
        }
    }
    static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }
    static Trial runTrial(String mode, Path directory, List<Input> inputs, int workers) throws Exception {
        final boolean batched = mode.startsWith("batched");
        long[] generated = new long[inputs.size()];
        long started = System.nanoTime(), cpuStarted = cpuNanos();
        long sealStarted, sealed;
        try (var set = new DiskStateSet(directory)) {
            try (var tasks = new BoundedTasks(workers, "inverse-benchmark-")) {
                for (int index = 0; index < inputs.size(); index++) {
                    final int position = index;
                    tasks.submit(() -> {
                        var input = inputs.get(position);
                        int gain = input.pair().getBeta().size() == 18 ? 1 : 0;
                        Consumer<Consumer<CyclicTargetPair>> generator = sink ->
                            ErikssonInverseReconstruction.preimages(input.pair(), gain, parent -> {
                                generated[position]++; sink.accept(parent);
                            });
                        if (batched) set.addGenerated(10, generator);
                        else generator.accept(parent -> set.add(parent, 10));
                    });
                }
            }
            sealStarted = System.nanoTime();
            sealed = set.seal();
        }
        double wall = (System.nanoTime() - started) / 1e9;
        double cpuSeconds = (cpuNanos() - cpuStarted) / 1e9;
        double generation = (sealStarted - started) / 1e9;
        long total = 0, partial = 0;
        for (long value : generated) {
            if (value <= 0) throw new IllegalStateException("Missing generated predecessor inventory");
            total = Math.addExact(total, value); if (value % 4096 != 0) partial++;
        }
        System.out.printf(Locale.ROOT, "TRIAL mode=%s inputs=%d workers=%d generated=%d sealed=%d partialBatchSources=%d generationSeconds=%.6f totalSeconds=%.6f inverseInputsPerSecond=%.3f totalInputsPerSecond=%.3f generatedPerSecond=%.3f processCpuSeconds=%.3f averageCpuCores=%.2f directory=%s%n",
                mode, inputs.size(), workers, total, sealed, partial, generation, wall,
                inputs.size()/generation, inputs.size()/wall, total/generation, cpuSeconds, cpuSeconds/wall, directory);
        return new Trial(mode, directory, sealed, generated, generation, wall);
    }
    static void compare(Trial left, Trial right) throws Exception {
        if (left.sealed() != right.sealed() || !Arrays.equals(left.generated(), right.generated()))
            throw new IllegalStateException("Generated or sealed inventories disagree");
        long count = 0;
        var digest = MessageDigest.getInstance("SHA-256");
        try (var options = new Options().setCreateIfMissing(false);
             var a = RocksDB.openReadOnly(options, left.directory().toString());
             var b = RocksDB.openReadOnly(options, right.directory().toString());
             var reads = new ReadOptions().setFillCache(false);
             var ai = a.newIterator(reads); var bi = b.newIterator(reads)) {
            ai.seekToFirst(); bi.seekToFirst();
            while (ai.isValid() && bi.isValid()) {
                byte[] aKey = ai.key(), bKey = bi.key();
                if (!Arrays.equals(aKey, bKey) || !Arrays.equals(ai.value(), bi.value()))
                    throw new IllegalStateException("Exact inventory mismatch at record " + count);
                if (aKey.length < 4 || aKey[0] != 2 || aKey[1] != 10 || aKey[2] != 19
                        || ai.value().length != 0)
                    throw new IllegalStateException("Malformed sealed root key at record " + count);
                digest.update(aKey); count++; ai.next(); bi.next();
            }
            if (ai.isValid() || bi.isValid() || count != left.sealed())
                throw new IllegalStateException("Missing sealed inventory records");
            ai.status(); bi.status();
        }
        Trial individual = left.mode().startsWith("individual") ? left : right;
        Trial batched = left.mode().startsWith("batched") ? left : right;
        System.out.printf(Locale.ROOT, "COMPARE left=%s right=%s exactInventoryEqual=true checkedRecords=%d inventorySha256=%s generationSpeedup=%.4f totalSpeedup=%.4f%n",
                left.mode(), right.mode(), count, HexFormat.of().formatHex(digest.digest()),
                individual.generationSeconds()/batched.generationSeconds(), individual.wallSeconds()/batched.wallSeconds());
    }
    static void run() throws Exception {
        int count = positive("td18.inverseBenchmarkCases", 237);
        int workers = positive("td18.inverseBenchmarkWorkers", Runtime.getRuntime().availableProcessors());
        int rounds = positive("td18.inverseBenchmarkRounds", 3);
        int warmup = Integer.getInteger("td18.inverseBenchmarkWarmup", Math.min(count, 16));
        if (warmup < 0 || warmup > count) throw new IllegalArgumentException("Warmup must be between zero and the case count");
        String queue = System.getProperty("td18.inverseBenchmarkQueue", "").trim();
        if (queue.isEmpty()) throw new IllegalArgumentException("Set td18.inverseBenchmarkQueue to an immutable sample");
        var inputs = readInput(Path.of(queue), count);
        Path root = Files.createTempDirectory(Path.of(System.getProperty("td18.inverseBenchmarkOutput",
                System.getProperty("java.io.tmpdir"))), "inverse-batching-");
        System.out.println("FORMAT td18-inverse-batching-benchmark-v1 workers=" + workers + " rounds=" + rounds
                + " warmup=" + warmup + " batchKeys=4096 bufferKeys=" + System.getProperty("td18.reconstructionBufferKeys", "16777216")
                + " output=" + root);
        if (warmup > 0) {
            var subset = inputs.subList(0, warmup);
            var a = runTrial("individual-warmup", root.resolve("individual-warmup"), subset, workers);
            var b = runTrial("batched-warmup", root.resolve("batched-warmup"), subset, workers);
            compare(a, b);
        }
        for (int round = 1; round <= rounds; round++) {
            String first = round % 2 == 1 ? "individual" : "batched";
            String second = round % 2 == 1 ? "batched" : "individual";
            var a = runTrial(first + "-" + round, root.resolve(first + "-" + round), inputs, workers);
            var b = runTrial(second + "-" + round, root.resolve(second + "-" + round), inputs, workers);
            compare(a, b);
        }
    }
}
int status = 1;
try { InverseBatchingBenchmark.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status

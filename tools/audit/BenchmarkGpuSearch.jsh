// Run after tools/td18 build, with the emitter classpath and a configured CUDA library.
// Example (one device, no certificate database writes):
// jshell --class-path "emitter/target/classes:$(cat emitter/target/jshell-classpath)" \
//   -R-Djava.library.path=emitter/target/native -R-Dtd18.gpuDevices=0 \
//   -R-Dtd18.benchmarkQueue=/tmp/queue-snapshot.bin tools/audit/BenchmarkGpuSearch.jsh
// td18.benchmarkCases=128, Offset=0, Stride=1 select the same snapshot records.
// td18.benchmarkRounds=3, Warmup=2*totalSlots, Depth=5, Millis=0 control searches.
// Property names after the first retain the td18.benchmark prefix.
// td18.gpuSlots=8 and td18.gpuBudgetMB=128 configure native search resources.
// Without a queue, Symbols=16, Budget=8, Seed=180016 generate fixed-point-free
// cyclic-target pairs; these synthetic cases do not model the CPU-miss queue.
// td18.benchmarkValidateOnly=true validates input without initializing CUDA.
// td18.benchmarkDetails=true prints per-case outcomes in deterministic order.
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.*;
import br.unb.cic.td18.base.*;
import br.unb.cic.td18.certificate.*;
import br.unb.cic.td18.joint.*;
import br.unb.cic.td18.model.*;
import br.unb.cic.td18.permutation.*;
import br.unb.cic.td18.runtime.*;

class GpuSearchBenchmark {
    record Case(CyclicTargetPair pair, int budget) {}
    record Result(GPUSortingSearch.Disposition disposition, int moves, long nanos) {}
    static int positive(String property, int fallback) {
        int value = Integer.getInteger(property, fallback);
        if (value < 1) throw new IllegalArgumentException(property + " must be positive");
        return value;
    }
    static long nonnegative(String property, long fallback) {
        long value = Long.getLong(property, fallback);
        if (value < 0) throw new IllegalArgumentException(property + " must be nonnegative");
        return value;
    }
    static Case decode(byte[] record, long index) {
        int length = Byte.toUnsignedInt(record[0]);
        if (length < 4 || length >= record.length)
            throw new IllegalArgumentException("Malformed queue key length at record " + index);
        for (int i = length + 1; i < record.length; i++)
            if (record[i] != 0) throw new IllegalArgumentException("Nonzero queue padding at record " + index);
        if (record[1] != 2) throw new IllegalArgumentException("Unsupported state key version at record " + index);
        int budget = Byte.toUnsignedInt(record[2]), domain = Byte.toUnsignedInt(record[3]);
        if (domain < 3 || domain > ErikssonRoute.ROOT_SYMBOLS || budget < 1 || budget > ErikssonRoute.ROOT_BUDGET)
            throw new IllegalArgumentException("Invalid queue domain/budget at record " + index);
        var pair = CertificateCodec.pairForKey(Arrays.copyOfRange(record, 4, length + 1), domain);
        for (int symbol = 0; symbol < domain; symbol++)
            if (pair.getOmega().image(symbol) == symbol)
                throw new IllegalArgumentException("Uncontracted fixed point at record " + index);
        return new Case(pair, budget);
    }
    static List<Case> queueCases(Path path, int count) throws Exception {
        // A snapshot may preserve the live queue's -1 count. It is benchmark
        // input only: no completion, checkpoint or proof claim follows from it.
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            long size = file.length();
            if (!file.readUTF().equals("td-reconstruction-queue-v1")
                    || !file.readUTF().equals(ErikssonRoute.ID)
                    || file.readInt() != ErikssonRoute.ROOT_SYMBOLS
                    || file.readInt() != ErikssonRoute.ROOT_BUDGET)
                throw new IllegalArgumentException("Incompatible reconstruction queue header");
            int recordBytes = file.readInt();
            int expectedBytes = 4 + (5 * ErikssonRoute.ROOT_SYMBOLS + 7) / 8;
            if (recordBytes != expectedBytes) throw new IllegalArgumentException("Incompatible queue record size");
            long finalCount = file.readLong(), headerBytes = file.getFilePointer();
            long payloadBytes = size - headerBytes;
            if (payloadBytes < 0 || payloadBytes % recordBytes != 0)
                throw new IllegalArgumentException("Truncated queue snapshot");
            long available = payloadBytes / recordBytes;
            if (finalCount < -1 || (finalCount >= 0 && finalCount != available))
                throw new IllegalArgumentException("Queue count disagrees with snapshot length");
            long first = nonnegative("td18.benchmarkOffset", 0);
            int stride = positive("td18.benchmarkStride", 1);
            long last = Math.addExact(first, Math.multiplyExact((long) count - 1, stride));
            if (last >= available) throw new IllegalArgumentException("Missing benchmark records: last requested=" + last + ", available=" + available);
            var cases = new ArrayList<Case>(count);
            byte[] record = new byte[recordBytes];
            for (int i = 0; i < count; i++) {
                long index = first + (long) i * stride;
                file.seek(Math.addExact(headerBytes, Math.multiplyExact(index, recordBytes)));
                file.readFully(record);
                cases.add(decode(record, index));
            }
            if (file.length() != size) throw new IllegalStateException("Queue changed while reading; provide an immutable snapshot");
            System.out.printf(Locale.ROOT, "INPUT queue=%s records=%d sealed=%s selected=%d offset=%d stride=%d%n",
                    path, available, finalCount >= 0, count, first, stride);
            return List.copyOf(cases);
        }
    }
    static List<Case> generatedCases(int count) {
        int n = positive("td18.benchmarkSymbols", 16), budget = positive("td18.benchmarkBudget", 8);
        if (n < 3 || n > 19 || budget > 10) throw new IllegalArgumentException("Generated domain must be 3..19 and budget 1..10");
        long seed = Long.getLong("td18.benchmarkSeed", 180016L);
        var random = new Random(seed);
        var cases = new ArrayList<Case>(count);
        var beta = Cycle.of(IntStream.range(0, n).toArray());
        while (cases.size() < count) {
            int[] order = IntStream.range(0, n).toArray();
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), value = order[i]; order[i] = order[j]; order[j] = value;
            }
            // Define rho by a shuffled full cycle and omega = rho * beta inverse.
            var omega = PermutationGroups.computeProduct(Cycle.of(order), beta.getInverse());
            boolean fixed = false;
            for (int i = 0; i < n; i++) if (omega.image(i) == i) { fixed = true; break; }
            if (!fixed) cases.add(new Case(CyclicTargetPair.of(omega, beta), budget));
        }
        System.out.printf(Locale.ROOT, "INPUT generated=true selected=%d symbols=%d budget=%d seed=%d%n", count, n, budget, seed);
        return List.copyOf(cases);
    }
    static String fingerprint(List<Case> cases) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        for (var input : cases) {
            hash.update((byte) input.budget());
            hash.update((byte) input.pair().getBeta().size());
            hash.update(CertificateCodec.keyFor(input.pair()));
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static Result search(GPUSortingSearch gpu, Case input, int depth, long millis) {
        // Final roots retain their full budget and have no timeout.
        int actualDepth = input.budget() == 10 ? 10 : Math.min(depth, input.budget());
        long actualMillis = input.budget() == 10 ? 0 : millis;
        byte[] beta = new byte[input.pair().getBeta().size()];
        for (int i = 0; i < beta.length; i++) beta[i] = (byte) input.pair().getBeta().get(i);
        long started = System.nanoTime();
        var result = gpu.search(beta, new OneLinePermutation(input.pair().getOmega().getOneLineNotation()),
                input.budget(), true, actualMillis, actualDepth);
        long nanos = System.nanoTime() - started;
        if (result.disposition() == GPUSortingSearch.Disposition.COMPLETE
                || result.disposition() == GPUSortingSearch.Disposition.DIAMETER_CLOSURE) {
            var moves = result.moves().stream().map(Cycle::of).toList();
            var certificate = result.disposition() == GPUSortingSearch.Disposition.COMPLETE
                    ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
            CertificateValidator.validate(input.pair(), certificate, input.budget());
        } else if (!result.moves().isEmpty()) throw new IllegalStateException("Unresolved result contains a witness");
        return new Result(result.disposition(), result.moves().size(), nanos);
    }
    static void run() throws Exception {
        int count = positive("td18.benchmarkCases", 128);
        if (count > 1000000) throw new IllegalArgumentException("Benchmark cases exceed the bounded in-memory limit");
        String queue = System.getProperty("td18.benchmarkQueue", "").trim();
        var cases = queue.isEmpty() ? generatedCases(count) : queueCases(Path.of(queue), count);
        var domains = new TreeMap<String, Integer>();
        for (var input : cases) domains.merge(input.pair().getBeta().size() + "/" + input.budget(), 1, Integer::sum);
        System.out.println("FORMAT td18-gpu-benchmark-v1 cases=" + count + " inputSha256=" + fingerprint(cases) + " domain/budget=" + domains);
        if (Boolean.getBoolean("td18.benchmarkValidateOnly")) return;
        int[] devices = Arrays.stream(System.getProperty("td18.gpuDevices", "0").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int slots = positive("td18.gpuSlots", 8), budgetMB = positive("td18.gpuBudgetMB", 128);
        int workers = positive("td18.benchmarkWorkers", Math.max(Runtime.getRuntime().availableProcessors(), devices.length * slots * 2));
        int depth = positive("td18.benchmarkDepth", 5), rounds = positive("td18.benchmarkRounds", 3);
        if (depth > 10) throw new IllegalArgumentException("Benchmark depth must be 1..10");
        long millis = nonnegative("td18.benchmarkMillis", 0);
        int warmup = Math.toIntExact(nonnegative("td18.benchmarkWarmup", Math.min(count, devices.length * slots * 2)));
        if (System.getProperty("td18.gpuStatsPeriodSeconds") == null) System.setProperty("td18.gpuStatsPeriodSeconds", "0");
        GPUSortingSearch.init(devices, slots, Math.multiplyExact((long) budgetMB, 1024L * 1024));
        var gpu = GPUSortingSearch.getInstance();
        System.out.printf(Locale.ROOT, "CONFIG devices=%s slots/device=%d queueMiB/slot=%d workers=%d intermediateDepth=%d intermediateMillis=%d rounds=%d warmup=%d finalRootDepth=10 finalRootMillis=0%n",
                Arrays.toString(devices), slots, budgetMB, workers, depth, millis, rounds, warmup);
        try (var tasks = new BoundedTasks(workers, "gpu-benchmark-warmup-")) {
            for (int i = 0; i < warmup; i++) {
                var input = cases.get(i % count); tasks.submit(() -> search(gpu, input, depth, millis));
            }
        }
        for (int round = 1; round <= rounds; round++) {
            var results = new Result[count];
            long started = System.nanoTime();
            try (var tasks = new BoundedTasks(workers, "gpu-benchmark-")) {
                for (int i = 0; i < count; i++) {
                    final int index = i; tasks.submit(() -> results[index] = search(gpu, cases.get(index), depth, millis));
                }
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            var outcomes = new EnumMap<GPUSortingSearch.Disposition, Long>(GPUSortingSearch.Disposition.class);
            for (var kind : GPUSortingSearch.Disposition.values()) outcomes.put(kind, 0L);
            long[] latencies = new long[count];
            for (int i = 0; i < count; i++) {
                var result = Objects.requireNonNull(results[i], "Missing benchmark result " + i);
                outcomes.merge(result.disposition(), 1L, Long::sum); latencies[i] = result.nanos();
                if (Boolean.getBoolean("td18.benchmarkDetails"))
                    System.out.printf(Locale.ROOT, "CASE round=%d index=%d symbols=%d budget=%d outcome=%s moves=%d hostMillis=%.3f%n",
                            round, i, cases.get(i).pair().getBeta().size(), cases.get(i).budget(), result.disposition(), result.moves(), result.nanos()/1e6);
            }
            Arrays.sort(latencies);
            long certified = outcomes.get(GPUSortingSearch.Disposition.COMPLETE) + outcomes.get(GPUSortingSearch.Disposition.DIAMETER_CLOSURE);
            System.out.printf(Locale.ROOT, "RESULT round=%d completed=%d seconds=%.6f searchesPerSecond=%.3f certified=%d certifiedPerSecond=%.3f outcomes=%s hostP50Millis=%.3f hostP95Millis=%.3f hostMaxMillis=%.3f replayedAllWitnesses=true%n",
                    round, count, seconds, count/seconds, certified, certified/seconds, outcomes,
                    latencies[(count-1)/2]/1e6, latencies[(int)Math.ceil(count*0.95)-1]/1e6, latencies[count-1]/1e6);
        }
        GPUSortingSearch.shutdownStatsReporter();
    }
}
int status = 1;
try { GpuSearchBenchmark.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status

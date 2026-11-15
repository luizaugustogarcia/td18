// JDK 21 / JShell, checker classpath only. Uses every available CPU by default.
// Set td18.canonicalBenchmarkInput to an immutable versioned sample of after-one keys.
// Compares the previous full-word orbit minimum with production canonicalization.
// Each trial regenerates both-sided inverse parents, normalizes them and encodes
// every output. Complete per-source key multisets must match, including duplicates.
// This CPU benchmark excludes certificate lookups, RocksDB persistence and full proof coverage.
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.lang.management.*;
import br.unb.cic.td18.certificate.*;
import br.unb.cic.td18.joint.*;
import br.unb.cic.td18.model.*;
import br.unb.cic.td18.runtime.*;

class CheckerCanonicalizationBenchmark {
    record Input(int n, byte[] key) { }
    record Trial(String mode, byte[][][] keys, long generated, double seconds, double cpuSeconds) { }

    static int positive(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static List<Input> readInput(Path path) throws Exception {
        var lines = Files.readAllLines(path);
        if (lines.size() < 3 || !lines.getFirst().equals("td18-checker-inverse-benchmark-input-v1"))
            throw new IllegalArgumentException("Missing/incompatible benchmark input header");
        int count = Integer.parseInt(lines.get(1));
        if (count < 1 || lines.size() != count + 2)
            throw new IllegalArgumentException("Incomplete benchmark input inventory");
        var inputs = new ArrayList<Input>(count);
        var domains = new TreeMap<Integer, Integer>();
        for (String line : lines.subList(2, lines.size())) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Malformed input record");
            int n = Integer.parseInt(fields[0]), budget = Integer.parseInt(fields[1]);
            if ((n != 18 && n != 19) || budget != 9)
                throw new IllegalArgumentException("Expected after-one states with budget nine");
            byte[] key = HexFormat.of().parseHex(fields[2]);
            var pair = CertificateCodec.pairForKey(key, n);
            for (int x = 0; x < n; x++) if (pair.getOmega().image(x) == x)
                throw new IllegalArgumentException("Contract fixed points before inverse generation");
            inputs.add(new Input(n, key));
            domains.merge(n, 1, Integer::sum);
        }
        if (domains.size() != 2) throw new IllegalArgumentException("Both gain branches must be sampled");
        System.out.println("INPUT cases=" + count + " domains=" + domains + " sha256="
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        return List.copyOf(inputs);
    }

    // Preserved pre-optimization algorithm. It deliberately uses the unchanged
    // transform/validation API, independently of the new primitive comparison.
    static StandardizedEncodingWord reference(CyclicTargetCandidate candidate) {
        return candidate.dihedralOrbitWords().map(DihedralOrbitWord::word)
                .min((left, right) -> Arrays.compare(fixedColorSymbols(left), fixedColorSymbols(right)))
                .orElseThrow();
    }

    static int[] fixedColorSymbols(StandardizedEncodingWord word) {
        int[] sizes = new int[word.length() + 1];
        boolean[] oriented = new boolean[word.length() + 1];
        int count = 0;
        for (int i = 0; i < word.length(); i++) {
            int label = word.cycleLabelAt(i);
            if (sizes[label]++ == 0) count++;
            oriented[label] |= word.rankAt(i) != 0;
        }
        Integer[] colors = new Integer[count];
        for (int i = 0; i < count; i++) colors[i] = i + 1;
        Arrays.sort(colors, (a, b) -> {
            int size = Integer.compare(sizes[b], sizes[a]);
            if (size != 0) return size;
            int orientation = Boolean.compare(oriented[a], oriented[b]);
            return orientation != 0 ? orientation : Integer.compare(a, b);
        });
        int[] bases = new int[count + 1];
        int next = 0;
        for (int label : colors) {
            bases[label] = next;
            next += oriented[label] ? sizes[label] : 1;
        }
        int[] symbols = new int[word.length()];
        for (int i = 0; i < symbols.length; i++) {
            int label = word.cycleLabelAt(i);
            symbols[i] = bases[label] + (oriented[label] ? word.rankAt(i) - 1 : 0);
        }
        return symbols;
    }

    static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    static Trial trial(String mode, List<Input> inputs, int workers) {
        boolean baseline = mode.startsWith("baseline");
        byte[][][] keys = new byte[inputs.size()][][];
        long cpuStarted = cpuNanos(), started = System.nanoTime();
        try (var tasks = new BoundedTasks(workers, "canonical-benchmark-")) {
            for (int i = 0; i < inputs.size(); i++) {
                final int index = i;
                tasks.submit(() -> {
                    var input = inputs.get(index);
                    var child = CertificateCodec.pairForKey(input.key(), input.n());
                    var generated = new ArrayList<byte[]>();
                    ErikssonInverseReconstruction.preimages(child, input.n() == 18 ? 1 : 0, parent -> {
                        var word = baseline ? reference(parent) : TwistedBraceletKey.of(parent).canonicalWord();
                        var normalized = CyclicTargetPair.fromEncodingWord(word);
                        generated.add(CertificateCodec.keyFor(normalized));
                    });
                    keys[index] = generated.toArray(byte[][]::new);
                    Arrays.sort(keys[index], Arrays::compareUnsigned);
                });
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        double cpuSeconds = (cpuNanos() - cpuStarted) / 1e9;
        long generated = 0;
        for (byte[][] source : keys) {
            if (source == null) throw new IllegalStateException("Missing inverse source result");
            generated = Math.addExact(generated, source.length);
        }
        if (generated == 0) throw new IllegalStateException("Empty benchmark workload");
        System.out.printf(Locale.ROOT,
                "TRIAL mode=%s cases=%d workers=%d generated=%d seconds=%.6f inputsPerSecond=%.3f generatedPerSecond=%.3f cpuSeconds=%.3f averageCpuCores=%.2f%n",
                mode, inputs.size(), workers, generated, seconds, inputs.size() / seconds,
                generated / seconds, cpuSeconds, cpuSeconds / seconds);
        return new Trial(mode, keys, generated, seconds, cpuSeconds);
    }

    static void compare(Trial expected, Trial actual) throws Exception {
        if (expected.generated() != actual.generated() || expected.keys().length != actual.keys().length)
            throw new IllegalStateException("Inverse inventory totals differ");
        var digest = MessageDigest.getInstance("SHA-256");
        for (int source = 0; source < expected.keys().length; source++) {
            byte[][] a = expected.keys()[source], b = actual.keys()[source];
            if (a.length != b.length) throw new IllegalStateException("Missing parents for source " + source);
            for (int i = 0; i < a.length; i++) {
                if (!Arrays.equals(a[i], b[i]))
                    throw new IllegalStateException("Changed canonical parent: source=" + source + " index=" + i);
                digest.update(b[i]);
            }
        }
        System.out.println("COMPARE expected=" + expected.mode() + " actual=" + actual.mode()
                + " exactPerSourceKeyMultisetsEqual=true generated=" + actual.generated()
                + " sha256=" + HexFormat.of().formatHex(digest.digest()));
    }

    static double median(double[] values) {
        var sorted = values.clone(); Arrays.sort(sorted);
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    static void run() throws Exception {
        String path = System.getProperty("td18.canonicalBenchmarkInput", "");
        if (path.isBlank()) throw new IllegalArgumentException("Set td18.canonicalBenchmarkInput");
        int workers = positive("td18.canonicalBenchmarkWorkers", Runtime.getRuntime().availableProcessors());
        int rounds = positive("td18.canonicalBenchmarkRounds", 3);
        var inputs = readInput(Path.of(path));
        int warmup = Math.min(inputs.size(), 2 * workers);
        System.out.println("FORMAT td18-checker-canonicalization-benchmark-v1 java=" + Runtime.version()
                + " workers=" + workers + " rounds=" + rounds + " warmup=" + warmup
                + " scope=inverse-generation-canonicalization-normalization-encoding-sorting");
        var warm = inputs.subList(0, warmup);
        compare(trial("baseline-warmup", warm, workers), trial("optimized-warmup", warm, workers));
        double[] baseline = new double[rounds], optimized = new double[rounds];
        Trial reference = null;
        long generated = 0;
        for (int round = 0; round < rounds; round++) {
            Trial first = trial((round % 2 == 0 ? "baseline-" : "optimized-") + round, inputs, workers);
            Trial second = trial((round % 2 == 0 ? "optimized-" : "baseline-") + round, inputs, workers);
            if (reference == null) reference = first;
            compare(reference, first); compare(reference, second);
            Trial old = first.mode().startsWith("baseline") ? first : second;
            Trial fast = first.mode().startsWith("optimized") ? first : second;
            baseline[round] = old.seconds(); optimized[round] = fast.seconds();
            generated = old.generated();
        }
        double old = median(baseline), fast = median(optimized), speedup = old / fast;
        System.out.printf(Locale.ROOT,
                "SUMMARY baselineMedianSeconds=%.6f optimizedMedianSeconds=%.6f baselineGeneratedPerSecond=%.3f optimizedGeneratedPerSecond=%.3f speedup=%.4f throughputImprovementPercent=%.2f elapsedReductionPercent=%.2f exactKeyEquality=true%n",
                old, fast, generated / old, generated / fast, speedup,
                100 * (speedup - 1), 100 * (1 - fast / old));
    }
}
int status = 1;
try { CheckerCanonicalizationBenchmark.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status

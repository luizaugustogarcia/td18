import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.joint.JointRootGenerator;
import br.unb.cic.td18.joint.SupportClearing;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.search.WitnessSearch;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Sample benchmark only: no exhaustive-generation or proof-completion claim. */
public final class BenchmarkWitnessSearch {
    private static final class SampleComplete extends RuntimeException {
        SampleComplete() { super(null, null, false, false); }
    }

    public static void main(String[] args) throws Exception {
        int[] partition = args.length > 0
                ? Arrays.stream(args[0].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[]{9, 5, 5};
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        if (samples < 1 || threads < 1 || Arrays.stream(partition).sum() != 19)
            throw new IllegalArgumentException("Use a 19-symbol root partition and positive samples/threads");
        var pairs = new ArrayList<CyclicTargetPair>();
        try {
            JointRootGenerator.generateResiduals(partition, pair -> {
                if (!SupportClearing.closes(pair)) pairs.add(pair);
                if (pairs.size() == samples) throw new SampleComplete();
            });
        } catch (SampleComplete complete) { /* Deterministic prefix of this generation tree. */ }
        if (pairs.isEmpty()) throw new IllegalArgumentException("Partition has no sampled direct obligations");
        var directory = Files.createTempDirectory("td18-search-benchmark-");
        int warmup = Math.min(2000, pairs.size() / 4);
        try (var writer = new AsyncCertificateWriter(directory.resolve("certificates"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                for (int index = 0; index < warmup; index++) WitnessSearch.search(pairs.get(index), 10);
                writer.flush();
                var next = new AtomicInteger(warmup);
                var settled = new AtomicInteger();
                var residual = new AtomicInteger();
                long started = System.nanoTime();
                try (var workers = Executors.newFixedThreadPool(threads)) {
                    var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                    for (int thread = 0; thread < threads; thread++) jobs.add(workers.submit(() -> {
                        for (int index; (index = next.getAndIncrement()) < pairs.size();) {
                            var result = WitnessSearch.search(pairs.get(index), 10);
                            if (result.disposition() == WitnessSearch.Disposition.RESIDUAL) residual.incrementAndGet();
                            else settled.incrementAndGet();
                        }
                    }));
                    for (var job : jobs) job.get();
                }
                writer.flush();
                double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                System.out.printf(java.util.Locale.ROOT,
                        "partition=%s samples=%d warmup=%d threads=%d settled=%d residual=%d seconds=%.3f throughput=%.3f/s database=%s%n",
                        Arrays.toString(partition), pairs.size(), warmup, threads, settled.get(), residual.get(),
                        seconds, (pairs.size() - warmup) / seconds, directory);
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally { WitnessSearch.shutdownGpuStats(); }
    }
}

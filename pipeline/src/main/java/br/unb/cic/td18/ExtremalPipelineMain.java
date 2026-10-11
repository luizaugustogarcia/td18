package br.unb.cic.td18;

import br.unb.cic.tdp.DirectTwistedBraceletGenerator;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line entry point for the E12 -> E18 computation. */
public final class ExtremalPipelineMain {
    private ExtremalPipelineMain() {
    }

    public static void main(final String[] args) {
        if (args.length == 0 || args[0].equals("help")) {
            usage();
            return;
        }
        final var options = parseOptions(args);
        final var work = Path.of(options.getOrDefault("work", "data"));
        final var gpuDevices = options.getOrDefault("gpu-devices", "0");
        final var gpuSlots = options.getOrDefault("gpu-slots", "7");
        System.setProperty("tdp.gpuDevices", gpuDevices);
        System.setProperty("tdp.gpuSlots", gpuSlots);
        System.setProperty("tdp.gpuQueueCapacity",
                options.getOrDefault("gpu-queue", "0"));
        System.setProperty("tdp.gpuStatsPeriodSeconds",
                options.getOrDefault("gpu-stats-seconds", "600"));
        System.setProperty("tdp.twistedBraceletWorkers",
                options.getOrDefault("generator-workers", "auto"));
        System.setProperty("td18.rocksdbCacheMiB",
                options.getOrDefault("rocksdb-cache-mib", "256"));
        final BoundedSorter sorter = new GpuBoundedSorter(
                gpuDevices,
                Integer.parseInt(gpuSlots),
                Long.parseLong(options.getOrDefault("gpu-budget-mib", "1024")));
        try {
            final var pipeline = new ExtremalPipeline(work, sorter,
                    Integer.parseInt(options.getOrDefault("lookup-batch-size", "500")));
            switch (args[0]) {
                case "e12" -> pipeline.runE12();
                case "e13" -> pipeline.runE13();
                case "h13", "h13-ge7" -> pipeline.runH13();
                case "e14" -> pipeline.runE14();
                case "e15" -> pipeline.runE15();
                case "e16" -> pipeline.runE16();
                case "e17" -> pipeline.runE17();
                case "e18" -> pipeline.runE18();
                case "all" -> pipeline.runAll();
                case "verify" -> new PipelineVerifier(work, sorter).verifyAll();
                default -> throw new IllegalArgumentException(
                        "Unknown command: " + args[0]);
            }
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    private static Map<String, String> parseOptions(final String[] args) {
        final var result = new LinkedHashMap<String, String>();
        for (var index = 1; index < args.length; index++) {
            final var option = args[index];
            if (!option.startsWith("--") || !option.contains("=")) {
                throw new IllegalArgumentException(
                        "Options must use --name=value: " + option);
            }
            final var separator = option.indexOf('=');
            result.put(option.substring(2, separator),
                    option.substring(separator + 1));
        }
        return result;
    }

    private static void usage() {
        System.out.println("""
                Usage: ExtremalPipelineMain <e12|e13|h13|e14|e15|e16|e17|e18|all|verify> [options]

                  --work=PATH                 stage databases (default: data)
                  --gpu-devices=0,1           CUDA device ordinals (default: 0)
                  --gpu-slots=NUMBER          slots per device (default: 7)
                  --gpu-budget-mib=NUMBER     memory per slot (default: 1024)
                  --gpu-queue=NUMBER          queued searches (default: 0)
                  --gpu-stats-seconds=NUMBER  statistics period (default: 600)
                  --generator-workers=NUMBER  generator workers (default: auto)
                  --lookup-batch-size=NUMBER  removal lookups per batch (default: 500)
                  --rocksdb-cache-mib=NUMBER   block cache per store (default: 256)
                """);
    }
}

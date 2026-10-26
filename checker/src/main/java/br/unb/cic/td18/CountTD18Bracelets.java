package br.unb.cic.td18;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.table.Table2;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

/**
 * Counts realizable twisted bracelets for each entry of Table 2 in the TD(18)
 * two-reduction computation.
 * Uses all available CPU cores and Burnside counting for every type; no sorting
 * search, certificate lookup, or representative decoding is performed.
 */
public final class CountTD18Bracelets {
    private CountTD18Bracelets() {
    }

    public static void main(final String[] args) throws IOException {
        final var root = ProjectPaths.root(Path.of(""));
        var tablePath = root.resolve("docs/td18-reduction-count.tex");
        var logPath = root.resolve("td18-bracelet-counts.log");
        var firstRow = 1;
        var lastRow = Table2.EXPECTED_ENTRIES;
        for (final var arg : args) {
            if (arg.equals("--help")) {
                System.out.println("CountTD18Bracelets [--table=path] [--log=path] [--rows=first-last]");
                return;
            } else if (arg.startsWith("--table=")) {
                tablePath = Path.of(arg.substring("--table=".length())).toAbsolutePath();
            } else if (arg.startsWith("--log=")) {
                logPath = Path.of(arg.substring("--log=".length())).toAbsolutePath();
            } else if (arg.startsWith("--rows=")) {
                final var range = arg.substring("--rows=".length()).split("-", -1);
                if (range.length != 2) {
                    throw new IllegalArgumentException("Expected --rows=first-last");
                }
                firstRow = Integer.parseInt(range[0]);
                lastRow = Integer.parseInt(range[1]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (firstRow < 1 || lastRow < firstRow || lastRow > Table2.EXPECTED_ENTRIES) {
            throw new IllegalArgumentException("Rows must be in the range 1-" + Table2.EXPECTED_ENTRIES);
        }

        final var entries = Table2.read(tablePath);

        // Set this before the generator initializes its shared executor.
        System.setProperty("td18.twistedBraceletWorkers",
                Integer.toString(Runtime.getRuntime().availableProcessors()));
        try (final var log = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            final var started = System.nanoTime();
            logLine(log, "# Started " + Instant.now() + "; cpuCores="
                    + Runtime.getRuntime().availableProcessors() + "; table=" + tablePath
                    + "; rows=" + firstRow + "-" + lastRow + "; method=burnside"
                    + "; counts=realizable twisted bracelets on contracted domains");
            var total = 0L;
            var completed = 0;
            try {
                for (final var entry : entries) {
                    if (entry.number() < firstRow || entry.number() > lastRow) {
                        continue;
                    }
                    logLine(log, "# Counting row=" + entry.number() + " type=" + entry.type()
                            + " method=burnside");
                    final var entryStarted = System.nanoTime();
                    final var bracelets = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                            entry.partition(), entry.orientedByPart());
                    if (entry.recordedRealizableClasses() != null
                            && entry.recordedRealizableClasses() != bracelets) {
                        throw new IllegalStateException("Table 2 row " + entry.number()
                                + " records " + entry.recordedRealizableClasses()
                                + " realizable classes, counted " + bracelets);
                    }
                    logLine(log, "row=" + entry.number() + " type=" + entry.type()
                            + " nonfixed=" + entry.nonfixedSymbols() + " fixed=" + entry.fixedSymbols()
                            + " movesUsed=" + entry.movesUsed() + " budget=" + entry.remainingMoves()
                            + " threeNorm=" + entry.threeNorm() + " bracelets=" + bracelets + " method=burnside"
                            + " elapsedSeconds=" + elapsedSeconds(entryStarted));
                    total = Math.addExact(total, bracelets);
                    completed++;
                }
                logLine(log, "# Complete entries=" + completed + " totalBracelets=" + total
                        + " elapsedSeconds=" + elapsedSeconds(started));
            } catch (final RuntimeException | Error failure) {
                logLine(log, "# Failed after " + completed + " entries: " + failure);
                throw failure;
            }
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    private static String elapsedSeconds(final long started) {
        return String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private static void logLine(final BufferedWriter log, final String line) throws IOException {
        log.write(line);
        log.newLine();
        log.flush();
        System.out.println(line);
    }

}

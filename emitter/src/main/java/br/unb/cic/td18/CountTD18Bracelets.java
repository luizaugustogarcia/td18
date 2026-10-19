package br.unb.cic.td18;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Counts realizable twisted bracelets for each entry of Table 2 in the TD(18)
 * two-reduction computation.
 * Uses all available CPU cores and Burnside counting for every type; no sorting
 * search, certificate lookup, or representative decoding is performed.
 */
public final class CountTD18Bracelets {
    private static final String TABLE_LABEL = "\\label{tab:complete}";
    static final int EXPECTED_ENTRIES = 204;
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\s*(\\d+)\\s*&\\s*\\\\ctype\\{(.*)}"
                    + "\\s*&\\s*(\\d+)\\s*&\\s*(\\d+)\\s*&\\s*(\\d+)"
                    + "\\s*&\\s*(\\d+)\\s*&\\s*(\\d+)"
                    + "(?:\\s*&\\s*([\\d\\\\,]+))?"
                    + "(?:\\s*&\\s*([\\d\\\\,]+)(?:\\\\textsuperscript\\{\\*})?)?"
                    + "\\s*\\\\\\\\\\s*$");
    private static final Pattern CYCLE = Pattern.compile("\\\\cyc\\{(\\d+)}\\{([OU])}");

    private CountTD18Bracelets() {
    }

    public static void main(final String[] args) throws IOException {
        final var root = projectRoot(Path.of(""));
        var tablePath = root.resolve("docs/td18-reduction-count.tex");
        var logPath = root.resolve("td18-bracelet-counts.log");
        var firstRow = 1;
        var lastRow = EXPECTED_ENTRIES;
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
        if (firstRow < 1 || lastRow < firstRow || lastRow > EXPECTED_ENTRIES) {
            throw new IllegalArgumentException("Rows must be in the range 1-" + EXPECTED_ENTRIES);
        }

        final var entries = readTableEntries(Files.readString(tablePath, StandardCharsets.UTF_8));
        if (entries.size() != EXPECTED_ENTRIES) {
            throw new IllegalArgumentException("Expected " + EXPECTED_ENTRIES
                    + " Table 2 entries, found " + entries.size());
        }

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

    static List<TableEntry> readTableEntries(final String latex) {
        final var start = latex.indexOf(TABLE_LABEL);
        final var end = latex.indexOf("\\end{longtable}", start);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Cannot find Table 2 with label tab:complete");
        }
        final var entries = new ArrayList<TableEntry>();
        final var types = new HashSet<String>();
        for (final var line : latex.substring(start, end).lines().toList()) {
            if (!line.stripLeading().matches("\\d+\\s*&.*")) {
                continue;
            }
            final var row = TABLE_ROW.matcher(line);
            if (!row.matches()) {
                throw new IllegalArgumentException("Malformed Table 2 row: " + line);
            }
            final var cycles = CYCLE.matcher(row.group(2));
            final var partition = new ArrayList<Integer>();
            final var orientations = new ArrayList<Boolean>();
            final var normalizedCycles = new ArrayList<String>();
            while (cycles.find()) {
                partition.add(Integer.parseInt(cycles.group(1)));
                orientations.add(cycles.group(2).equals("O"));
                normalizedCycles.add(cycles.group());
            }
            if (partition.isEmpty() || !String.join(",", normalizedCycles)
                    .equals(row.group(2).replaceAll("\\s+", ""))) {
                throw new IllegalArgumentException("Invalid annotated cycle type: " + row.group(2));
            }
            final var orientedByPart = new boolean[partition.size()];
            for (var i = 0; i < orientedByPart.length; i++) {
                orientedByPart[i] = orientations.get(i);
            }
            final var entry = new TableEntry(Integer.parseInt(row.group(1)),
                    partition.stream().mapToInt(Integer::intValue).toArray(), orientedByPart,
                    Integer.parseInt(row.group(3)), Integer.parseInt(row.group(4)),
                    Integer.parseInt(row.group(5)), Integer.parseInt(row.group(6)),
                    Integer.parseInt(row.group(7)), parseOptionalCount(row.group(8)),
                    parseOptionalCount(row.group(9)));
            if (entry.recordedRealizableClasses() != null
                    && entry.remainingClassObligations() != null) {
                final var obligation = new ReductionTypeEnumerator.Obligation(
                        entry.partition(), entry.orientedByPart(), entry.movesUsed());
                final long expectedRemaining = ReductionTypeEnumerator.remainingClassObligations(
                        obligation, entry.recordedRealizableClasses());
                if (entry.remainingClassObligations() != expectedRemaining) {
                    throw new IllegalArgumentException(
                            "Incorrect remaining obligation in Table 2 row " + entry.number()
                                    + ": expected " + expectedRemaining + ", found "
                                    + entry.remainingClassObligations());
                }
            }
            if (entry.number() != entries.size() + 1 || !types.add(entry.type())) {
                throw new IllegalArgumentException("Repeated type or out-of-order Table 2 row " + entry.number());
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    private static Long parseOptionalCount(final String value) {
        return value == null ? null : Long.parseLong(value.replace("\\,", ""));
    }

    static Path projectRoot(final Path directory) {
        var root = directory.toAbsolutePath().normalize();
        while (root != null) {
            if (Files.isRegularFile(root.resolve("pom.xml"))
                    && Files.isRegularFile(root.resolve("emitter/pom.xml"))) {
                return root;
            }
            root = root.getParent();
        }
        throw new IllegalArgumentException("Run from the project root or one of its subdirectories");
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

    record TableEntry(int number, int[] partition, boolean[] orientedByPart,
                      int nonfixedSymbols, int fixedSymbols, int movesUsed,
                      int remainingMoves, int threeNorm,
                      Long recordedRealizableClasses,
                      Long remainingClassObligations) {
        TableEntry {
            partition = partition.clone();
            orientedByPart = orientedByPart.clone();
            if (Arrays.stream(partition).sum() != nonfixedSymbols
                    || nonfixedSymbols + fixedSymbols != 19
                    || movesUsed + remainingMoves != 10
                    || Arrays.stream(partition).map(part -> part / 2).sum() != threeNorm
                    || partition.length != orientedByPart.length
                    || (remainingClassObligations != null
                    && recordedRealizableClasses == null)
                    || (remainingClassObligations != null
                    && (remainingClassObligations < 0
                    || remainingClassObligations > recordedRealizableClasses))
                    || Arrays.stream(partition).filter(part -> part % 2 == 0).count() % 2 != 0) {
                throw new IllegalArgumentException("Inconsistent metadata in Table 2 row " + number);
            }
            for (var i = 0; i < partition.length; i++) {
                if (partition[i] < 2 || (partition[i] == 2 && orientedByPart[i])
                        || (i > 0 && (partition[i] > partition[i - 1]
                        || (partition[i] == partition[i - 1] && orientedByPart[i - 1] && !orientedByPart[i])))) {
                    throw new IllegalArgumentException("Noncanonical annotation in Table 2 row " + number);
                }
            }
        }

        String type() {
            return IntStream.range(0, partition.length)
                    .mapToObj(i -> partition[i] + "-" + (orientedByPart[i] ? "O" : "U"))
                    .collect(Collectors.joining(",", "(", ")"));
        }
    }
}

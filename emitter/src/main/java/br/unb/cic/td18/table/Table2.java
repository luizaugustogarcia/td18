package br.unb.cic.td18.table;

import br.unb.cic.td18.reduction.ReductionTypeEnumerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Reads Table 2 from the TD(18) source document. */
public final class Table2 {
    private static final String TABLE_LABEL = "\\label{tab:complete}";
    public static final int EXPECTED_ENTRIES = 292;
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\s*(\\d+)\\s*&\\s*\\\\ctype\\{(.*)}"
                    + "\\s*&\\s*(\\d+)\\s*&\\s*(\\d+)\\s*&\\s*(\\d+)"
                    + "\\s*&\\s*(\\d+)\\s*&\\s*(\\d+)"
                    + "(?:\\s*&\\s*([\\d\\\\,]+))?"
                    + "(?:\\s*&\\s*([\\d\\\\,]+)(?:\\\\textsuperscript\\{\\*})?)?"
                    + "\\s*\\\\\\\\\\s*$");
    private static final Pattern CYCLE = Pattern.compile("\\\\cyc\\{(\\d+)}\\{([OU])}");

    private Table2() { }

    /** Loads the complete inventory and rejects missing rows or R/Q counts. */
    public static List<TableEntry> read(final Path document) throws IOException {
        final var entries = parse(Files.readString(document, StandardCharsets.UTF_8));
        if (entries.size() != EXPECTED_ENTRIES) {
            throw new IllegalArgumentException("Expected " + EXPECTED_ENTRIES
                    + " Table 2 entries, found " + entries.size());
        }
        for (final var entry : entries) {
            if (entry.recordedRealizableClasses() == null
                    || entry.remainingClassObligations() == null) {
                throw new IllegalArgumentException("Missing R/Q counts in Table 2 row " + entry.number());
            }
        }
        return entries;
    }

    /** Parses draft rows; use read for a computation requiring the complete inventory. */
    public static List<TableEntry> parse(final String latex) {
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

}

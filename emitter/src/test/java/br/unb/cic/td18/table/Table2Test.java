package br.unb.cic.td18.table;

import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.runtime.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Table2Test {
    @Test
    void loadsOnlyACompleteInventory(@TempDir final Path directory) throws IOException {
        final var document = directory.resolve("table.tex");
        final var latex = table();
        Files.writeString(document, latex);
        assertEquals(292, Table2.read(document).size());

        final var incomplete = latex.lines()
                .filter(line -> !line.stripLeading().startsWith("292 &"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(291, Table2.parse(incomplete).size());
        Files.writeString(document, incomplete);
        assertThrows(IllegalArgumentException.class, () -> Table2.read(document));
    }

    @Test
    void rejectsDraftRowsWithoutCountsAtTheRunBoundary(@TempDir final Path directory) throws IOException {
        final var latex = table();
        final var first = latex.substring(latex.indexOf("\\label{tab:complete}")).lines()
                .filter(line -> line.stripLeading().startsWith("1 &"))
                .findFirst().orElseThrow();
        final var withoutQ = first.substring(0, first.lastIndexOf('&'));
        final var withoutCounts = withoutQ.substring(0, withoutQ.lastIndexOf('&')) + " \\\\";
        final var draft = latex.replace(first, withoutCounts);
        assertEquals(292, Table2.parse(draft).size());
        assertNull(Table2.parse(draft).getFirst().recordedRealizableClasses());
        assertNull(Table2.parse(draft).getFirst().remainingClassObligations());
        final var document = directory.resolve("draft.tex");
        Files.writeString(document, draft);
        final var error = assertThrows(IllegalArgumentException.class, () -> Table2.read(document));
        assertTrue(error.getMessage().contains("Missing R/Q counts"));
    }

    @Test
    void callersCannotMutateTableMetadata() throws IOException {
        final var entry = Table2.parse(table()).getFirst();
        final var expected = entry.type();
        entry.partition()[0] = 2;
        entry.orientedByPart()[0] = true;
        assertEquals(expected, entry.type());
        assertEquals(7, entry.partition()[0]);
        assertFalse(entry.orientedByPart()[0]);
    }

    private static String table() throws IOException {
        return Files.readString(ProjectPaths.root(Path.of(""))
                .resolve("docs/td18-reduction-count.tex"));
    }

    private static boolean hasOrientedThreeCycle(final TableEntry entry) {
        final int[] partition = entry.partition();
        final boolean[] oriented = entry.orientedByPart();
        for (var index = 0; index < partition.length; index++) {
            if (partition[index] == 3 && oriented[index]) {
                return true;
            }
        }
        return false;
    }

    @Test
    void readsAll292EntriesInLatexTableOrder() throws IOException {
        final var latex = table();
        final var entries = Table2.parse(latex);
        assertEquals(292, entries.size());
        assertEquals("(7-U,3-U,3-U,3-U,3-U)", entries.getFirst().type());
        assertEquals("(4-O,3-O,3-O,2-U,2-U,2-U)", entries.get(203).type());
        assertEquals(59, entries.stream().filter(entry -> entry.movesUsed() == 0).count());
        assertEquals(45, entries.stream().filter(entry -> entry.movesUsed() == 1).count());
        assertEquals(188, entries.stream().filter(entry -> entry.movesUsed() == 2).count());
        assertEquals(0, entries.stream().filter(entry -> entry.movesUsed() == 3).count());
        for (final int row : new int[]{130, 134}) {
            final var entry = entries.get(row - 1);
            assertEquals(row, entry.number());
            assertEquals(entry.recordedRealizableClasses(),
                    entry.remainingClassObligations());
        }
        assertTrue(latex.contains(
                "1\\,384\\,025\\,296\\textsuperscript{*}"));
        assertTrue(latex.contains(
                "1\\,221\\,159\\,648\\textsuperscript{*}"));
    }

    @Test
    void tableTwoExactlyMatchesTheReductionEnumerator() throws IOException {
        final var entries = Table2.parse(table());
        final var obligations = ReductionTypeEnumerator.enumerate();

        assertEquals(obligations.size(), entries.size());
        for (var index = 0; index < entries.size(); index++) {
            final var entry = entries.get(index);
            final var obligation = obligations.get(index);
            assertArrayEquals(obligation.lengths(), entry.partition());
            assertArrayEquals(obligation.oriented(), entry.orientedByPart());
            assertEquals(obligation.nonfixedSymbols(), entry.nonfixedSymbols());
            assertEquals(ReductionTypeEnumerator.ROOT_SYMBOLS - obligation.nonfixedSymbols(),
                    entry.fixedSymbols());
            assertEquals(obligation.movesUsed(), entry.movesUsed());
            assertEquals(obligation.budget(), entry.remainingMoves());
            assertEquals(obligation.threeNorm(), entry.threeNorm());
            assertNotNull(entry.recordedRealizableClasses());
            assertNotNull(entry.remainingClassObligations());
            assertEquals(ReductionTypeEnumerator.remainingClassObligations(
                            obligation, entry.recordedRealizableClasses()),
                    entry.remainingClassObligations());
        }
        assertEquals(692_894_074_401L, entries.stream()
                .mapToLong(TableEntry::recordedRealizableClasses)
                .sum());
        assertEquals(45_498_334_516L, entries.stream()
                .mapToLong(TableEntry::remainingClassObligations)
                .sum());

        final var td16SuccessorSearches = entries.stream()
                .filter(entry -> entry.nonfixedSymbols() == 16
                        && entry.movesUsed() == 2
                        && !hasOrientedThreeCycle(entry))
                .toList();
        assertEquals(52, td16SuccessorSearches.size());
        assertEquals(4_511_223_147L, td16SuccessorSearches.stream()
                .mapToLong(TableEntry::recordedRealizableClasses)
                .sum());
        assertEquals(2_605_184_944L, td16SuccessorSearches.stream()
                .mapToLong(TableEntry::remainingClassObligations)
                .sum());
    }

    @Test
    void rejectsAChangedNormInsteadOfCountingTheWrongType() throws IOException {
        final var invalid = table().replace("& 19 & 0 & 0 & 10 & 8", "& 19 & 0 & 0 & 10 & 7");
        assertThrows(IllegalArgumentException.class,
                () -> Table2.parse(invalid));
    }

    @Test
    void rejectsAChangedRemainingObligation() throws IOException {
        final var invalid = table().replace(
                "1\\,384\\,025\\,296\\textsuperscript{*}",
                "1\\,384\\,025\\,295\\textsuperscript{*}");
        assertThrows(IllegalArgumentException.class,
                () -> Table2.parse(invalid));
    }

    @Test
    void restrictsOnlyTheThreeOddPairRowsAndRejectsMissingSeeds() throws IOException {
        final var latex = table();
        final var entries = Table2.parse(latex);
        final int[] rows = {267, 270, 274};
        final long[] counts = {391L, 321L, 1_172L};
        final long[] fullCounts = {284_235_604_416L, 243_117_785_088L, 115_561_900_003L};
        for (var index = 0; index < rows.length; index++) {
            final int row = rows[index];
            final var entry = entries.get(row - 1);
            assertEquals(fullCounts[index], entry.recordedRealizableClasses());
            assertEquals(counts[index], entry.remainingClassObligations());
            final var line = latex.substring(latex.indexOf("\\label{tab:complete}")).lines()
                    .filter(candidate -> candidate.stripLeading().startsWith(row + " &"))
                    .findFirst().orElseThrow();
            final var invalidLine = line.substring(0, line.lastIndexOf('&') + 1) + " 0 \\\\";
            assertThrows(IllegalArgumentException.class, () -> Table2.parse(latex.replace(line, invalidLine)));
        }
        assertEquals(3, entries.stream().filter(entry -> entry.remainingClassObligations() > 0
                && !entry.remainingClassObligations().equals(entry.recordedRealizableClasses())).count());
    }
}

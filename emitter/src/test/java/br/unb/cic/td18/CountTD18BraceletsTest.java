package br.unb.cic.td18;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CountTD18BraceletsTest {
    private static String table() throws IOException {
        return Files.readString(CountTD18Bracelets.projectRoot(Path.of(""))
                .resolve("docs/td18-reduction-count.tex"));
    }

    private static boolean hasOrientedThreeCycle(final CountTD18Bracelets.TableEntry entry) {
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
    void readsAll204EntriesInLatexTableOrder() throws IOException {
        final var entries = CountTD18Bracelets.readTableEntries(table());
        assertEquals(204, entries.size());
        assertEquals("(7-U,3-U,3-U,3-U,3-U)", entries.getFirst().type());
        assertEquals("(4-O,3-O,3-O,2-U,2-U,2-U)", entries.getLast().type());
        assertEquals(8, entries.stream().filter(entry -> entry.movesUsed() == 0).count());
        assertEquals(8, entries.stream().filter(entry -> entry.movesUsed() == 1).count());
        assertEquals(188, entries.stream().filter(entry -> entry.movesUsed() == 2).count());
        assertEquals(0, entries.stream().filter(entry -> entry.movesUsed() == 3).count());
    }

    @Test
    void tableTwoExactlyMatchesTheReductionEnumerator() throws IOException {
        final var entries = CountTD18Bracelets.readTableEntries(table());
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
        assertEquals(33_544_626_415L, entries.stream()
                .mapToLong(CountTD18Bracelets.TableEntry::recordedRealizableClasses)
                .sum());
        assertEquals(26_458_991_245L, entries.stream()
                .mapToLong(CountTD18Bracelets.TableEntry::remainingClassObligations)
                .sum());

        final var td16SuccessorSearches = entries.stream()
                .filter(entry -> entry.nonfixedSymbols() == 16
                        && entry.movesUsed() == 2
                        && !hasOrientedThreeCycle(entry))
                .toList();
        assertEquals(52, td16SuccessorSearches.size());
        assertEquals(4_511_223_147L, td16SuccessorSearches.stream()
                .mapToLong(CountTD18Bracelets.TableEntry::recordedRealizableClasses)
                .sum());
        assertEquals(2_036L, td16SuccessorSearches.stream()
                .mapToLong(CountTD18Bracelets.TableEntry::remainingClassObligations)
                .sum());
    }

    @Test
    void rejectsAChangedNormInsteadOfCountingTheWrongType() throws IOException {
        final var invalid = table().replace("& 19 & 0 & 0 & 10 & 8", "& 19 & 0 & 0 & 10 & 7");
        assertThrows(IllegalArgumentException.class,
                () -> CountTD18Bracelets.readTableEntries(invalid));
    }

    @Test
    void rejectsAChangedRemainingObligation() throws IOException {
        final var invalid = table().replace(
                "1\\,384\\,025\\,296 & 495",
                "1\\,384\\,025\\,296 & 496");
        assertThrows(IllegalArgumentException.class,
                () -> CountTD18Bracelets.readTableEntries(invalid));
    }
}

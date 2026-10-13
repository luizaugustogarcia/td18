package br.unb.cic.tdp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CountTD18BraceletsTest {
    private static String table() throws IOException {
        return Files.readString(CountTD18Bracelets.projectRoot(Path.of(""))
                .resolve("docs/td18-reduction-count.tex"));
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
    void rejectsAChangedNormInsteadOfCountingTheWrongType() throws IOException {
        final var invalid = table().replace("& 19 & 0 & 0 & 10 & 8", "& 19 & 0 & 0 & 10 & 7");
        assertThrows(IllegalArgumentException.class,
                () -> CountTD18Bracelets.readTableEntries(invalid));
    }

    @Test
    void acceptsAnAddedBraceletColumn() throws IOException {
        final var extended = table().replace("& 19 & 0 & 0 & 10 & 8",
                "& 19 & 0 & 0 & 10 & 8 & 123");
        assertEquals(204, CountTD18Bracelets.readTableEntries(extended).size());
    }
}

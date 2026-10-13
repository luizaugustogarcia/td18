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
    void readsAll106EntriesInLatexTableOrder() throws IOException {
        final var entries = CountTD18Bracelets.readTableEntries(table());
        assertEquals(106, entries.size());
        assertEquals("(13-U,3-U,3-U)", entries.getFirst().type());
        assertEquals("(3-O,3-O,2-U,2-U,2-U,2-U)", entries.getLast().type());
        assertEquals(57, entries.stream().filter(entry -> entry.movesUsed() == 3).count());
        assertEquals(16, entries.get(49).nonfixedSymbols());
        assertEquals(7, entries.get(49).remainingMoves());
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
        assertEquals(106, CountTD18Bracelets.readTableEntries(extended).size());
    }
}

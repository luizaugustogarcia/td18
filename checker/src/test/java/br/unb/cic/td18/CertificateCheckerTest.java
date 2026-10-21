package br.unb.cic.td18;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateCheckerTest {
    @Test
    void independentlyReconstructsTheDocumentedInventory() throws Exception {
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var table = CountTD18Bracelets.readTableEntries(
                Files.readString(root.resolve(
                                "docs/td18-reduction-count.tex"),
                        StandardCharsets.UTF_8));

        final long checksum = CertificateChecker.verifyInventory(table);

        assertEquals(2_232_227_918L, checksum);
    }

    @Test
    void dischargesQZeroOnlyThroughTheCitedPaperLemma() throws Exception {
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var table = CountTD18Bracelets.readTableEntries(
                Files.readString(root.resolve(
                                "docs/td18-reduction-count.tex"),
                        StandardCharsets.UTF_8));

        final var audit = PaperDischargeLemma.verify(table);

        assertEquals("https://arxiv.org/abs/2609.17493",
                PaperDischargeLemma.PAPER);
        assertEquals(52, audit.orientedThreeRows());
        assertEquals(52, audit.successorSearchRows());
        assertEquals(50, audit.paperSettledSuccessorRows());
        assertEquals(2, audit.retainedResidualRows());
        assertEquals(4_511_223_147L,
                PaperDischargeLemma.PAPER_SUCCESSOR_CLASSES);
        assertEquals(4_511_221_111L,
                PaperDischargeLemma.PAPER_SORTED_SUCCESSOR_CLASSES);
        assertEquals(2_036L,
                PaperDischargeLemma.PAPER_RESIDUAL_SUCCESSOR_CLASSES);
        assertEquals(14, audit.emptyUncoveredRows());
        assertEquals(116, audit.zeroObligationRows());
        assertEquals(29_064_174_153L, audit.totalQ());
    }
}

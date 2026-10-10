package br.unb.cic.td18;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApproximationBoundTest {
    @Test
    void appliesTheThreeHalvesGuaranteeBelowThreeNormEight() {
        assertEquals(7, ApproximationBound.guaranteedMoves(5));
        assertEquals(9, ApproximationBound.guaranteedMoves(6));
        assertEquals(10, ApproximationBound.guaranteedMoves(7));
    }

    @Test
    void appliesTheElevenEighthsGuaranteeFromThreeNormEight() {
        assertEquals(11, ApproximationBound.guaranteedMoves(8));
        assertEquals(12, ApproximationBound.guaranteedMoves(9));
    }

    @Test
    void rejectsNegativeThreeNorm() {
        assertThrows(IllegalArgumentException.class,
                () -> ApproximationBound.guaranteedMoves(-1));
    }
}

package br.unb.cic.td18;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApproximationBoundTest {
    @Test
    void appliesTheIntegerPartOfTheElevenEighthsGuarantee() {
        assertEquals(8, ApproximationBound.guaranteedMoves(6));
        assertEquals(9, ApproximationBound.guaranteedMoves(7));
        assertEquals(11, ApproximationBound.guaranteedMoves(8));
    }
}

package br.unb.cic.td18;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrientedNineteenCycleClassificationTest {
    @Test
    void reproducesTheCompleteLengthNineteenClassification() {
        final var result = OrientedNineteenCycleClassification.classify();

        assertEquals(9_304_845, result.visitedNodes());
        assertEquals(3, result.retainedCycles());
        assertEquals(1, result.unorientedCycles());
        assertEquals(2, result.orientedPairs().size());
        assertEquals(OrientedNineteenCycleClassification
                        .expectedOrientedPairs().stream()
                        .map(pair -> pair.extendedToricClassKey()).toList(),
                result.orientedPairs().stream()
                        .map(pair -> pair.extendedToricClassKey()).toList());
    }
}

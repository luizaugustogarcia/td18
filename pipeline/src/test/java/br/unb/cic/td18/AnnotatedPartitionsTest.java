package br.unb.cic.td18;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnnotatedPartitionsTest {
    @Test
    void equalPartsAreAnnotatedOnlyByTheirOrientedMultiplicity() {
        final var annotations = AnnotatedPartitions.of(new int[]{5, 3, 3, 2, 2});

        assertEquals(6, annotations.size());
        final var keys = new HashSet<String>();
        annotations.forEach(annotation -> keys.add(annotation.checkpointKey()));
        assertEquals(annotations.size(), keys.size());
    }

    @Test
    void twoCyclesCannotBeOriented() {
        final var annotations = AnnotatedPartitions.of(new int[]{2, 2, 2, 2});

        assertEquals(1, annotations.size());
        assertEquals("[2, 2, 2, 2]:0000",
                annotations.getFirst().checkpointKey());
    }

    @Test
    void generationPlanContainsUniqueAnnotatedPartitions() {
        final var planned = FixedPointFreeClassGenerator.plannedCheckpoints(8);

        assertFalse(planned.isEmpty());
        assertEquals(planned.size(), planned.stream().distinct().count());
    }
}

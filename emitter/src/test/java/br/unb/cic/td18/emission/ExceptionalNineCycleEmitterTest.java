package br.unb.cic.td18.emission;

import br.unb.cic.td18.model.ExceptionalNineCycle;
import br.unb.cic.td18.search.WitnessSearch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionalNineCycleEmitterTest {
    @Test
    void coversAllTypesAndRejectsUnknownSelections() {
        assertEquals(18, ExceptionalNineCycleEmitter.selectedTypes(null).size());
        assertEquals(1, ExceptionalNineCycleEmitter.selectedTypes("9O-8U-2U").size());
        assertThrows(IllegalArgumentException.class, () ->
                ExceptionalNineCycleEmitter.selectedTypes("9O-10U"));
    }

    @Test
    void fullDomainSearchRequiresCompleteTenTranspositionWitnesses() {
        final var type = ExceptionalNineCycleEmitter.selectedTypes("9O-8U-2U").getFirst();
        final int[] called = {0};
        final var failure = assertThrows(IllegalStateException.class, () ->
                ExceptionalNineCycleEmitter.emitType(type, (pair, rate, budget) -> {
                    called[0]++;
                    assertEquals(19, pair.getBeta().size());
                    assertTrue(ExceptionalNineCycle.contains(pair));
                    assertEquals(10, budget);
                    assertEquals(1.8f, rate, 1e-6f);
                    return WitnessSearch.SearchResult.triviallySettled();
                }));
        assertEquals(1, called[0]);
        assertTrue(failure.getMessage().contains("complete witness"));
    }
}

package br.unb.cic.td18.search;

import br.unb.cic.td18.certificate.AsyncCertificateWriter;
import br.unb.cic.td18.certificate.CertificateValidator;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class WitnessSearchTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5)).canonicalRepresentative();
    }
    @Test void sortingRequiresPersistenceBeforeAnyGpuWork() {
        assertThrows(IllegalStateException.class, () -> WitnessSearch.search(pair(), 2));
    }
    @Test @EnabledIfSystemProperty(named="td18.gpuSmoke",matches="true")
    void gpuClosureIsPersistedReplayedAndNotACompleteWitness(@TempDir Path path) {
        System.setProperty("td18.gpuSlots","2");
        System.setProperty("td18.gpuBudgetMB","32");
        try (var writer = new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result = WitnessSearch.search(pair(),2);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                assertEquals(1,result.moves().size());
                assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair(),result.moves(),2));
                writer.flush();
                assertTrue(writer.find(pair(),1).isEmpty());
                assertEquals(result.moves(),writer.find(pair(),2).orElseThrow());
                assertEquals(result,WitnessSearch.search(pair(),2));
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            System.clearProperty("td18.gpuSlots");System.clearProperty("td18.gpuBudgetMB");
        }
    }
}

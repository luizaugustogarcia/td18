package br.unb.cic.td18.joint;

import br.unb.cic.td18.runtime.ProjectPaths;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JointInventoryTest {
    @Test void sourcePaperAndIndependentInventoriesAgree() throws Exception {
        final var roots=JointInventory.read(ProjectPaths.root(Path.of("")).resolve("docs/td18-reduction-count.tex"));
        assertEquals(53,roots.size());
        assertEquals(248_082_611L,roots.stream().mapToLong(r->r.unorientedClasses()+r.orientedClasses()).sum());
        final var endpoints=JointInventory.endpoints();
        assertEquals(104,endpoints.size());
        assertEquals(52,endpoints.stream().filter(JointInventory.Endpoint::elementaryClosure).count());
        assertEquals(9,KnownDiameters.knownDiameter(17));
        assertEquals(8,KnownDiameters.knownDiameter(15));
        assertFalse(KnownDiameters.closes(19,0));
    }
}

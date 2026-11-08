package br.unb.cic.td18.joint;

import br.unb.cic.td18.runtime.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class JointInventoryTest {
    @TempDir Path directory;

    @Test
    void paperContainsEveryRootPartitionAndAuditedTotals() throws Exception {
        final var rows = JointInventory.read(paper());
        assertEquals(53, rows.size());
        assertEquals(214_474_050L, rows.stream().mapToLong(JointInventory.Root::unorientedClasses).sum());
        assertEquals(37_858_324L, rows.stream().mapToLong(JointInventory.Root::jointResidualClasses).sum());
        assertEquals(4_249_763L, rows.stream().mapToLong(JointInventory.Root::supportClearingClasses).sum());
        assertEquals(33_608_561L, rows.stream().mapToLong(JointInventory.Root::orientedClasses).sum());
        assertEquals(248_082_611L, rows.stream().mapToLong(JointInventory.Root::directClasses).sum());
        assertArrayEquals(new int[]{19}, rows.getFirst().partition());
        rows.getFirst().partition()[0] = 1;
        assertArrayEquals(new int[]{19}, rows.getFirst().partition());
        assertThrows(UnsupportedOperationException.class, rows::clear);
    }

    @Test
    void missingDuplicateMalformedAndInconsistentRowsReject() throws Exception {
        final String original = Files.readString(paper());
        final String row = "\\jointrow{1}{19}{0}{667}{0}{667}";
        assertTrue(original.contains(row));
        for (final String changed : new String[]{
                original.replace(row, ""),
                original.replace(row, row + "\n" + row),
                original.replace(row, "\\jointrow{1}{19}{0}{667}{0}{666}"),
                original.replace(row, "\\jointrow{1}{19}{0}{667}{0}{667}trailing"),
                original.replace(row, "\\jointrow{1}{17,2}{0}{667}{0}{667}")}) {
            final Path corrupt = directory.resolve("corrupt.tex");
            Files.writeString(corrupt, changed);
            assertThrows(IllegalArgumentException.class, () -> JointInventory.read(corrupt));
        }
    }

    @Test
    void endpointCoverIsCompleteAndHasFiftyTwoElementaryClosures() {
        final var endpoints = JointInventory.endpoints();
        assertEquals(104, endpoints.size());
        assertEquals(52, endpoints.stream().filter(JointInventory.Endpoint::elementaryClosure).count());
        final var seen = new HashSet<String>();
        for (final var endpoint : endpoints) {
            final int[] partition = endpoint.partition();
            final boolean[] orientations = endpoint.orientedByPart();
            assertEquals(16, Arrays.stream(partition).sum());
            assertTrue(KnownDiameters.threeNorm(partition) <= 7);
            assertEquals(0, Arrays.stream(partition).filter(x -> x % 2 == 0).count() % 2);
            assertTrue(seen.add(Arrays.toString(partition) + Arrays.toString(orientations)));
            for (int i = 0; i < partition.length; i++) {
                if (partition[i] == 2) assertFalse(orientations[i]);
                if (i > 0 && partition[i] == partition[i - 1] && orientations[i - 1]) {
                    assertTrue(orientations[i]);
                }
            }
        }
        final int[] original = endpoints.getFirst().partition();
        endpoints.getFirst().partition()[0] = 1;
        assertArrayEquals(original, endpoints.getFirst().partition());
        assertThrows(UnsupportedOperationException.class, endpoints::clear);
    }

    @Test
    void knownDiameterClosuresUseTheCyclicDomainConvention() {
        assertTrue(KnownDiameters.closes(17, 1));
        assertTrue(KnownDiameters.closes(15, 2));
        assertFalse(KnownDiameters.closes(16, 2));
        assertEquals(7, KnownDiameters.knownDiameter(13));
        assertEquals(Integer.MAX_VALUE, KnownDiameters.knownDiameter(19));
        assertEquals(4, KnownDiameters.threeNorm(new int[]{5, 4, 1, 1}));
    }

    private static Path paper() {
        return ProjectPaths.root(Path.of("")).resolve("docs/td18-reduction-count.tex");
    }
}

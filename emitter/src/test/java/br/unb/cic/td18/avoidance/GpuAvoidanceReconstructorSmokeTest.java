package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "td18.gpuSmoke", matches = "true")
class GpuAvoidanceReconstructorSmokeTest {
    @Test
    void matchesCpuKeysAndApplicationCountsAcrossBothWaves() {
        final var reconstructor = new GpuAvoidanceReconstructor(
                0, 512L * 1024L * 1024L);
        final var residual = controlResidual();

        final var cpuIntermediate = GpuAvoidanceReconstructor.cpuReference(
                List.of(residual), 8);
        final var gpuIntermediate = reconstructor.reconstruct(
                List.of(residual), 8);
        assertEquals(2_176, gpuIntermediate.applications());
        assertEquals(1_759, gpuIntermediate.uniqueKeys());
        assertEquals(cpuIntermediate.applications(),
                gpuIntermediate.applications());
        assertArrayEquals(cpuIntermediate.keyRecords(),
                gpuIntermediate.keyRecords());

        final var intermediateParents =
                GpuAvoidanceReconstructor.decodeKeyRecords(
                        gpuIntermediate.keyRecords());
        final var cpuRoot = GpuAvoidanceReconstructor.cpuReference(
                intermediateParents, 9);
        final var gpuRoot = reconstructor.reconstruct(
                intermediateParents, 9);
        assertEquals(4_844_286, gpuRoot.applications());
        assertEquals(1_901_013, gpuRoot.uniqueKeys());
        assertEquals(cpuRoot.applications(), gpuRoot.applications());
        assertArrayEquals(cpuRoot.keyRecords(), gpuRoot.keyRecords());
    }

    private static CyclicTargetPair controlResidual() {
        final var omega = new MulticyclePermutation(List.of(
                Cycle.of(0, 11, 13, 12, 2, 4, 9, 5, 7, 3, 14),
                Cycle.of(1, 10, 8, 15, 6)));
        final var beta = Cycle.of(0, 6, 5, 15, 14, 8, 4, 9,
                1, 12, 11, 13, 3, 2, 7, 10);
        return CyclicTargetPair.of(omega, beta).canonicalRepresentative();
    }
}

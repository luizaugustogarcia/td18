package br.unb.cic.td18;

import br.unb.cic.tdp.permutation.Cycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PipelineVerifierTest {
    @TempDir
    Path directory;

    @Test
    void verifiesNonemptyKnownDiameterStageWithoutWitnessStore() throws Exception {
        final var child = KnownDiametersTest.reversePair(14).canonicalRepresentative();
        final var childKey = EncodedClassKey.of(child);
        final var parentStage = directory.resolve("e12");
        final var childStage = directory.resolve("e13");
        try (final var parents = new ClassStore(parentStage.resolve("classes"), 13, true)) {
            parents.putAll(CircularClassOperations.removals(child));
            StageManifest.completeKnownDiameter(parentStage, "E12", 13, parents.summarize());
        }
        try (final var classes = new ClassStore(childStage.resolve("classes"), 14, true)) {
            classes.putAll(List.of(child));
            StageManifest.completeKnownDiameter(childStage, "E13", 14, classes.summarize());
        }

        new PipelineVerifier(directory, (pair, depth) -> {
            assertEquals(7, depth);
            return childKey.equals(EncodedClassKey.of(pair))
                    ? Optional.empty() : Optional.of(List.of(Cycle.of(0, 1, 2)));
        }).verifyE13();

        assertFalse(Files.exists(childStage.resolve("witnesses")));
        final var manifest = Files.readString(childStage.resolve("manifest.txt"));
        assertTrue(manifest.contains("upperBoundSource=known-diameter\n"));
        assertTrue(manifest.contains("upperBound=8\n"));
    }

    @Test
    void order18VerificationStillRejectsMissingWitness() {
        final var child = KnownDiametersTest.reversePair(19).canonicalRepresentative();
        final var parentStage = directory.resolve("e17");
        final var childStage = directory.resolve("e18");
        try (final var parents = new ClassStore(parentStage.resolve("classes"), 18, true)) {
            parents.putAll(CircularClassOperations.removals(child));
            StageManifest.completeKnownDiameter(parentStage, "E17", 18, parents.summarize());
        }
        try (final var classes = new ClassStore(childStage.resolve("classes"), 19, true);
             final var witnesses = new WitnessStore(childStage.resolve("witnesses"), true)) {
            classes.putAll(List.of(child));
            StageManifest.complete(childStage, "TD18_CERTIFICATE", 19, classes.summarize());
        }
        final var failure = assertThrows(IllegalStateException.class, () ->
                new PipelineVerifier(directory, (pair, depth) -> {
                    throw new AssertionError("Verification must replay a stored witness");
                }).verifyE18());
        assertEquals("Missing sorting witness", failure.getMessage());
    }
}

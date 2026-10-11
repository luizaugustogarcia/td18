package br.unb.cic.td18;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExtremalPipelineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void directE13DoesNotRequireOrCreateTheGlobalH13Stage() {
        final var e12Stage = temporaryDirectory.resolve("e12");
        try (final var parents = new ClassStore(e12Stage.resolve("classes"), 13, true)) {
            StageManifest.complete(e12Stage, "E12", 13, parents.summarize());
        }
        final var pipeline = new ExtremalPipeline(temporaryDirectory, (pair, depth) -> {
            throw new AssertionError("Empty parent set must not invoke bounded sorting");
        }, 500);
        pipeline.runE13();
        pipeline.runE13();
        StageManifest.requireComplete(temporaryDirectory.resolve("e13"), "E13", 14);
        try (final var classes = new ClassStore(
                temporaryDirectory.resolve("e13/classes"), 14, false)) {
            assertEquals(0, classes.summarize().count());
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("h13-ge7")));
    }
}

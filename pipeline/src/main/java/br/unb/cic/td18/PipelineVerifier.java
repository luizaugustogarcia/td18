package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/** Replays witnesses, removal obligations, checksums, and exact-depth failures. */
@Slf4j
public final class PipelineVerifier {
    private final Path workDirectory;
    private final BoundedSorter sorter;

    public PipelineVerifier(final Path workDirectory, final BoundedSorter sorter) {
        this.workDirectory = workDirectory.toAbsolutePath();
        this.sorter = sorter;
    }

    public void verifyAll() {
        verifyE15();
        verifyE16();
        verifyE17();
        verifyE18();
    }

    public void verifyE15() {
        final var stage = workDirectory.resolve("e15");
        FixedPointFreeClassGenerator.requireComplete(
                16, stage.resolve("fixed-point-free.checkpoints"));
        try (final var classes = checkedClasses(stage, "E15", 16);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), false)) {
            classes.forEach(pair -> {
                requireFixedPointCount(pair, 0);
                requireNoSorting(pair, 8);
                requireWitness(pair, 9, witnesses);
            });
        }
        log.info("E15 verified");
    }

    public void verifyE16() {
        final var e15Stage = workDirectory.resolve("e15");
        final var stage = workDirectory.resolve("e16");
        FixedPointFreeClassGenerator.requireComplete(
                17, stage.resolve("fixed-point-free.checkpoints"));
        try (final var e15 = checkedClasses(e15Stage, "E15", 16);
             final var classes = checkedClasses(stage, "E16", 17);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), false)) {
            e15.forEach(parent -> CircularClassOperations.insertions(parent).stream()
                    .map(CircularClassOperations.Insertion::child)
                    .filter(child -> child.getOmega().image(16) == 16)
                    .forEach(child -> {
                        if (!classes.contains(child)) {
                            throw new IllegalStateException(
                                    "Expand(E15) child is missing from E16");
                        }
                    }));
            classes.forEach(pair -> {
                final var fixedPoints = CircularClassOperations
                        .algebraicFixedPointCount(pair);
                if (fixedPoints == 0) {
                    requireNoSorting(pair, 8);
                    requireWitness(pair, 9, witnesses);
                    return;
                }
                requireFixedPointCount(pair, 1);
                var contractsToE15 = false;
                for (var symbol = 0; symbol < pair.getBeta().size(); symbol++) {
                    if (pair.getOmega().image(symbol) == symbol
                            && e15.contains(CircularClassOperations.removeSymbol(
                            pair, symbol))) {
                        contractsToE15 = true;
                    }
                }
                if (!contractsToE15) {
                    throw new IllegalStateException(
                            "Fixed-point E16 class does not contract to E15");
                }
            });
        }
        log.info("E16 verified");
    }

    public void verifyE17() {
        final var e16Stage = workDirectory.resolve("e16");
        final var stage = workDirectory.resolve("e17");
        final var childStage = workDirectory.resolve("e17-children");
        try (final var e16 = checkedClasses(e16Stage, "E16", 17);
             final var children = checkedClasses(
                     childStage, "E17_CHILDREN", 18);
             final var classes = checkedClasses(stage, "E17", 18);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), false)) {
            children.forEach(pair -> {
                if (!allRemovalsBelongTo(pair, e16)) {
                    if (classes.contains(pair)) {
                        throw new IllegalStateException(
                                "E17 contains a class with a nonextremal removal");
                    }
                    return;
                }
                final var shortSorting = sorter.sort(pair, 9);
                if (shortSorting.isPresent()) {
                    if (classes.contains(pair)) {
                        throw new IllegalStateException(
                                "E17 contains a class sortable within nine moves");
                    }
                } else {
                    if (!classes.contains(pair)) {
                        throw new IllegalStateException(
                                "E17 omits a depth-nine failure");
                    }
                    requireWitness(pair, 10, witnesses);
                }
            });
            classes.forEach(pair -> {
                if (!children.contains(pair)) {
                    throw new IllegalStateException(
                            "E17 contains a class outside the generated child set");
                }
                requireAllRemovals(pair, e16);
                requireWitness(pair, 10, witnesses);
            });
        }
        log.info("E17 verified");
    }

    public void verifyE18() {
        final var e17Stage = workDirectory.resolve("e17");
        final var stage = workDirectory.resolve("e18");
        final var childStage = workDirectory.resolve("e18-children");
        try (final var e17 = checkedClasses(e17Stage, "E17", 18);
             final var children = checkedClasses(
                     childStage, "E18_CHILDREN", 19);
             final var candidates = checkedClasses(
                     stage, "TD18_CERTIFICATE", 19);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), false)) {
            children.forEach(pair -> {
                final var qualified = allRemovalsBelongTo(pair, e17);
                if (qualified != candidates.contains(pair)) {
                    throw new IllegalStateException(
                            "TD(18) candidate filter mismatch");
                }
                if (qualified) {
                    requireWitness(pair, 10, witnesses);
                }
            });
            candidates.forEach(pair -> {
                if (!children.contains(pair)) {
                    throw new IllegalStateException(
                            "Certified TD(18) class is outside the generated child set");
                }
                requireAllRemovals(pair, e17);
                requireWitness(pair, 10, witnesses);
            });
        }
        log.info("TD(18) candidate certificate verified");
    }

    private ClassStore checkedClasses(
            final Path stage,
            final String name,
            final int domainSize
    ) {
        StageManifest.requireComplete(stage, name, domainSize);
        final var classes = new ClassStore(
                stage.resolve("classes"), domainSize, false);
        try {
            StageManifest.verify(stage, name, domainSize, classes.summarize());
            return classes;
        } catch (final RuntimeException failure) {
            classes.close();
            throw failure;
        }
    }

    private void requireNoSorting(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        if (sorter.sort(pair, maximumMoves).isPresent()) {
            throw new IllegalStateException(
                    "Extremal class sorts within excluded depth " + maximumMoves);
        }
    }

    private static void requireWitness(
            final CyclicTargetPair pair,
            final int maximumMoves,
            final WitnessStore witnesses
    ) {
        witnesses.find(pair, maximumMoves)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing sorting witness"));
    }

    private static void requireFixedPointCount(
            final CyclicTargetPair pair,
            final int expected
    ) {
        final var actual = CircularClassOperations.algebraicFixedPointCount(pair);
        if (actual != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " fixed points, found " + actual);
        }
    }

    private static void requireAllRemovals(
            final CyclicTargetPair pair,
            final ClassStore previous
    ) {
        for (final var removal : CircularClassOperations.removals(pair)) {
            if (!previous.contains(removal)) {
                throw new IllegalStateException(
                        "Class has a removal outside the preceding extremal set");
            }
        }
    }

    private static boolean allRemovalsBelongTo(
            final CyclicTargetPair pair,
            final ClassStore previous
    ) {
        for (final var removal : CircularClassOperations.removals(pair)) {
            if (!previous.contains(removal)) {
                return false;
            }
        }
        return true;
    }
}

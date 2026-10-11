package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/** Replays classification against known bounds and order-18 sorting witnesses. */
@Slf4j
public final class PipelineVerifier {
    private static final int E12_DOMAIN = 13;
    private static final int E13_DOMAIN = 14;
    private static final int E14_DOMAIN = 15;
    private static final int E15_DOMAIN = 16;
    private static final int E16_DOMAIN = 17;
    private static final int E17_DOMAIN = 18;
    private static final int E18_DOMAIN = 19;

    private final Path workDirectory;
    private final BoundedSorter sorter;

    public PipelineVerifier(final Path workDirectory, final BoundedSorter sorter) {
        this.workDirectory = workDirectory.toAbsolutePath();
        this.sorter = sorter;
    }

    public void verifyAll() {
        verifyE12();
        verifyE13();
        verifyH13();
        verifyE14();
        verifyE15();
        verifyE16();
        verifyE17();
        verifyE18();
    }

    public void verifyE12() {
        final var stage = workDirectory.resolve("e12");
        FixedPointFreeClassGenerator.requireComplete(
                E12_DOMAIN, stage.resolve("fixed-point-free.checkpoints"));
        try (final var classes = checkedClasses(stage, "E12", E12_DOMAIN)) {
            classes.forEach(pair -> {
                requireFixedPointCount(pair, 0);
                requireNoSorting(pair, 6);
            });
            verifyFixedPointFreeExactEnumeration(
                    stage, "E12", E12_DOMAIN, 6, 7,
                    classes);
        }
        log.info("E12 verified");
    }

    public void verifyE13() {
        verifyPropagatedExactStage(
                workDirectory.resolve("e12"), "E12", E12_DOMAIN,
                workDirectory.resolve("e13"), "E13", E13_DOMAIN,
                7, 8);
        try (final var e13 = checkedClasses(
                workDirectory.resolve("e13"), "E13", E13_DOMAIN)) {
            e13.forEach(pair -> requireFixedPointCount(pair, 0));
        }
        log.info("E13 verified");
    }

    public void verifyH13() {
        final var e12Stage = workDirectory.resolve("e12");
        final var layerStage = workDirectory.resolve("h13-ge7");
        final var e13Stage = workDirectory.resolve("e13");
        FixedPointFreeClassGenerator.requireComplete(
                E13_DOMAIN,
                layerStage.resolve("fixed-point-free.checkpoints"));
        try (final var e12 = checkedClasses(e12Stage, "E12", E12_DOMAIN);
             final var layer = checkedClasses(
                     layerStage, "H13_GE7", E13_DOMAIN);
             final var e13 = checkedClasses(
                     e13Stage, "E13", E13_DOMAIN)) {
            requireEveryFixedPointExpansion(e12, E13_DOMAIN, layer,
                    "Expand(E12) child is missing from H13(7)");

            layer.forEach(pair -> {
                final var fixedPoints = CircularClassOperations
                        .algebraicFixedPointCount(pair);
                if (fixedPoints != 0) {
                    requireFixedPointCount(pair, 1);
                    requireContractsTo(pair, e12,
                            "Fixed-point H13(7) class does not contract to E12");
                    if (e13.contains(pair)) {
                        throw new IllegalStateException(
                                "E13 contains a class with an algebraic fixed point");
                    }
                    return;
                }
                requireNoSorting(pair, 6);
            });

            e13.forEach(pair -> {
                if (!layer.contains(pair)) {
                    throw new IllegalStateException(
                            "E13 contains a class outside H13(7)");
                }
            });

            FixedPointFreeClassGenerator.generate(
                    E13_DOMAIN,
                    layerStage.resolve(
                            "verification/fixed-point-free.checkpoints"),
                    pair -> verifyS13Threshold(pair, layer));
        }
        log.info("H13(7) verified");
    }

    public void verifyE14() {
        verifyPropagatedExactStage(
                workDirectory.resolve("h13-ge7"), "H13_GE7", E13_DOMAIN,
                workDirectory.resolve("e14"), "E14", E14_DOMAIN,
                7, 8);
        try (final var e13 = checkedClasses(
                workDirectory.resolve("e13"), "E13", E13_DOMAIN);
             final var e14 = checkedClasses(
                     workDirectory.resolve("e14"), "E14", E14_DOMAIN)) {
            e14.forEach(pair -> {
                final var fixedPoints = CircularClassOperations
                        .algebraicFixedPointCount(pair);
                if (fixedPoints != 0) {
                    requireFixedPointCount(pair, 1);
                    requireContractsTo(pair, e13,
                            "Fixed-point E14 class does not contract to E13");
                }
            });
        }
        log.info("E14 verified");
    }

    public void verifyE15() {
        verifyPropagatedExactStage(
                workDirectory.resolve("e14"), "E14", E14_DOMAIN,
                workDirectory.resolve("e15"), "E15", E15_DOMAIN,
                8, 9);
        try (final var e15 = checkedClasses(
                workDirectory.resolve("e15"), "E15", E15_DOMAIN)) {
            e15.forEach(pair -> requireFixedPointCount(pair, 0));
        }
        log.info("E15 verified");
    }

    public void verifyE16() {
        final var e15Stage = workDirectory.resolve("e15");
        final var stage = workDirectory.resolve("e16");
        FixedPointFreeClassGenerator.requireComplete(
                E16_DOMAIN, stage.resolve("fixed-point-free.checkpoints"));
        try (final var e15 = checkedClasses(e15Stage, "E15", E15_DOMAIN);
             final var classes = checkedClasses(stage, "E16", E16_DOMAIN)) {
            requireEveryFixedPointExpansion(e15, E16_DOMAIN, classes,
                    "Expand(E15) child is missing from E16");
            classes.forEach(pair -> {
                final var fixedPoints = CircularClassOperations
                        .algebraicFixedPointCount(pair);
                if (fixedPoints == 0) {
                    requireNoSorting(pair, 8);
                    return;
                }
                requireFixedPointCount(pair, 1);
                requireContractsTo(pair, e15,
                        "Fixed-point E16 class does not contract to E15");
            });
            verifyFixedPointFreeExactEnumeration(
                    stage, "E16", E16_DOMAIN, 8, 9,
                    classes);
        }
        log.info("E16 verified");
    }

    public void verifyE17() {
        verifyPropagatedExactStage(
                workDirectory.resolve("e16"), "E16", E16_DOMAIN,
                workDirectory.resolve("e17"), "E17", E17_DOMAIN,
                9, 10);
        try (final var e17 = checkedClasses(
                workDirectory.resolve("e17"), "E17", E17_DOMAIN)) {
            e17.forEach(pair -> requireFixedPointCount(pair, 0));
        }
        log.info("E17 verified");
    }

    public void verifyE18() {
        final var parentStage = workDirectory.resolve("e17");
        final var stage = workDirectory.resolve("e18");
        try (final var parents = checkedClasses(
                parentStage, "E17", E17_DOMAIN);
             final var candidates = checkedClasses(
                     stage, "TD18_CERTIFICATE", E18_DOMAIN);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), false)) {
            candidates.forEach(pair -> {
                requireAllRemovals(pair, parents);
                requireWitness(pair, 10, witnesses);
            });
            verifyPropagatedCandidateSet(
                    parents, stage, candidates, witnesses);
        }
        log.info("TD(18) candidate certificate verified");
    }

    private void verifyS13Threshold(
            final CyclicTargetPair pair,
            final ClassStore layer
    ) {
        final var canonical = pair.canonicalRepresentative();
        final var expectedInLayer = liesBeyondDepth(canonical, 6);
        if (expectedInLayer != layer.contains(canonical)) {
            throw new IllegalStateException(
                    "Fixed-point-free H13(7) classification mismatch");
        }
    }

    private void verifyFixedPointFreeExactEnumeration(
            final Path stage,
            final String name,
            final int domainSize,
            final int excludedDepth,
            final int exactDepth,
            final ClassStore classes
    ) {
        KnownDiameters.requireExactDepth(domainSize, excludedDepth, exactDepth);
        FixedPointFreeClassGenerator.generate(
                domainSize,
                stage.resolve("verification/fixed-point-free.checkpoints"),
                pair -> {
                    final var canonical = pair.canonicalRepresentative();
                    final var expected = liesBeyondDepth(
                            canonical, excludedDepth);
                    if (expected != classes.contains(canonical)) {
                        throw new IllegalStateException(
                                name + " fixed-point-free classification mismatch");
                    }
                });
    }

    private void verifyPropagatedExactStage(
            final Path parentStage,
            final String parentName,
            final int parentDomain,
            final Path childStage,
            final String childName,
            final int childDomain,
            final int excludedDepth,
            final int exactDepth
    ) {
        KnownDiameters.requireExactDepth(childDomain, excludedDepth, exactDepth);
        try (final var parents = checkedClasses(
                parentStage, parentName, parentDomain);
             final var classes = checkedClasses(
                     childStage, childName, childDomain)) {
            classes.forEach(pair -> {
                requireAllRemovals(pair, parents);
                requireNoSorting(pair, excludedDepth);
            });

            final var verification = childStage.resolve("verification");
            if (StageManifest.isComplete(verification)) {
                try (final var processed = new ClassStore(
                        verification.resolve("processed-candidates"),
                        childDomain, false)) {
                    StageManifest.verify(
                            verification, "VERIFY_" + childName,
                            childDomain, processed.summarize());
                }
                return;
            }

            try (final var processedParents = new ClassStore(
                    verification.resolve("processed-parents"),
                    parentDomain, true);
                 final var processedCandidates = new ClassStore(
                         verification.resolve("processed-candidates"),
                         childDomain, true)) {
                parents.forEachParallel(availableProcessors(), parent -> {
                    if (processedParents.contains(parent)) {
                        return;
                    }
                    for (final var insertion
                            : CircularClassOperations.insertions(parent)) {
                        final var child = insertion.child();
                        if (!allRemovalsBelongTo(child, parents)) {
                            continue;
                        }
                        final var canonical = child.canonicalRepresentative();
                        processedCandidates.processIfAbsent(canonical, () -> {
                            final var expected = liesBeyondDepth(
                                    canonical, excludedDepth);
                            if (expected != classes.contains(canonical)) {
                                throw new IllegalStateException(
                                        childName + " propagation mismatch");
                            }
                        });
                    }
                    processedParents.add(parent);
                });
                StageManifest.complete(
                        verification, "VERIFY_" + childName, childDomain,
                        processedCandidates.summarize());
            }
        }
    }

    private void verifyPropagatedCandidateSet(
            final ClassStore parents,
            final Path stage,
            final ClassStore candidates,
            final WitnessStore witnesses
    ) {
        final var verification = stage.resolve("verification");
        if (StageManifest.isComplete(verification)) {
            try (final var processed = new ClassStore(
                    verification.resolve("processed-candidates"),
                    E18_DOMAIN, false)) {
                StageManifest.verify(
                        verification, "VERIFY_TD18_CERTIFICATE",
                        E18_DOMAIN, processed.summarize());
            }
            return;
        }

        try (final var processedParents = new ClassStore(
                verification.resolve("processed-parents"),
                E17_DOMAIN, true);
             final var processedCandidates = new ClassStore(
                     verification.resolve("processed-candidates"),
                     E18_DOMAIN, true)) {
            parents.forEachParallel(availableProcessors(), parent -> {
                if (processedParents.contains(parent)) {
                    return;
                }
                for (final var insertion : CircularClassOperations.insertions(parent)) {
                    final var child = insertion.child();
                    if (!allRemovalsBelongTo(child, parents)) {
                        continue;
                    }
                    final var canonical = child.canonicalRepresentative();
                    processedCandidates.processIfAbsent(canonical, () -> {
                        if (!candidates.contains(canonical)) {
                            throw new IllegalStateException(
                                    "TD(18) certificate omits a qualified child class");
                        }
                        requireWitness(canonical, 10, witnesses);
                    });
                }
                processedParents.add(parent);
            });
            StageManifest.complete(
                    verification, "VERIFY_TD18_CERTIFICATE", E18_DOMAIN,
                    processedCandidates.summarize());
        }
    }

    private boolean liesBeyondDepth(
            final CyclicTargetPair pair,
            final int maximumMoves
    ) {
        return KnownDiameters.liesBeyondDepth(pair, maximumMoves, sorter);
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

    private static void requireEveryFixedPointExpansion(
            final ClassStore parents,
            final int childDomain,
            final ClassStore children,
            final String failureMessage
    ) {
        if (parents.domainSize() + 1 != childDomain
                || children.domainSize() != childDomain) {
            throw new IllegalArgumentException(
                    "Fixed-point expansion domain mismatch");
        }
        parents.forEachParallel(
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                parent -> CircularClassOperations.fixedPointInsertions(parent).stream()
                .map(CircularClassOperations.Insertion::child)
                .forEach(child -> {
                    if (!children.contains(child)) {
                        throw new IllegalStateException(failureMessage);
                    }
                }));
    }

    private static void requireContractsTo(
            final CyclicTargetPair pair,
            final ClassStore parents,
            final String failureMessage
    ) {
        for (var symbol = 0; symbol < pair.getBeta().size(); symbol++) {
            if (pair.getOmega().image(symbol) == symbol
                    && parents.contains(CircularClassOperations.removeSymbol(
                    pair, symbol))) {
                return;
            }
        }
        throw new IllegalStateException(failureMessage);
    }

    private static void requireAllRemovals(
            final CyclicTargetPair pair,
            final ClassStore previous
    ) {
        if (!allRemovalsBelongTo(pair, previous)) {
            throw new IllegalStateException(
                    "Class has a removal outside the required preceding set");
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

    private static int availableProcessors() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}

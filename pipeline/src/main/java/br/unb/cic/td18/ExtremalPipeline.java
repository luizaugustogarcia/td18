package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/** Durable end-to-end computation from E12 to the TD(18) candidates. */
@Slf4j
public final class ExtremalPipeline {
    private static final int E12_DOMAIN = 13;
    private static final int E13_DOMAIN = 14;
    private static final int E14_DOMAIN = 15;
    private static final int E15_DOMAIN = 16;
    private static final int E16_DOMAIN = 17;
    private static final int E17_DOMAIN = 18;
    private static final int E18_DOMAIN = 19;

    private final Path workDirectory;
    private final BoundedSorter sorter;

    public ExtremalPipeline(
            final Path workDirectory,
            final BoundedSorter sorter
    ) {
        this.workDirectory = workDirectory.toAbsolutePath();
        this.sorter = sorter;
    }

    /** Enumerates every fixed-point-free order-12 class and retains distance 7. */
    public void runE12() {
        final var stage = stage("e12");
        if (StageManifest.isComplete(stage)) {
            StageManifest.requireComplete(stage, "E12", E12_DOMAIN);
            log.info("E12 is already complete");
            return;
        }
        final var examined = new LongAdder();
        final var retained = new LongAdder();
        try (final var classes = new ClassStore(
                stage.resolve("classes"), E12_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            FixedPointFreeClassGenerator.generate(
                    E12_DOMAIN,
                    stage.resolve("fixed-point-free.checkpoints"),
                    pair -> {
                        examined.increment();
                        if (retainExactDistance(
                                pair, 6, 7, classes, witnesses)) {
                            retained.increment();
                        }
                    });
            final var summary = classes.summarize();
            StageManifest.complete(stage, "E12", E12_DOMAIN, summary);
            log.info("E12 complete: examined={}, retained={}, stored={}",
                    examined.sum(), retained.sum(), summary.count());
        }
    }

    /** Computes H13(7) and its exact-distance-eight subset E13 in one pass. */
    public void runE13() {
        final var e12Stage = stage("e12");
        StageManifest.requireComplete(e12Stage, "E12", E12_DOMAIN);
        final var layerStage = stage("h13-ge7");
        final var e13Stage = stage("e13");
        recoverS13Manifests(layerStage, e13Stage);
        if (StageManifest.isComplete(layerStage)) {
            StageManifest.requireComplete(
                    layerStage, "H13_GE7", E13_DOMAIN);
            StageManifest.requireComplete(e13Stage, "E13", E13_DOMAIN);
            log.info("H13(7) and E13 are already complete");
            return;
        }

        final var expanded = new LongAdder();
        final var examined = new LongAdder();
        final var retainedInLayer = new LongAdder();
        final var retainedInE13 = new LongAdder();
        try (final var e12 = new ClassStore(
                e12Stage.resolve("classes"), E12_DOMAIN, false);
             final var layer = new ClassStore(
                     layerStage.resolve("classes"), E13_DOMAIN, true);
             final var layerWitnesses = new WitnessStore(
                     layerStage.resolve("witnesses"), true);
             final var e13 = new ClassStore(
                     e13Stage.resolve("classes"), E13_DOMAIN, true);
             final var e13Witnesses = new WitnessStore(
                     e13Stage.resolve("witnesses"), true)) {
            addFixedPointExpansions(e12, E13_DOMAIN, layer, expanded);

            FixedPointFreeClassGenerator.generate(
                    E13_DOMAIN,
                    layerStage.resolve("fixed-point-free.checkpoints"),
                    pair -> {
                        examined.increment();
                        final var result = classifyS13Layer(
                                pair, layer, layerWitnesses,
                                e13, e13Witnesses);
                        if (result.inLayer()) {
                            retainedInLayer.increment();
                        }
                        if (result.inE13()) {
                            retainedInE13.increment();
                        }
                    });

            final var e13Summary = e13.summarize();
            final var layerSummary = layer.summarize();
            // E13 is written first so an interrupted two-manifest commit can
            // safely recover H13(7), whose store is already complete.
            StageManifest.complete(
                    e13Stage, "E13", E13_DOMAIN, e13Summary);
            StageManifest.complete(
                    layerStage, "H13_GE7", E13_DOMAIN, layerSummary);
            log.info("S13 layers complete: expanded={}, fixed-point-free "
                            + "examined={}, fixed-point-free in H13(7)={}, "
                            + "new E13={}, E13 stored={}, H13(7) stored={}",
                    expanded.sum(), examined.sum(), retainedInLayer.sum(),
                    retainedInE13.sum(), e13Summary.count(), layerSummary.count());
        }
    }

    /** Propagates H13(7) and retains the exact-distance-eight classes E14. */
    public void runE14() {
        propagateExactDistance(
                stage("h13-ge7"), "H13_GE7", E13_DOMAIN,
                stage("e14"), "E14", E14_DOMAIN, 7, 8);
    }

    /** Propagates E14 and retains the exact-distance-nine classes E15. */
    public void runE15() {
        propagateExactDistance(
                stage("e14"), "E14", E14_DOMAIN,
                stage("e15"), "E15", E15_DOMAIN, 8, 9);
    }

    /** Builds Expand(E15) and classifies every fixed-point-free order-16 class. */
    public void runE16() {
        final var e15Stage = stage("e15");
        StageManifest.requireComplete(e15Stage, "E15", E15_DOMAIN);
        final var stage = stage("e16");
        if (StageManifest.isComplete(stage)) {
            StageManifest.requireComplete(stage, "E16", E16_DOMAIN);
            log.info("E16 is already complete");
            return;
        }
        final var expanded = new LongAdder();
        final var examined = new LongAdder();
        final var retained = new LongAdder();
        try (final var e15 = new ClassStore(
                e15Stage.resolve("classes"), E15_DOMAIN, false);
             final var classes = new ClassStore(
                     stage.resolve("classes"), E16_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            addFixedPointExpansions(e15, E16_DOMAIN, classes, expanded);

            FixedPointFreeClassGenerator.generate(
                    E16_DOMAIN,
                    stage.resolve("fixed-point-free.checkpoints"),
                    pair -> {
                        examined.increment();
                        if (retainExactDistance(
                                pair, 8, 9, classes, witnesses)) {
                            retained.increment();
                        }
                    });
            final var summary = classes.summarize();
            StageManifest.complete(stage, "E16", E16_DOMAIN, summary);
            log.info("E16 complete: expanded={}, fixed-point-free examined={}, "
                            + "fixed-point-free retained={}, stored={}",
                    expanded.sum(), examined.sum(), retained.sum(), summary.count());
        }
    }

    /** Propagates E16 and retains the exact-distance-ten classes E17. */
    public void runE17() {
        propagateExactDistance(
                stage("e16"), "E16", E16_DOMAIN,
                stage("e17"), "E17", E17_DOMAIN, 9, 10);
    }

    /** Certifies every all-removals-in-E17 order-18 candidate within ten moves. */
    public void runE18() {
        final var e17Stage = stage("e17");
        StageManifest.requireComplete(e17Stage, "E17", E17_DOMAIN);
        final var stage = stage("e18");
        if (StageManifest.isComplete(stage)) {
            StageManifest.requireComplete(
                    stage, "TD18_CERTIFICATE", E18_DOMAIN);
            log.info("TD(18) candidate certification is already complete");
            return;
        }

        final var examined = new LongAdder();
        final var qualified = new LongAdder();
        try (final var parents = new ClassStore(
                e17Stage.resolve("classes"), E17_DOMAIN, false);
             final var processedParents = new ClassStore(
                     stage.resolve("processed-parents"), E17_DOMAIN, true);
             final var processedCandidates = new ClassStore(
                     stage.resolve("processed-candidates"), E18_DOMAIN, true);
             final var candidates = new ClassStore(
                     stage.resolve("classes"), E18_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            parents.forEach(parent -> {
                if (processedParents.contains(parent)) {
                    return;
                }
                for (final var insertion : CircularClassOperations.insertions(parent)) {
                    examined.increment();
                    final var child = insertion.child();
                    if (!allRemovalsBelongTo(child, parents)) {
                        continue;
                    }
                    final var canonical = child.canonicalRepresentative();
                    if (processedCandidates.contains(canonical)) {
                        continue;
                    }
                    final var witness = sorter.sort(canonical, 10)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Unresolved TD(18) candidate: "
                                            + canonical.extendedToricClassKey()));
                    if (witness.isEmpty()) {
                        throw new IllegalStateException(
                                "An order-18 candidate unexpectedly represents "
                                        + "the identity");
                    }
                    witnesses.put(canonical, witness);
                    if (candidates.add(canonical)) {
                        qualified.increment();
                    }
                    processedCandidates.add(canonical);
                }
                processedParents.add(parent);
            });
            final var summary = candidates.summarize();
            StageManifest.complete(
                    stage, "TD18_CERTIFICATE", E18_DOMAIN, summary);
            log.info("TD(18) candidate certification complete: raw children={}, "
                            + "new qualified={}, stored={}",
                    examined.sum(), qualified.sum(), summary.count());
        }
    }

    public void runAll() {
        runE12();
        runE13();
        runE14();
        runE15();
        runE16();
        runE17();
        runE18();
    }

    private void propagateExactDistance(
            final Path parentStage,
            final String parentName,
            final int parentDomain,
            final Path childStage,
            final String childName,
            final int childDomain,
            final int excludedDepth,
            final int exactDepth
    ) {
        StageManifest.requireComplete(parentStage, parentName, parentDomain);
        if (StageManifest.isComplete(childStage)) {
            StageManifest.requireComplete(childStage, childName, childDomain);
            log.info("{} is already complete", childName);
            return;
        }

        final var examined = new LongAdder();
        final var qualified = new LongAdder();
        final var retained = new LongAdder();
        try (final var parents = new ClassStore(
                parentStage.resolve("classes"), parentDomain, false);
             final var processedParents = new ClassStore(
                     childStage.resolve("processed-parents"), parentDomain, true);
             final var processedCandidates = new ClassStore(
                     childStage.resolve("processed-candidates"), childDomain, true);
             final var classes = new ClassStore(
                     childStage.resolve("classes"), childDomain, true);
             final var witnesses = new WitnessStore(
                     childStage.resolve("witnesses"), true)) {
            parents.forEach(parent -> {
                if (processedParents.contains(parent)) {
                    return;
                }
                for (final var insertion : CircularClassOperations.insertions(parent)) {
                    examined.increment();
                    final var child = insertion.child();
                    if (!allRemovalsBelongTo(child, parents)) {
                        continue;
                    }
                    final var canonical = child.canonicalRepresentative();
                    if (processedCandidates.contains(canonical)) {
                        continue;
                    }
                    qualified.increment();
                    if (retainExactDistance(
                            canonical, excludedDepth, exactDepth,
                            classes, witnesses)) {
                        retained.increment();
                    }
                    processedCandidates.add(canonical);
                }
                processedParents.add(parent);
            });
            final var summary = classes.summarize();
            StageManifest.complete(
                    childStage, childName, childDomain, summary);
            log.info("{} complete: raw children={}, new removal-qualified={}, "
                            + "new retained={}, stored={}",
                    childName, examined.sum(), qualified.sum(), retained.sum(),
                    summary.count());
        }
    }

    private LayerResult classifyS13Layer(
            final CyclicTargetPair pair,
            final ClassStore layer,
            final WitnessStore layerWitnesses,
            final ClassStore e13,
            final WitnessStore e13Witnesses
    ) {
        final var canonical = pair.canonicalRepresentative();
        if (layer.contains(canonical)) {
            return new LayerResult(false, false);
        }
        if (ApproximationBound.guaranteedMoves(canonical.getThreeNorm()) <= 6
                || sorter.sort(canonical, 6).isPresent()) {
            return new LayerResult(false, false);
        }

        final var depthSeven = sorter.sort(canonical, 7);
        if (depthSeven.isPresent()) {
            layerWitnesses.put(canonical, depthSeven.orElseThrow());
            layer.add(canonical);
            return new LayerResult(true, false);
        }

        final var depthEight = sorter.sort(canonical, 8)
                .orElseThrow(() -> new IllegalStateException(
                        "Known TD(13) bound failed for class "
                                + canonical.extendedToricClassKey()));
        if (depthEight.isEmpty()) {
            throw new IllegalStateException(
                    "A member of E13 unexpectedly represents the identity");
        }
        layerWitnesses.put(canonical, depthEight);
        e13Witnesses.put(canonical, depthEight);
        e13.add(canonical);
        layer.add(canonical);
        return new LayerResult(true, true);
    }

    private boolean retainExactDistance(
            final CyclicTargetPair pair,
            final int excludedDepth,
            final int exactDepth,
            final ClassStore classes,
            final WitnessStore witnesses
    ) {
        final var canonical = pair.canonicalRepresentative();
        if (classes.contains(canonical) && witnesses.contains(canonical)) {
            return false;
        }
        if (ApproximationBound.guaranteedMoves(canonical.getThreeNorm())
                <= excludedDepth) {
            return false;
        }
        if (sorter.sort(canonical, excludedDepth).isPresent()) {
            return false;
        }
        final var witness = sorter.sort(canonical, exactDepth)
                .orElseThrow(() -> new IllegalStateException(
                        "Known diameter bound failed for class "
                                + canonical.extendedToricClassKey()));
        if (witness.isEmpty()) {
            throw new IllegalStateException(
                    "An extremal candidate unexpectedly represents the identity");
        }
        witnesses.put(canonical, witness);
        return classes.add(canonical);
    }

    private static void addFixedPointExpansions(
            final ClassStore parents,
            final int childDomain,
            final ClassStore children,
            final LongAdder added
    ) {
        parents.forEach(parent -> CircularClassOperations.insertions(parent).stream()
                .map(CircularClassOperations.Insertion::child)
                .filter(child -> child.getOmega().image(childDomain - 1)
                        == childDomain - 1)
                .map(CyclicTargetPair::canonicalRepresentative)
                .forEach(child -> {
                    if (children.add(child)) {
                        added.increment();
                    }
                }));
    }

    private static boolean allRemovalsBelongTo(
            final CyclicTargetPair candidate,
            final ClassStore previous
    ) {
        for (final var removal : CircularClassOperations.removals(candidate)) {
            if (!previous.contains(removal)) {
                return false;
            }
        }
        return true;
    }

    private static void recoverS13Manifests(
            final Path layerStage,
            final Path e13Stage
    ) {
        final var layerComplete = StageManifest.isComplete(layerStage);
        final var e13Complete = StageManifest.isComplete(e13Stage);
        if (layerComplete && !e13Complete) {
            throw new IllegalStateException(
                    "H13(7) is complete but its E13 subset is incomplete");
        }
        if (!layerComplete && e13Complete) {
            FixedPointFreeClassGenerator.requireComplete(
                    E13_DOMAIN,
                    layerStage.resolve("fixed-point-free.checkpoints"));
            try (final var layer = new ClassStore(
                    layerStage.resolve("classes"), E13_DOMAIN, false)) {
                StageManifest.complete(
                        layerStage, "H13_GE7", E13_DOMAIN,
                        layer.summarize());
            }
        }
    }

    private Path stage(final String name) {
        return workDirectory.resolve(name);
    }

    private record LayerResult(boolean inLayer, boolean inE13) {
    }
}

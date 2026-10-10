package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/** Durable end-to-end computation of E15, E16, E17, and the TD(18) candidates. */
@Slf4j
public final class ExtremalPipeline {
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

    /** Enumerates every fixed-point-free class at cyclic order 16 and retains distance 9. */
    public void runE15() {
        final var stage = stage("e15");
        if (StageManifest.isComplete(stage)) {
            StageManifest.requireComplete(stage, "E15", E15_DOMAIN);
            log.info("E15 is already complete");
            return;
        }
        final var examined = new LongAdder();
        final var retained = new LongAdder();
        try (final var classes = new ClassStore(
                stage.resolve("classes"), E15_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            FixedPointFreeClassGenerator.generate(
                    E15_DOMAIN,
                    stage.resolve("fixed-point-free.checkpoints"),
                    pair -> {
                        examined.increment();
                        if (retainExactDistance(pair, 8, 9, classes, witnesses)) {
                            retained.increment();
                        }
                    });
            final var summary = classes.summarize();
            StageManifest.complete(stage, "E15", E15_DOMAIN, summary);
            log.info("E15 complete: examined={}, retained={}, stored={}",
                    examined.sum(), retained.sum(), summary.count());
        }
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
            e15.forEach(parent -> CircularClassOperations.insertions(parent).stream()
                    .map(CircularClassOperations.Insertion::child)
                    .filter(child -> child.getOmega().image(E16_DOMAIN - 1)
                            == E16_DOMAIN - 1)
                    .map(CyclicTargetPair::canonicalRepresentative)
                    .forEach(child -> {
                        if (classes.add(child)) {
                            expanded.increment();
                        }
                    }));

            FixedPointFreeClassGenerator.generate(
                    E16_DOMAIN,
                    stage.resolve("fixed-point-free.checkpoints"),
                    pair -> {
                        examined.increment();
                        if (retainExactDistance(pair, 8, 9, classes, witnesses)) {
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

    /** Applies the all-removals-in-E16 filter and retains exact distance 10. */
    public void runE17() {
        final var e16Stage = stage("e16");
        StageManifest.requireComplete(e16Stage, "E16", E16_DOMAIN);
        final var candidates = prepareChildren(
                e16Stage, "E16", E16_DOMAIN,
                stage("e17-children"), "E17_CHILDREN", E17_DOMAIN);
        final var stage = stage("e17");
        if (StageManifest.isComplete(stage)) {
            StageManifest.requireComplete(stage, "E17", E17_DOMAIN);
            log.info("E17 is already complete");
            return;
        }
        try (final var e16 = new ClassStore(
                e16Stage.resolve("classes"), E16_DOMAIN, false);
             final var candidateStore = new ClassStore(
                     candidates.resolve("classes"), E17_DOMAIN, false);
             final var processed = new ClassStore(
                     stage.resolve("processed"), E17_DOMAIN, true);
             final var classes = new ClassStore(
                     stage.resolve("classes"), E17_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            final var qualified = new LongAdder();
            candidateStore.forEach(candidate -> {
                if (processed.contains(candidate)) {
                    return;
                }
                if (allRemovalsBelongTo(candidate, e16)) {
                    qualified.increment();
                    retainExactDistance(candidate, 9, 10, classes, witnesses);
                }
                processed.add(candidate);
            });
            final var summary = classes.summarize();
            StageManifest.complete(stage, "E17", E17_DOMAIN, summary);
            log.info("E17 complete: removal-qualified={}, retained={}",
                    qualified.sum(), summary.count());
        }
    }

    /** Certifies every all-removals-in-E17 candidate within ten moves. */
    public void runE18() {
        final var e17Stage = stage("e17");
        StageManifest.requireComplete(e17Stage, "E17", E17_DOMAIN);
        final var candidates = prepareChildren(
                e17Stage, "E17", E17_DOMAIN,
                stage("e18-children"), "E18_CHILDREN", E18_DOMAIN);
        final var stage = stage("e18");
        if (StageManifest.isComplete(stage)) {
            StageManifest.requireComplete(
                    stage, "TD18_CERTIFICATE", E18_DOMAIN);
            log.info("TD(18) candidate certification is already complete");
            return;
        }
        try (final var e17 = new ClassStore(
                e17Stage.resolve("classes"), E17_DOMAIN, false);
             final var candidateStore = new ClassStore(
                     candidates.resolve("classes"), E18_DOMAIN, false);
             final var processed = new ClassStore(
                     stage.resolve("processed"), E18_DOMAIN, true);
             final var qualified = new ClassStore(
                     stage.resolve("classes"), E18_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            candidateStore.forEach(candidate -> {
                if (processed.contains(candidate)) {
                    return;
                }
                if (allRemovalsBelongTo(candidate, e17)) {
                    final var canonical = candidate.canonicalRepresentative();
                    final var witness = sorter.sort(canonical, 10)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Unresolved TD(18) candidate: "
                                            + canonical.extendedToricClassKey()));
                    if (witness.isEmpty()) {
                        throw new IllegalStateException(
                                "An order-18 candidate unexpectedly represents the identity");
                    }
                    qualified.add(canonical);
                    witnesses.put(canonical, witness);
                }
                processed.add(candidate);
            });
            final var summary = qualified.summarize();
            StageManifest.complete(stage, "TD18_CERTIFICATE", E18_DOMAIN, summary);
            log.info("TD(18) candidate certification complete: {} witnesses",
                    summary.count());
        }
    }

    public void runAll() {
        runE15();
        runE16();
        runE17();
        runE18();
    }

    private Path prepareChildren(
            final Path parentStage,
            final String parentName,
            final int parentDomain,
            final Path childStage,
            final String childName,
            final int childDomain
    ) {
        StageManifest.requireComplete(parentStage, parentName, parentDomain);
        if (StageManifest.isComplete(childStage)) {
            StageManifest.requireComplete(childStage, childName, childDomain);
            return childStage;
        }
        try (final var parents = new ClassStore(
                parentStage.resolve("classes"), parentDomain, false);
             final var children = new ClassStore(
                     childStage.resolve("classes"), childDomain, true)) {
            parents.forEach(parent -> CircularClassOperations.childClasses(parent)
                    .forEach(children::add));
            final var summary = children.summarize();
            StageManifest.complete(childStage, childName, childDomain, summary);
            log.info("{} complete: {} canonical child classes",
                    childName, summary.count());
        }
        return childStage;
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
        final var added = classes.add(canonical);
        witnesses.put(canonical, witness);
        return added;
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

    private Path stage(final String name) {
        return workDirectory.resolve(name);
    }
}

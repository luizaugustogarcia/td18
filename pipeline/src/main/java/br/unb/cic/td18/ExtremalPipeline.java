package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Durable end-to-end computation from E12 to the TD(18) candidates. */
@Slf4j
public final class ExtremalPipeline {
    private static final long PROPAGATION_LOG_INTERVAL = 10_000;
    private static final int E12_DOMAIN = 13;
    private static final int E13_DOMAIN = 14;
    private static final int E14_DOMAIN = 15;
    private static final int E15_DOMAIN = 16;
    private static final int E16_DOMAIN = 17;
    private static final int E17_DOMAIN = 18;
    private static final int E18_DOMAIN = 19;

    private final Path workDirectory;
    private final BoundedSorter sorter;
    private final int lookupBatchSize;
    private final int writeFlushSeconds;

    public ExtremalPipeline(
            final Path workDirectory,
            final BoundedSorter sorter
    ) {
        this(workDirectory, sorter, 500, 60);
    }

    public ExtremalPipeline(
            final Path workDirectory,
            final BoundedSorter sorter,
            final int lookupBatchSize
    ) {
        this(workDirectory, sorter, lookupBatchSize, 60);
    }

    public ExtremalPipeline(
            final Path workDirectory,
            final BoundedSorter sorter,
            final int lookupBatchSize,
            final int writeFlushSeconds
    ) {
        if (lookupBatchSize < 1) {
            throw new IllegalArgumentException("Lookup batch size must be positive");
        }
        if (writeFlushSeconds < 1) {
            throw new IllegalArgumentException(
                    "Write flush period must be positive");
        }
        this.workDirectory = workDirectory.toAbsolutePath();
        this.sorter = sorter;
        this.lookupBatchSize = lookupBatchSize;
        this.writeFlushSeconds = writeFlushSeconds;
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
            try (final var writer = new RetainedClassWriter(
                    classes, witnesses, writeFlushSeconds)) {
                FixedPointFreeClassGenerator.generate(
                        E12_DOMAIN,
                        stage.resolve("fixed-point-free.checkpoints"),
                        pair -> {
                            examined.increment();
                            exactDistanceWitness(pair, 6, 7).ifPresent(witness -> {
                                writer.add(pair, witness);
                                retained.increment();
                            });
                        },
                        writer::flush);
            }
            final var summary = classes.summarize();
            StageManifest.complete(stage, "E12", E12_DOMAIN, summary);
            log.info("E12 complete: examined={}, retained={}, stored={}",
                    examined.sum(), retained.sum(), summary.count());
        }
    }

    /** Propagates E12 and retains the exact-distance-eight classes E13. */
    public void runE13() {
        propagateExactDistance(
                stage("e12"), "E12", E12_DOMAIN,
                stage("e13"), "E13", E13_DOMAIN, 7, 8);
    }

    /** Computes the threshold layer H13(7) needed to propagate E14. */
    public void runH13() {
        final var e12Stage = stage("e12");
        StageManifest.requireComplete(e12Stage, "E12", E12_DOMAIN);
        final var layerStage = stage("h13-ge7");
        if (StageManifest.isComplete(layerStage)) {
            StageManifest.requireComplete(
                    layerStage, "H13_GE7", E13_DOMAIN);
            log.info("H13(7) is already complete");
            return;
        }

        final var expanded = new LongAdder();
        final var examined = new LongAdder();
        final var retainedInLayer = new LongAdder();
        try (final var e12 = new ClassStore(
                e12Stage.resolve("classes"), E12_DOMAIN, false);
             final var layer = new ClassStore(
                     layerStage.resolve("classes"), E13_DOMAIN, true);
             final var layerWitnesses = new WitnessStore(
                     layerStage.resolve("witnesses"), true)) {
            addFixedPointExpansions(e12, E13_DOMAIN, layer, expanded);

            try (final var writer = new RetainedClassWriter(
                    layer, layerWitnesses, writeFlushSeconds)) {
                FixedPointFreeClassGenerator.generate(
                        E13_DOMAIN,
                        layerStage.resolve("fixed-point-free.checkpoints"),
                        pair -> {
                            examined.increment();
                            thresholdDistanceWitness(pair, 6, 8)
                                    .ifPresent(witness -> {
                                        writer.add(pair, witness);
                                        retainedInLayer.increment();
                                    });
                        },
                        writer::flush);
            }

            final var layerSummary = layer.summarize();
            StageManifest.complete(
                    layerStage, "H13_GE7", E13_DOMAIN, layerSummary);
            log.info("H13(7) complete: expanded={}, fixed-point-free "
                            + "examined={}, fixed-point-free retained={}, "
                            + "stored={}",
                    expanded.sum(), examined.sum(), retainedInLayer.sum(),
                    layerSummary.count());
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

            try (final var writer = new RetainedClassWriter(
                    classes, witnesses, writeFlushSeconds)) {
                FixedPointFreeClassGenerator.generate(
                        E16_DOMAIN,
                        stage.resolve("fixed-point-free.checkpoints"),
                        pair -> {
                            examined.increment();
                            exactDistanceWitness(pair, 8, 9).ifPresent(witness -> {
                                writer.add(pair, witness);
                                retained.increment();
                            });
                        },
                        writer::flush);
            }
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
        final var completedParents = new AtomicLong();
        try (final var parents = new ClassStore(
                e17Stage.resolve("classes"), E17_DOMAIN, false);
             final var candidates = new ClassStore(
                     stage.resolve("classes"), E18_DOMAIN, true);
             final var witnesses = new WitnessStore(
                     stage.resolve("witnesses"), true)) {
            try (final var writer = new RetainedClassWriter(
                    candidates, witnesses, writeFlushSeconds)) {
                forEachQualifiedChild(parents, examined,
                        completedParents, "TD(18)", qualified, null, children -> {
                    for (final var child : children) {
                        final var witness = sorter.sort(child, 10)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Unresolved TD(18) candidate: "
                                                + child.extendedToricClassKey()));
                        if (witness.isEmpty()) {
                            throw new IllegalStateException(
                                    "An order-18 candidate unexpectedly represents "
                                            + "the identity");
                        }
                        writer.add(child, witness);
                    }
                    qualified.add(children.size());
                });
            }
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
        runH13();
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
        final var completedParents = new AtomicLong();
        try (final var parents = new ClassStore(
                parentStage.resolve("classes"), parentDomain, false);
             final var classes = new ClassStore(
                     childStage.resolve("classes"), childDomain, true);
             final var witnesses = new WitnessStore(
                     childStage.resolve("witnesses"), true)) {
            try (final var writer = new RetainedClassWriter(
                    classes, witnesses, writeFlushSeconds)) {
                forEachQualifiedChild(parents, examined,
                        completedParents, childName, qualified, retained, children -> {
                    qualified.add(children.size());
                    for (final var child : children) {
                        exactDistanceWitness(child, excludedDepth, exactDepth)
                                .ifPresent(witness -> {
                                    writer.add(child, witness);
                                    retained.increment();
                                });
                    }
                });
            }
            final var summary = classes.summarize();
            StageManifest.complete(
                    childStage, childName, childDomain, summary);
            log.info("{} complete: raw children={}, new removal-qualified={}, "
                            + "new retained={}, stored={}",
                    childName, examined.sum(), qualified.sum(), retained.sum(),
                    summary.count());
        }
    }

    private void forEachQualifiedChild(
            final ClassStore parents,
            final LongAdder examined,
            final AtomicLong completedParents,
            final String stageName,
            final LongAdder qualified,
            final LongAdder retained,
            final Consumer<List<CyclicTargetPair>> consumer
    ) {
        final var childrenPerParent = parents.domainSize() * parents.domainSize();
        final var parentBatchSize = 1 + (lookupBatchSize - 1) / childrenPerParent;
        final var startedAt = System.nanoTime();
        log.info("{} propagation: workers={}, lookup batch size={}, parents/batch={}",
                stageName, availableProcessors(), lookupBatchSize, parentBatchSize);
        parents.forEachBatchParallel(availableProcessors(), parentBatchSize, batch -> {
            final var ownedQualifiedChildren = new ArrayList<CyclicTargetPair>();
            for (final var parent : batch) {
                examined.add(childrenPerParent);
                RemovalMembershipFilter.forEachQualifiedOwnedBy(
                        parent,
                        CircularClassOperations.childClasses(parent),
                        parents,
                        lookupBatchSize,
                        ownedQualifiedChildren::add);
            }
            consumer.accept(List.copyOf(ownedQualifiedChildren));
            for (final var ignored : batch) {
                final var completed = completedParents.incrementAndGet();
                if (completed % PROPAGATION_LOG_INTERVAL == 0) {
                    final var elapsedSeconds = (System.nanoTime() - startedAt) / 1.0e9;
                    log.info("{} propagation progress: new parents={}, raw children={}, "
                                    + "removal-qualified={}, retained={}, raw children/s={}",
                            stageName, completed, examined.sum(), qualified.sum(),
                            retained == null ? qualified.sum() : retained.sum(),
                            Math.round(examined.sum() / elapsedSeconds));
                }
            }
        });
    }

    private Optional<List<Cycle>> thresholdDistanceWitness(
            final CyclicTargetPair pair,
            final int excludedDepth,
            final int witnessDepth
    ) {
        final var canonical = pair.canonicalRepresentative();
        if (ApproximationBound.guaranteedMoves(canonical.getThreeNorm())
                <= excludedDepth
                || sorter.sort(canonical, excludedDepth).isPresent()) {
            return Optional.empty();
        }
        final var witness = sorter.sort(canonical, witnessDepth)
                .orElseThrow(() -> new IllegalStateException(
                        "Known diameter bound failed for threshold class "
                                + canonical.extendedToricClassKey()));
        if (witness.isEmpty()) {
            throw new IllegalStateException(
                    "A threshold class unexpectedly represents the identity");
        }
        return Optional.of(witness);
    }

    private Optional<List<Cycle>> exactDistanceWitness(
            final CyclicTargetPair pair,
            final int excludedDepth,
            final int exactDepth
    ) {
        final var canonical = pair.canonicalRepresentative();
        if (ApproximationBound.guaranteedMoves(canonical.getThreeNorm())
                <= excludedDepth) {
            return Optional.empty();
        }
        if (sorter.sort(canonical, excludedDepth).isPresent()) {
            return Optional.empty();
        }
        final var witness = sorter.sort(canonical, exactDepth)
                .orElseThrow(() -> new IllegalStateException(
                        "Known diameter bound failed for class "
                                + canonical.extendedToricClassKey()));
        if (witness.isEmpty()) {
            throw new IllegalStateException(
                    "An extremal candidate unexpectedly represents the identity");
        }
        return Optional.of(witness);
    }

    private static void addFixedPointExpansions(
            final ClassStore parents,
            final int childDomain,
            final ClassStore children,
            final LongAdder added
    ) {
        if (parents.domainSize() + 1 != childDomain
                || children.domainSize() != childDomain) {
            throw new IllegalArgumentException(
                    "Fixed-point expansion domain mismatch");
        }
        parents.forEachParallel(availableProcessors(),
                parent -> {
                    final var expansions = CircularClassOperations
                            .fixedPointInsertions(parent).stream()
                            .map(CircularClassOperations.Insertion::child)
                            .map(CyclicTargetPair::canonicalRepresentative)
                            .toList();
                    children.putAll(expansions);
                    added.add(expansions.size());
                });
    }

    private Path stage(final String name) {
        return workDirectory.resolve(name);
    }

    private static int availableProcessors() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}

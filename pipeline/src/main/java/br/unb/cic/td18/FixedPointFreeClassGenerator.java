package br.unb.cic.td18;

import br.unb.cic.tdp.DirectTwistedBraceletGenerator;
import br.unb.cic.tdp.Partitions;
import br.unb.cic.tdp.base.CyclicTargetPair;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Streams every fixed-point-free extended-toric class at one cyclic order. */
public final class FixedPointFreeClassGenerator {
    private FixedPointFreeClassGenerator() {
    }

    public static void generate(
            final int domainSize,
            final Path checkpointFile,
            final Consumer<CyclicTargetPair> consumer
    ) {
        generate(domainSize, checkpointFile, consumer, () -> { });
    }

    /** Generates classes and flushes caller state before each durable checkpoint. */
    public static void generate(
            final int domainSize,
            final Path checkpointFile,
            final Consumer<CyclicTargetPair> consumer,
            final Runnable beforeCheckpoint
    ) {
        Objects.requireNonNull(checkpointFile);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(beforeCheckpoint);
        final var checkpoints = new CheckpointSet(checkpointFile);
        final var planned = plannedCheckpoints(domainSize);
        if (!planned.containsAll(checkpoints.snapshot())) {
            throw new IllegalStateException(
                    "Checkpoint file contains entries from an incompatible generation plan");
        }
        for (final var partition : Partitions.generateValidPartitions(domainSize)) {
            for (final var annotated : AnnotatedPartitions.of(partition)) {
                final var checkpoint = annotated.checkpointKey();
                if (checkpoints.contains(checkpoint)) {
                    continue;
                }
                DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(
                        annotated.partition(),
                        annotated.oriented(),
                        () -> { },
                        consumer);
                beforeCheckpoint.run();
                checkpoints.add(checkpoint);
            }
        }
        requireComplete(domainSize, checkpointFile);
    }

    /** Checks that every and only planned annotated cycle types completed. */
    public static void requireComplete(
            final int domainSize,
            final Path checkpointFile
    ) {
        final var actual = new CheckpointSet(checkpointFile).snapshot();
        final var planned = plannedCheckpoints(domainSize);
        if (!actual.equals(planned)) {
            final var missing = new LinkedHashSet<>(planned);
            missing.removeAll(actual);
            final var unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(planned);
            throw new IllegalStateException(
                    "Incomplete fixed-point-free generation plan: missing="
                            + missing.size() + ", unexpected="
                            + unexpected.size());
        }
    }

    static Set<String> plannedCheckpoints(final int domainSize) {
        final var planned = new LinkedHashSet<String>();
        for (final var partition : Partitions.generateValidPartitions(domainSize)) {
            for (final var annotated : AnnotatedPartitions.of(partition)) {
                planned.add(annotated.checkpointKey());
            }
        }
        return Set.copyOf(planned);
    }
}

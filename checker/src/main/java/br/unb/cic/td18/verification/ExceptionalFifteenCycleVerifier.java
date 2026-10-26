package br.unb.cic.td18.verification;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.model.ExceptionalFifteenCycle;

import java.util.Map;
import java.util.stream.Collectors;

/** Regenerates restricted interleavings, CPU closures, and remaining budgeted witnesses. */
public final class ExceptionalFifteenCycleVerifier {
    // Independently enumerated by complete companion interleavings in
    // ExceptionalFifteenGenerationTest, before comparing direct generation.
    private static final Map<String, Long> EXPECTED_CLASSES = Map.of(
            "15O-2U-2U@0", 90L, "15O-3U@1", 7L);
    private static final Map<String, Long> EXPECTED_CPU_CLOSURES = Map.of(
            "15O-2U-2U@0", 79L, "15O-3U@1", 7L);

    private ExceptionalFifteenCycleVerifier() {
    }

    public static void verifyInventory() {
        final var obligations = ExceptionalFifteenCycle.obligations();
        final var budgets = obligations.stream().collect(Collectors.toMap(
                ExceptionalFifteenCycle.Obligation::key,
                ExceptionalFifteenCycle.Obligation::budget));
        if (obligations.size() != 2 || !budgets.equals(Map.of(
                "15O-2U-2U@0", 10, "15O-3U@1", 9))
                || obligations.stream().anyMatch(obligation ->
                obligation.domain() != 19 - obligation.movesUsed())) {
            throw new IllegalStateException("Exceptional-fifteen inventory mismatch");
        }
        final var local = ExceptionalFifteenCycle.candidate();
        if (local.formsCyclicTargetPair() || !ExceptionalFifteenCycle.contains(local)) {
            throw new IllegalStateException("Incorrect exceptional-fifteen local candidate");
        }
        final var classification = OrientedFifteenCycleClassification.classify();
        if (classification.visitedNodes() != OrientedFifteenCycleClassification.EXPECTED_VISITED_NODES
                || classification.retainedCycles() != 2 || classification.unorientedCycles() != 1
                || classification.orientedCandidates().size() != 1
                || !classification.orientedCandidates().getFirst().twistedBraceletKey()
                .equals(local.twistedBraceletKey())) {
            throw new IllegalStateException("Exceptional-fifteen classification mismatch: " + classification);
        }
        System.out.printf("exceptional-fifteen inventory verified: domains=19/18 budgets=10/9 "
                        + "localDfsNodes=%d retained=2 unoriented=1 exceptional=1%n",
                classification.visitedNodes());
    }

    public record VerificationResult(long classes, long cpuClosures, long certificates) {
        public VerificationResult {
            if (classes < 0 || cpuClosures < 0 || certificates < 0
                    || classes != cpuClosures + certificates) {
                throw new IllegalArgumentException("Inconsistent exceptional-fifteen verification counts");
            }
        }
    }

    public static VerificationResult verify(final CertificateStore certificates, final String selectedType,
                                             final VerificationProgress progress) {
        var selected = 0;
        long totalClasses = 0;
        long totalCpuClosures = 0;
        long totalCertificates = 0;
        for (final var obligation : ExceptionalFifteenCycle.obligations()) {
            final var key = obligation.key();
            if (selectedType != null && !selectedType.equals(key)) {
                continue;
            }
            selected++;
            final long[] verified = {0};
            final long[] cpuClosures = {0};
            final long[] witnesses = {0};
            final var type = obligation.type();
            progress.phase("exceptional-fifteen verification: " + key
                    + " maximumMoves=" + obligation.budget());
            DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                    type.partition(), type.oriented(), pair -> {
                        if (pair.getBeta().size() != obligation.domain()
                                || !ExceptionalFifteenCycle.contains(pair)) {
                            throw new IllegalStateException("Invalid exceptional-fifteen pair: " + pair);
                        }
                        if (CertificateChecker.cpuClosure(pair, obligation.budget())) {
                            cpuClosures[0]++;
                        } else {
                            certificates.find(pair, obligation.budget())
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Missing exceptional-fifteen witness: type=" + key
                                                    + " maximumMoves=" + obligation.budget()
                                                    + " classKey=" + pair.extendedToricClassKey()));
                            witnesses[0]++;
                        }
                        verified[0]++;
                    });
            if (verified[0] != EXPECTED_CLASSES.get(key)) {
                throw new IllegalStateException("Exceptional-fifteen class count mismatch: type=" + key
                        + " generated=" + verified[0] + " expected=" + EXPECTED_CLASSES.get(key));
            }
            if (cpuClosures[0] != EXPECTED_CPU_CLOSURES.get(key)) {
                throw new IllegalStateException("Exceptional-fifteen CPU-closure count mismatch: type=" + key
                        + " generated=" + cpuClosures[0] + " expected=" + EXPECTED_CPU_CLOSURES.get(key));
            }
            totalClasses += verified[0];
            totalCpuClosures += cpuClosures[0];
            totalCertificates += witnesses[0];
            System.out.printf("exceptional-fifteen type verified: type=%s classes=%d cpuClosures=%d "
                            + "certificates=%d maximumMoves=%d%n",
                    key, verified[0], cpuClosures[0], witnesses[0], obligation.budget());
        }
        if (selected == 0) {
            throw new IllegalArgumentException("Unknown exceptional-fifteen type: " + selectedType);
        }
        return new VerificationResult(totalClasses, totalCpuClosures, totalCertificates);
    }
}

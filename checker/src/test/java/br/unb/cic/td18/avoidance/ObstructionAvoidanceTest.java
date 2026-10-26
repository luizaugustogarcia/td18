package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.permutation.PermutationGroups;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.search.CheckedSearchResult;
import br.unb.cic.td18.search.CommonOperations;
import br.unb.cic.td18.verification.DiskObstructionVerifier;
import br.unb.cic.td18.verification.VerificationProgress;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static br.unb.cic.td18.fixtures.AvoidanceFixtures.controlResidual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstructionAvoidanceTest {
    @Test
    void skipsClosedPredecessorSizesAndDuplicateRestoredLabels() throws Exception {
        final var batch = ObstructionAvoidance.reconstructParents(
                controlResidual(), 8);

        assertEquals(2_176, batch.applications());
        assertEquals(627, batch.parents().size());
        final var digest = MessageDigest.getInstance("SHA-256");
        batch.parents().stream().map(CertificateCodec::keyFor)
                .sorted(Arrays::compareUnsigned).forEach(digest::update);
        // Recorded with the original exhaustive insertion implementation.
        assertEquals("be7cecc20d7136833867f95a048c7c31c6b4334d1eda0d8e04229c17deae67d2",
                HexFormat.of().formatHex(digest.digest()));
        assertTrue(batch.parents().stream().allMatch(pair ->
                pair.getBeta().size() == 18));
        final var rootBatch = batch.parents().stream()
                .map(parent -> ObstructionAvoidance.reconstructParents(parent, 9))
                .filter(candidate -> !candidate.parents().isEmpty())
                .findFirst().orElseThrow();
        assertTrue(rootBatch.parents().stream().allMatch(pair ->
                pair.getBeta().size() == ReductionTypeEnumerator.ROOT_SYMBOLS));
    }

    @Test
    void closesTerminalMissesAtTheIntermediatePrefixScreen() {
        final var residual = new ResidualObligation(
                130, 8, controlResidual());
        final var persistedMisses = new ConcurrentLinkedQueue<>();

        final var result = ObstructionAvoidance.resolve(List.of(residual),
                (pair, rate, maximumMoves, fullDomain) -> {
                    assertFalse(fullDomain);
                    assertEquals(9, maximumMoves);
                    assertTrue(pair.getBeta().size()
                            < ReductionTypeEnumerator.ROOT_SYMBOLS);
                    assertEquals(18, pair.getBeta().size());
                    return CheckedSearchResult.triviallySettled();
                }, persistedMisses::add);

        assertTrue(result.complete());
        assertEquals(1, result.terminalResiduals());
        assertEquals(2_176, result.reconstructionApplications());
        assertEquals(627, result.intermediatePrefixObligations());
        assertEquals(0, result.propagatedPrefixMisses());
        assertEquals(0, result.rootSearchObligations());
        assertEquals(627, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertTrue(persistedMisses.isEmpty());
    }

    @Test
    void propagatesOnlyIntermediatePrefixMissesToTheRootSearch(@TempDir final Path directory) {
        final var residual = new ResidualObligation(
                130, 8, controlResidual());
        final var persistedMisses = new ConcurrentLinkedQueue<>();

        try (final var progress = new VerificationProgress()) {
            final var result = DiskObstructionVerifier.resolve(List.of(residual),
                    (pair, rate, maximumMoves, fullDomain) -> {
                        assertEquals(maximumMoves == 10, fullDomain);
                        if (maximumMoves == 9) {
                            return CheckedSearchResult.residual();
                        }
                        assertEquals(10, maximumMoves);
                        assertEquals(ReductionTypeEnumerator.ROOT_SYMBOLS,
                                pair.getBeta().size());
                        return CheckedSearchResult.triviallySettled();
                    }, persistedMisses::add, directory.resolve("scratch"), progress);

            assertTrue(result.complete());
            assertEquals(1, result.terminalResiduals());
            assertEquals(1_728_934, result.reconstructionApplications());
            assertEquals(627, result.intermediatePrefixObligations());
            assertEquals(627, result.propagatedPrefixMisses());
            assertEquals(623_283, result.rootSearchObligations());
            assertEquals(623_283, result.trivialClosures());
            assertEquals(0, result.certificates());
            assertTrue(persistedMisses.isEmpty());
        }
    }

    @Test
    void diskAndMemoryReconstructionsAgree(@TempDir final Path directory) {
        final var residuals = List.of(new ResidualObligation(130, 8, controlResidual()));
        final ObstructionAvoidance.PairSearch search = (pair, rate, budget, fullDomain) ->
                CheckedSearchResult.triviallySettled();
        final var expected = ObstructionAvoidance.resolve(residuals, search,
                miss -> { throw new AssertionError("Unexpected miss"); });
        try (final var progress = new VerificationProgress()) {
            assertEquals(expected, DiskObstructionVerifier.resolve(residuals, search,
                    miss -> { throw new AssertionError("Unexpected miss"); },
                    directory.resolve("scratch"), progress));
            assertThrows(IllegalStateException.class, () -> DiskObstructionVerifier.resolve(
                    residuals, search, miss -> { }, directory.resolve("scratch"), progress));
        }
    }

    @Test
    void rejectsAResidualAssignedToTheWrongTableRow() {
        final var residual = new ResidualObligation(
                129, 8, controlResidual());

        assertThrows(IllegalArgumentException.class,
                () -> ObstructionAvoidance.resolve(List.of(residual),
                        (pair, rate, moves, fullDomain) ->
                                CheckedSearchResult.triviallySettled(),
                        miss -> { }));
    }

    @Test
    void restorationsMatchAllInsertionHistoriesOnSmallDomains() {
        for (var size = 1; size <= 5; size++) {
            final int[] target = IntStream.range(0, size).toArray();
            // Exercise different current cycles and traversal anchors, with
            // both existing fixed points and nontrivial algebraic cycles.
            for (var shift = 0; shift < size; shift++) {
                for (final int direction : new int[]{1, -1}) {
                    final int[] current = new int[size];
                    for (var index = 0; index < size; index++) {
                        current[index] = Math.floorMod(
                                shift + direction * index, size);
                    }
                    final var beta = Cycle.of(current);
                    final var pair = CyclicTargetPair.of(
                            PermutationGroups.computeProduct(
                                    Cycle.of(target), beta.getInverse()), beta);
                    for (var count = 1; count <= 3; count++) {
                        assertRestorationCoverage(pair, count);
                    }
                }
            }
        }
    }

    private static void assertRestorationCoverage(
            final CyclicTargetPair pair, final int count
    ) {
        final int originalSize = pair.getBeta().size();
        final var expected = new HashSet<String>();
        collectInsertionHistories(pair.getBeta().getSymbols(), originalSize,
                count, expected);
        final var actual = new HashSet<String>();
        final var retained = new ArrayList<CyclicTargetPair>();
        final int[] expectedFixed = IntStream.range(
                originalSize, originalSize + count).toArray();
        ObstructionAvoidance.forEachRestoration(pair, count, restored -> {
            final var candidate = restored.pair();
            assertTrue(actual.add(Arrays.toString(
                    candidate.getBeta().getSymbols())), "Repeated restoration");
            assertTrue(Arrays.equals(expectedFixed, restored.fixedPoints()));
            for (var symbol = 0; symbol < originalSize + count; symbol++) {
                assertEquals(symbol < originalSize
                                ? pair.getOmega().image(symbol) : symbol,
                        candidate.getOmega().image(symbol));
            }
            final int[] contractedTarget = Arrays.stream(
                            candidate.getRho().getSymbols())
                    .filter(symbol -> symbol < originalSize).toArray();
            assertEquals(pair.getRho(), Cycle.of(contractedTarget));
            retained.add(candidate);
        });
        assertEquals(expected, actual);
        // Recheck retained objects after the generator has reused its buffers.
        assertEquals(actual, retained.stream()
                .map(candidate -> Arrays.toString(candidate.getBeta().getSymbols()))
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static void collectInsertionHistories(
            final int[] symbols, final int originalSize, final int remaining,
            final Set<String> result
    ) {
        if (remaining == 0) {
            final int[] normalized = symbols.clone();
            var next = originalSize;
            for (var index = 0; index < normalized.length; index++) {
                if (normalized[index] >= originalSize) {
                    normalized[index] = next++;
                }
            }
            result.add(Arrays.toString(normalized));
            return;
        }
        for (var position = 0; position < symbols.length; position++) {
            final int[] inserted = new int[symbols.length + 1];
            System.arraycopy(symbols, 0, inserted, 0, position + 1);
            inserted[position + 1] = symbols.length;
            System.arraycopy(symbols, position + 1, inserted, position + 2,
                    symbols.length - position - 1);
            collectInsertionHistories(inserted, originalSize,
                    remaining - 1, result);
        }
    }
}

package br.unb.cic.td18.avoidance;

import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.permutation.PermutationGroups;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.search.CommonOperations;
import br.unb.cic.td18.search.WitnessSearch;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstructionAvoidanceTest {
    private static CyclicTargetPair controlResidual() {
        final var omega = new MulticyclePermutation(List.of(
                Cycle.of(0, 11, 13, 12, 2, 4, 9, 5, 7, 3, 14),
                Cycle.of(1, 10, 8, 15, 6)));
        final var beta = Cycle.of(0, 6, 5, 15, 14, 8, 4, 9,
                1, 12, 11, 13, 3, 2, 7, 10);
        for (final var cycle : omega) {
            assertTrue(CommonOperations.isOriented(beta, cycle));
        }
        return CyclicTargetPair.of(omega, beta).canonicalRepresentative();
    }

    @Test
    void skipsClosedPredecessorSizesAndDuplicateRestoredLabels() throws Exception {
        final var batch = ObstructionAvoidance.reconstructParents(
                controlResidual(), 8);

        assertEquals(2_176, batch.applications());
        assertEquals(1_759, batch.parents().size());
        final var digest = MessageDigest.getInstance("SHA-256");
        batch.parents().stream().map(CertificateCodec::keyFor)
                .sorted(Arrays::compareUnsigned).forEach(digest::update);
        // Compared with the independent sequential-insertion reconstruction.
        assertEquals("9fc9908c1a5e106ed7eb135ef7a339bb14dcf157e7141db3e3112b15e2b5456b",
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
                    return WitnessSearch.SearchResult.triviallySettled();
                }, persistedMisses::add);

        assertTrue(result.complete());
        assertEquals(1, result.terminalResiduals());
        assertEquals(2_176, result.reconstructionApplications());
        assertEquals(1_759, result.intermediatePrefixObligations());
        assertEquals(0, result.propagatedPrefixMisses());
        assertEquals(0, result.rootSearchObligations());
        assertEquals(1_759, result.trivialClosures());
        assertEquals(0, result.certificates());
        assertTrue(persistedMisses.isEmpty());
    }

    @Test
    void propagatesOnlyIntermediatePrefixMissesToTheRootSearch(@TempDir final Path directory) {
        final var persistedMisses = new ConcurrentLinkedQueue<>();
        try (final var store = new AvoidancePreimageStore(directory.resolve("scratch"))) {
            store.putPairs(8, List.of(controlResidual()));
            store.putHardPairs(8, List.of(controlResidual()));
            final var first = ObstructionAvoidanceRunner.reconstructWave(store, 8, 1);
            assertEquals(2_176, first.applications());
            assertEquals(1_759, store.inventory(9).count());
            final var screened = ObstructionAvoidanceRunner.searchWave(store, 9, 1_759,
                    false, persistedMisses::add, (pair, rate, moves, fullDomain) -> {
                        assertFalse(fullDomain);
                        assertEquals(9, moves);
                        return WitnessSearch.SearchResult.residual();
                    });
            assertEquals(1_759, screened.residuals());
            final var second = ObstructionAvoidanceRunner.reconstructWave(store, 9, 1_759);
            assertEquals(4_844_286, second.applications());
            assertEquals(1_901_013, store.inventory(10).count());
            final var roots = ObstructionAvoidanceRunner.searchWave(store, 10, 1_901_013,
                    true, persistedMisses::add, (pair, rate, moves, fullDomain) -> {
                        assertTrue(fullDomain);
                        assertEquals(10, moves);
                        assertEquals(ReductionTypeEnumerator.ROOT_SYMBOLS, pair.getBeta().size());
                        return WitnessSearch.SearchResult.triviallySettled();
                    });
            assertEquals(1_901_013, roots.trivialClosures());
            assertEquals(0, roots.certificates());
            assertEquals(0, roots.residuals());
            assertTrue(persistedMisses.isEmpty());
        }
    }

    @Test
    void rejectsAResidualAssignedToTheWrongTableRow() {
        final var residual = new ResidualObligation(
                129, 8, controlResidual());

        assertThrows(IllegalArgumentException.class,
                () -> ObstructionAvoidance.resolve(List.of(residual),
                        (pair, rate, moves, fullDomain) ->
                                WitnessSearch.SearchResult.triviallySettled(),
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

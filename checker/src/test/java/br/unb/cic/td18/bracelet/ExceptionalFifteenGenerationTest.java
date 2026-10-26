package br.unb.cic.td18.bracelet;

import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExceptionalFifteenCycle;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.model.TwistedBraceletKey;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionalFifteenGenerationTest {
    @Test
    void localCandidateAndCompanionObligationsKeepTheirDistinctBudgets() {
        final var local = ExceptionalFifteenCycle.candidate();
        assertFalse(local.formsCyclicTargetPair());
        assertTrue(local.asCyclicTargetPair().isEmpty());
        assertTrue(ExceptionalFifteenCycle.contains(local));
        final var obligations = ExceptionalFifteenCycle.obligations();
        assertEquals(List.of("15O-2U-2U@0", "15O-3U@1"),
                obligations.stream().map(ExceptionalFifteenCycle.Obligation::key).toList());
        assertEquals(List.of(19, 18), obligations.stream()
                .map(ExceptionalFifteenCycle.Obligation::domain).toList());
        assertEquals(List.of(10, 9), obligations.stream()
                .map(ExceptionalFifteenCycle.Obligation::budget).toList());
        assertEquals(List.of(0, 1), obligations.stream()
                .map(ExceptionalFifteenCycle.Obligation::movesUsed).toList());
        final int[] mutable = obligations.getFirst().type().partition();
        mutable[0] = 2;
        assertEquals(15, obligations.getFirst().type().partition()[0]);
        assertThrows(UnsupportedOperationException.class, () -> obligations.removeFirst());
        assertThrows(IllegalArgumentException.class, () -> new ExceptionalFifteenCycle.Obligation(
                obligations.getLast().type(), 18, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new ExceptionalFifteenCycle.Obligation(
                obligations.getLast().type(), 19, 1, 9));
    }

    @Test
    void directRestrictedGenerationMatchesIndependentCompleteInterleavings() {
        compare(new int[]{15, 3}, new boolean[]{true, false}, 27, 7);
        compare(new int[]{15, 3}, new boolean[]{true, true}, 27, 7);
        compare(new int[]{15, 2, 2}, new boolean[]{true, false, false}, 360, 90);
    }

    @Test
    void restrictionPreservesFixedPointDataAndRejectsOtherLocalOrders() {
        final var local = ExceptionalFifteenCycle.candidate();
        final var withFixedPoint = new CyclicTargetCandidate(local.getOmega(),
                Cycle.of(IntStream.range(0, 16).toArray()));
        assertTrue(ExceptionalFifteenCycle.contains(withFixedPoint));
        assertEquals(16, withFixedPoint.getBeta().size());
        final var different = new CyclicTargetCandidate(
                new MulticyclePermutation(Cycle.of(IntStream.range(0, 15).toArray())),
                Cycle.of(IntStream.range(0, 15).toArray()));
        assertFalse(ExceptionalFifteenCycle.contains(different));
        final long[] count = {0};
        DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                new int[]{15}, new boolean[]{true}, pair -> count[0]++);
        assertEquals(0, count[0], "The local candidate alone is not realizable");
    }

    @Test
    void rejectsUnorientedMissingDuplicateFifteenAndFixedPointInputs() {
        for (final var sizes : List.of(new int[]{15}, new int[]{3},
                new int[]{15, 15}, new int[]{15, 1})) {
            final var oriented = new boolean[sizes.length];
            Arrays.fill(oriented, true);
            if (sizes.length == 1 && sizes[0] == 15) {
                oriented[0] = false;
            }
            assertThrows(IllegalArgumentException.class, () ->
                    DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(
                            sizes, oriented, pair -> { }));
        }
    }

    private static void compare(final int[] sizes, final boolean[] oriented,
                                final int expectedCandidateOrbits, final int expectedValidClasses) {
        final var candidates = new HashMap<TwistedBraceletKey, CyclicTargetCandidate>();
        final int domain = Arrays.stream(sizes).sum();
        final var colors = new int[domain];
        final var remaining = sizes.clone();
        remaining[0]--;
        interleave(1, colors, remaining, completed -> decodeCompanions(
                completed, sizes, oriented, 1, new ArrayList<>(), candidate ->
                        candidates.putIfAbsent(candidate.twistedBraceletKey(), candidate)));
        final var expected = new HashSet<ExtendedToricClassKey>();
        candidates.values().forEach(candidate -> candidate.asCyclicTargetPair()
                .ifPresent(pair -> expected.add(pair.extendedToricClassKey())));
        assertFalse(expected.isEmpty(), Arrays.toString(sizes));
        final var actual = new HashSet<ExtendedToricClassKey>();
        final var order = new ArrayList<String>();
        DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(sizes, oriented, pair -> {
            assertTrue(ExceptionalFifteenCycle.contains(pair));
            assertEquals(domain, pair.getRho().size());
            assertTrue(actual.add(pair.extendedToricClassKey()), "Duplicate class");
            assertSame(pair, pair.canonicalRepresentative());
            assertEquals(pair, CyclicTargetPair.fromEncodingWord(pair.twistedBraceletKey().canonicalWord()));
            order.add(pair.extendedToricClassKey().toString());
            final var word = pair.standardizedEncodingWord();
            for (var shift = 0; shift < domain; shift++) {
                assertTrue(ExceptionalFifteenCycle.contains(CyclicTargetCandidate.fromEncodingWord(
                        word.transform(shift, false))));
                assertTrue(ExceptionalFifteenCycle.contains(CyclicTargetCandidate.fromEncodingWord(
                        word.transform(shift, true))));
            }
        });
        assertEquals(expected, actual, Arrays.toString(sizes));
        assertEquals(expectedCandidateOrbits, candidates.size());
        assertEquals(expectedValidClasses, actual.size());
        final var repeated = new ArrayList<String>();
        DirectTwistedBraceletGenerator.generateExceptionalFifteenRepresentatives(sizes, oriented,
                pair -> repeated.add(pair.extendedToricClassKey().toString()));
        assertEquals(order, repeated);
    }

    private static void interleave(final int position, final int[] colors, final int[] remaining,
                                   final Consumer<int[]> consumer) {
        if (position == colors.length) {
            consumer.accept(colors);
            return;
        }
        for (var color = 0; color < remaining.length; color++) {
            if (remaining[color] != 0) {
                colors[position] = color;
                remaining[color]--;
                interleave(position + 1, colors, remaining, consumer);
                remaining[color]++;
            }
        }
    }

    private static void decodeCompanions(final int[] colors, final int[] sizes, final boolean[] oriented,
                                         final int color, final List<Cycle> cycles,
                                         final Consumer<CyclicTargetCandidate> consumer) {
        if (color == 1) {
            final var support = IntStream.range(0, colors.length).filter(p -> colors[p] == 0).toArray();
            final var local = ExceptionalFifteenCycle.candidate().getOmega().iterator().next();
            cycles.add(Cycle.of(Arrays.stream(local.getSymbols()).map(p -> support[p]).toArray()));
        }
        if (color == sizes.length) {
            consumer.accept(new CyclicTargetCandidate(new MulticyclePermutation(cycles),
                    Cycle.of(IntStream.range(0, colors.length).toArray())));
            return;
        }
        final var support = IntStream.range(0, colors.length).filter(p -> colors[p] == color).toArray();
        if (oriented[color]) {
            // Small oriented companions have length three: the increasing order
            // is their unique oriented order with the first support symbol fixed.
            cycles.add(Cycle.of(support));
        } else {
            final var reversed = support.clone();
            for (var index = 1; index < support.length; index++) {
                reversed[index] = support[support.length - index];
            }
            cycles.add(Cycle.of(reversed));
        }
        decodeCompanions(colors, sizes, oriented, color + 1, cycles, consumer);
        cycles.removeLast();
    }
}

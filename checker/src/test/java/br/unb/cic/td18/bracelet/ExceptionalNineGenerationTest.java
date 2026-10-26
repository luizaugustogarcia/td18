package br.unb.cic.td18.bracelet;

import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExceptionalNineCycle;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionalNineGenerationTest {
    @Test
    void localExceptionIsAnUncheckedCandidateAndAllRootAnnotationsAreIncluded() {
        final var local = ExceptionalNineCycle.candidate();
        assertFalse(local.formsCyclicTargetPair());
        assertTrue(local.asCyclicTargetPair().isEmpty());
        assertTrue(ExceptionalNineCycle.contains(local));
        final var types = ExceptionalNineCycle.rootTypes();
        assertEquals(18, types.size());
        assertEquals(18, types.stream().map(ExceptionalNineCycle::typeKey).distinct().count());
        assertEquals(Set.of("[9, 8, 2]", "[9, 7, 3]", "[9, 6, 4]", "[9, 5, 5]",
                "[9, 4, 2, 2, 2]", "[9, 3, 3, 2, 2]"), types.stream()
                .map(type -> Arrays.toString(type.partition())).collect(java.util.stream.Collectors.toSet()));
        final var mutable = types.getFirst().partition();
        mutable[0] = 2;
        assertEquals(9, types.getFirst().partition()[0]);
    }

    @Test
    void directRestrictedGenerationMatchesIndependentSmallInterleavings() {
        compare(new int[]{9, 3}, new boolean[]{true, false});
        compare(new int[]{9, 3}, new boolean[]{true, true});
        compare(new int[]{9, 2, 2}, new boolean[]{true, false, false});
        compare(new int[]{9, 3, 3}, new boolean[]{true, false, true});
    }

    @Test
    void reproducesCheckedTdSixteenRealizableExtensionCounts() {
        // Oriented9CycleExtensionsTest in ../td16 checks these four full domains.
        final var partitions = new int[][]{{9, 6, 2}, {9, 5, 3}, {9, 4, 4}, {9, 2, 2, 2, 2}};
        final var expected = new long[]{527, 2_096, 1_643, 12_732};
        for (var index = 0; index < partitions.length; index++) {
            final var oriented = new boolean[partitions[index].length];
            oriented[0] = true;
            final long[] count = {0};
            DirectTwistedBraceletGenerator.generateExceptionalNineRepresentatives(
                    partitions[index], oriented, pair -> count[0]++);
            assertEquals(expected[index], count[0], Arrays.toString(partitions[index]));
        }
    }

    @Test
    void rejectsUnorientedMissingDuplicateNineAndFixedPointInputs() {
        for (final var sizes : List.of(new int[]{9}, new int[]{3}, new int[]{9, 9}, new int[]{9, 1})) {
            final var oriented = new boolean[sizes.length];
            Arrays.fill(oriented, true);
            if (sizes.length == 1 && sizes[0] == 9) {
                oriented[0] = false;
            }
            assertThrows(IllegalArgumentException.class, () ->
                    DirectTwistedBraceletGenerator.generateExceptionalNineRepresentatives(sizes, oriented, pair -> { }));
        }
    }

    private static void compare(final int[] sizes, final boolean[] oriented) {
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
        DirectTwistedBraceletGenerator.generateExceptionalNineRepresentatives(sizes, oriented, pair -> {
            assertTrue(ExceptionalNineCycle.contains(pair));
            assertEquals(domain, pair.getRho().size());
            assertTrue(actual.add(pair.extendedToricClassKey()), "Duplicate class");
            assertSame(pair, pair.canonicalRepresentative());
            assertEquals(pair, CyclicTargetPair.fromEncodingWord(pair.twistedBraceletKey().canonicalWord()));
            order.add(pair.extendedToricClassKey().toString());
            final var word = pair.standardizedEncodingWord();
            for (var shift = 0; shift < domain; shift++) {
                assertTrue(ExceptionalNineCycle.contains(CyclicTargetCandidate.fromEncodingWord(
                        word.transform(shift, false))));
                assertTrue(ExceptionalNineCycle.contains(CyclicTargetCandidate.fromEncodingWord(
                        word.transform(shift, true))));
            }
        });
        assertEquals(expected, actual, Arrays.toString(sizes));
        final var repeated = new ArrayList<String>();
        DirectTwistedBraceletGenerator.generateExceptionalNineRepresentatives(sizes, oriented,
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
            final var local = ExceptionalNineCycle.candidate().getOmega().iterator().next();
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

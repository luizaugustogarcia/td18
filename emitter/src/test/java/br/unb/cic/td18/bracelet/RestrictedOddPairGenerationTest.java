package br.unb.cic.td18.bracelet;

import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.model.TwistedBraceletKey;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.permutation.PermutationGroups;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RestrictedOddPairGenerationTest {
    private static final int DOMAIN = 18;
    private static final int FULL = (1 << DOMAIN) - 1;
    private static final Cycle BETA = Cycle.of(IntStream.range(0, DOMAIN).toArray());
    private static final boolean[] ORIENTED = {true, true};

    private record Family(int length, int firstExponent, int secondExponent,
                          int candidateOrbits, int classes) { }

    private static final List<Family> FAMILIES = List.of(
            new Family(13, 7, 3, 252, 96),
            new Family(13, 10, 3, 252, 115),
            new Family(13, 11, 3, 252, 180),
            new Family(11, 6, 4, 912, 321),
            new Family(9, 5, 5, 765, 193),
            new Family(9, 5, 7, 1_387, 439),
            new Family(9, 7, 7, 765, 540));

    @Test
    void directPrefixGenerationMatchesEveryIndependentSupportOrbit() {
        var totalCandidates = 0L;
        var totalClasses = 0L;
        for (final int length : new int[]{13, 11, 9}) {
            final int[] partition = {length, DOMAIN - length};
            final var expected = new HashSet<ExtendedToricClassKey>();
            final var allCandidates = new HashSet<TwistedBraceletKey>();
            for (final var family : FAMILIES) {
                if (family.length() == length) {
                    independentSupportOrbits(family, allCandidates, expected);
                }
            }
            final var actual = new HashSet<ExtendedToricClassKey>();
            final var ordered = new ArrayList<String>();
            DirectTwistedBraceletGenerator.generateRestrictedOddPairRepresentatives(
                    partition, ORIENTED, pair -> {
                        assertTrue(RestrictedOddPair.contains(pair));
                        assertEquals(DOMAIN, pair.getRho().size());
                        assertTrue(actual.add(pair.extendedToricClassKey()), "Duplicate restricted class");
                        assertSame(pair, pair.canonicalRepresentative());
                        assertEquals(pair, CyclicTargetPair.fromEncodingWord(
                                pair.twistedBraceletKey().canonicalWord()));
                        ordered.add(pair.extendedToricClassKey().toString());
                        for (var shift = 0; shift < DOMAIN; shift++) {
                            final var word = pair.standardizedEncodingWord();
                            assertTrue(RestrictedOddPair.contains(CyclicTargetCandidate.fromEncodingWord(
                                    word.transform(shift, false))));
                            assertTrue(RestrictedOddPair.contains(CyclicTargetCandidate.fromEncodingWord(
                                    word.transform(shift, true))));
                        }
                    });
            assertEquals(expected, actual, Arrays.toString(partition));
            assertEquals(RestrictedOddPair.expectedClasses(partition, ORIENTED), actual.size());
            final long[] directCandidates = {0};
            DirectTwistedBraceletGenerator.generateRestrictedOddPairWords(partition, ORIENTED,
                    word -> directCandidates[0]++);
            assertEquals(allCandidates.size(), directCandidates[0]);
            assertEquals(RestrictedOddPair.expectedCandidateOrbits(partition, ORIENTED), directCandidates[0]);
            final var repeated = new ArrayList<String>();
            DirectTwistedBraceletGenerator.generateRestrictedOddPairRepresentatives(
                    new int[]{DOMAIN - length, length}, ORIENTED,
                    pair -> repeated.add(pair.extendedToricClassKey().toString()));
            assertEquals(ordered, repeated, "Normalized input order must not change persistence order");
            totalCandidates += directCandidates[0];
            totalClasses += actual.size();
        }
        assertEquals(4_585L, totalCandidates);
        assertEquals(1_884L, totalClasses);
    }

    @Test
    void eachAllowedLocalPowerHasNoQualifyingTranspositionByDirectMultiplication() {
        for (final int length : new int[]{5, 7, 9, 11, 13}) {
            for (final int rankStep : RestrictedOddPair.allowedRankSteps(length)) {
                var exponent = 1;
                while (rankStep * exponent % length != 1) {
                    exponent++;
                }
                final var cycle = cycle((1 << length) - 1, exponent);
                final var omega = new MulticyclePermutation(cycle);
                for (var a = 0; a < length - 2; a++) {
                    for (var b = a + 1; b < length - 1; b++) {
                        for (var c = b + 1; c < length; c++) {
                            final var move = Cycle.of(a, b, c);
                            final var child = PermutationGroups.computeProduct(
                                    true, length, omega, move.getInverse());
                            final boolean createsFixedPoint = child.stream().anyMatch(part -> part.size() == 1);
                            assertFalse(createsFixedPoint && child.get3Norm() == omega.get3Norm() - 1,
                                    () -> "Qualifying transposition in local power " + cycle);
                        }
                    }
                }
            }
        }
    }

    @Test
    void recognitionFailsClosedAndPreservesTheFixedPointPrecondition() {
        assertFalse(RestrictedOddPair.isType(null, ORIENTED));
        assertFalse(RestrictedOddPair.isType(new int[]{13, 5}, null));
        assertFalse(RestrictedOddPair.isType(new int[]{13, 5}, new boolean[]{true}));
        assertFalse(RestrictedOddPair.isType(new int[]{13, 5}, new boolean[]{true, false}));
        assertFalse(RestrictedOddPair.isType(new int[]{13, 3}, ORIENTED));
        assertTrue(RestrictedOddPair.isObligation(new int[]{5, 13}, ORIENTED, 1));
        assertFalse(RestrictedOddPair.isObligation(new int[]{13, 5}, ORIENTED, 0));
        assertFalse(RestrictedOddPair.isObligation(new int[]{13, 5}, ORIENTED, 2));
        assertFalse(RestrictedOddPair.contains(null));
        for (final int[] sizes : List.of(new int[]{13, 5, 1}, new int[]{13, 3}, new int[]{18})) {
            final var orientations = new boolean[sizes.length];
            Arrays.fill(orientations, true);
            assertThrows(IllegalArgumentException.class, () ->
                    DirectTwistedBraceletGenerator.generateRestrictedOddPairRepresentatives(
                            sizes, orientations, pair -> fail("Invalid domain was emitted")));
            assertThrows(IllegalArgumentException.class, () -> RestrictedOddPair.expectedClasses(sizes, orientations));
            assertThrows(IllegalArgumentException.class, () -> RestrictedOddPair.expectedCandidateOrbits(sizes, orientations));
        }
        final var localPair = new CyclicTargetCandidate(
                new MulticyclePermutation(List.of(cycle((1 << 13) - 1, 7), cycle(FULL ^ ((1 << 13) - 1), 3))), BETA);
        assertTrue(RestrictedOddPair.contains(localPair));
        assertFalse(RestrictedOddPair.contains(new CyclicTargetCandidate(localPair.getOmega(),
                Cycle.of(IntStream.range(0, 19).toArray()))));
        final var alteredCompanion = new CyclicTargetCandidate(
                new MulticyclePermutation(List.of(cycle((1 << 13) - 1, 7), cycle(FULL ^ ((1 << 13) - 1), 1))), BETA);
        assertFalse(RestrictedOddPair.contains(alteredCompanion), "Both local cycles must fail");
    }

    /** Enumerates support subsets, quotients their ordinary bit-mask dihedral action,
     * then independently decodes powers and validates target realizability. */
    private static void independentSupportOrbits(final Family family,
            final Set<TwistedBraceletKey> allCandidates,
            final Set<ExtendedToricClassKey> allClasses) {
        var candidates = 0;
        var classes = 0;
        final boolean interchangeable = family.length() == 9
                && family.firstExponent() == family.secondExponent();
        for (var mask = 0; mask <= FULL; mask++) {
            if (Integer.bitCount(mask) != family.length() || !isLeastSupport(mask, interchangeable)) {
                continue;
            }
            final var candidate = new CyclicTargetCandidate(new MulticyclePermutation(List.of(
                    cycle(mask, family.firstExponent()), cycle(FULL ^ mask, family.secondExponent()))), BETA);
            assertTrue(RestrictedOddPair.contains(candidate));
            assertTrue(allCandidates.add(candidate.twistedBraceletKey()), "Independent family overlap");
            candidates++;
            final var pair = candidate.asCyclicTargetPair();
            if (pair.isPresent()) {
                assertTrue(allClasses.add(pair.orElseThrow().extendedToricClassKey()));
                classes++;
            }
        }
        assertEquals(family.candidateOrbits(), candidates, family.toString());
        assertEquals(family.classes(), classes, family.toString());
    }

    private static boolean isLeastSupport(final int mask, final boolean interchangeable) {
        var rotated = mask;
        var reflected = Integer.reverse(mask) >>> (32 - DOMAIN);
        for (var shift = 0; shift < DOMAIN; shift++) {
            if (rotated < mask || reflected < mask
                    || interchangeable && ((FULL ^ rotated) < mask || (FULL ^ reflected) < mask)) {
                return false;
            }
            rotated = ((rotated << 1) & FULL) | (rotated >>> (DOMAIN - 1));
            reflected = ((reflected << 1) & FULL) | (reflected >>> (DOMAIN - 1));
        }
        return true;
    }

    private static Cycle cycle(final int mask, final int exponent) {
        final int[] support = IntStream.range(0, DOMAIN)
                .filter(symbol -> (mask & (1 << symbol)) != 0).toArray();
        final int[] symbols = new int[support.length];
        for (var index = 0; index < symbols.length; index++) {
            symbols[index] = support[index * exponent % symbols.length];
        }
        return Cycle.of(symbols);
    }
}

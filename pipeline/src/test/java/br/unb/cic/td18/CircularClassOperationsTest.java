package br.unb.cic.td18;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.base.StandardizedEncodingWord;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import br.unb.cic.tdp.permutation.PermutationGroups;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static br.unb.cic.td18.CircularClassOperations.algebraicFixedPointCount;
import static br.unb.cic.td18.CircularClassOperations.childClasses;
import static br.unb.cic.td18.CircularClassOperations.fixedPointInsertions;
import static br.unb.cic.td18.CircularClassOperations.insertions;
import static br.unb.cic.td18.CircularClassOperations.removalClassMultiset;
import static br.unb.cic.td18.CircularClassOperations.removeSymbol;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularClassOperationsTest {

    @Test
    void insertionAndDeletionAreInverseBeforeCanonicalization() {
        final var parent = threeSymbolPair();
        final var insertions = insertions(parent);

        assertEquals(9, insertions.size());
        for (final var insertion : insertions) {
            assertEquals(parent, removeSymbol(insertion.child(), 3));
        }
    }

    @Test
    void oneInsertionPerParentSymbolCreatesTheNewFixedPoint() {
        final var parent = threeSymbolPair();

        final var fixedPointChildren = insertions(parent).stream()
                .filter(insertion -> insertion.child().getOmega().image(3) == 3)
                .toList();

        assertEquals(3, fixedPointChildren.size());
        assertTrue(fixedPointChildren.stream()
                .allMatch(insertion -> algebraicFixedPointCount(
                        insertion.child()) == 1));
    }

    @Test
    void directFixedPointInsertionsEqualFilteredQuadraticInsertions() {
        for (final var parent : List.of(threeSymbolPair(), eightSymbolPair())) {
            final var newSymbol = parent.getBeta().size();
            final var expected = insertions(parent).stream()
                    .map(CircularClassOperations.Insertion::child)
                    .filter(child -> child.getOmega().image(newSymbol)
                            == newSymbol)
                    .collect(java.util.stream.Collectors.toSet());
            final var actual = fixedPointInsertions(parent).stream()
                    .map(CircularClassOperations.Insertion::child)
                    .collect(java.util.stream.Collectors.toSet());

            assertEquals(parent.getBeta().size(), actual.size());
            assertEquals(expected, actual);
            actual.forEach(child -> {
                assertEquals(newSymbol, child.getOmega().image(newSymbol));
                assertEquals(parent, removeSymbol(child, newSymbol));
            });
        }
    }

    @Test
    void removalClassMultisetIsInvariantAcrossTheDihedralOrbit() {
        final var representative = eightSymbolPair();
        final var expected = removalClassMultiset(representative);

        representative.dihedralOrbitWords()
                .map(orbitWord -> CyclicTargetPair.fromEncodingWord(orbitWord.word()))
                .forEach(equivalent -> assertEquals(
                        expected, removalClassMultiset(equivalent)));
    }

    @Test
    void insertionClassSetIsInvariantAcrossTheDihedralOrbit() {
        final var representative = eightSymbolPair();
        final var expected = childKeys(representative);

        representative.dihedralOrbitWords()
                .map(orbitWord -> CyclicTargetPair.fromEncodingWord(orbitWord.word()))
                .forEach(equivalent -> assertEquals(
                        expected, childKeys(equivalent)));
    }

    @Test
    void fixedPointChildrenCanBeCanonicalizedAndContracted() {
        final var parent = threeSymbolPair();

        insertions(parent).stream()
                .map(CircularClassOperations.Insertion::child)
                .filter(child -> child.getOmega().image(3) == 3)
                .forEach(child -> {
                    final var canonical = child.canonicalRepresentative();
                    assertEquals(child.extendedToricClassKey(),
                            canonical.extendedToricClassKey());
                    assertEquals(parent, removeSymbol(child, 3));
                });
    }

    @Test
    void representativeInsertionsCoverDirectEnumerationAtSmallOrder() {
        final Map<ExtendedToricClassKey, CyclicTargetPair> parentClasses =
                new LinkedHashMap<>();
        ordinaryPairs(3).forEach(pair -> parentClasses.putIfAbsent(
                pair.extendedToricClassKey(), pair));

        final Set<ExtendedToricClassKey> generatedChildren = new HashSet<>();
        parentClasses.values().forEach(parent -> childClasses(parent)
                .forEach(child -> generatedChildren.add(
                        child.extendedToricClassKey())));

        final Set<ExtendedToricClassKey> directlyEnumerated = new HashSet<>();
        ordinaryPairs(4).forEach(pair -> directlyEnumerated.add(
                pair.extendedToricClassKey()));

        assertEquals(directlyEnumerated, generatedChildren);
    }

    private static Set<ExtendedToricClassKey> childKeys(
            final CyclicTargetPair pair
    ) {
        final var keys = new HashSet<ExtendedToricClassKey>();
        childClasses(pair).forEach(child -> keys.add(child.extendedToricClassKey()));
        return keys;
    }

    private static CyclicTargetPair threeSymbolPair() {
        return CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
    }

    private static CyclicTargetPair eightSymbolPair() {
        final var word = StandardizedEncodingWord.of(
                new int[]{1, 2, 1, 2, 2, 1, 2, 2},
                new int[]{0, 1, 0, 3, 5, 0, 2, 4});
        return CyclicTargetPair.fromEncodingWord(word);
    }

    private static List<CyclicTargetPair> ordinaryPairs(final int order) {
        final var values = new int[order];
        for (var index = 0; index < order; index++) {
            values[index] = index + 1;
        }
        final var result = new ArrayList<CyclicTargetPair>();
        permute(values, 0, result);
        return result;
    }

    private static void permute(
            final int[] values,
            final int index,
            final List<CyclicTargetPair> result
    ) {
        if (index == values.length) {
            final var betaSymbols = new int[values.length + 1];
            System.arraycopy(values, 0, betaSymbols, 1, values.length);
            final var rhoSymbols = new int[betaSymbols.length];
            Arrays.setAll(rhoSymbols, symbol -> symbol);
            final var beta = Cycle.of(betaSymbols);
            final var rho = Cycle.of(rhoSymbols);
            final var omega = PermutationGroups.computeProduct(
                    true, beta.size(), rho, beta.getInverse());
            result.add(CyclicTargetPair.ofKnownTargetOwned(omega, beta, rho));
            return;
        }
        for (var candidate = index; candidate < values.length; candidate++) {
            swap(values, index, candidate);
            permute(values, index + 1, result);
            swap(values, index, candidate);
        }
    }

    private static void swap(final int[] values, final int left, final int right) {
        final var temporary = values[left];
        values[left] = values[right];
        values[right] = temporary;
    }
}

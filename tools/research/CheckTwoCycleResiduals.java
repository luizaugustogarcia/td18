import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.model.CyclicTargetCandidate;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.ExtendedToricClassKey;
import br.unb.cic.td18.model.TwistedBraceletKey;
import br.unb.cic.td18.permutation.Cycle;
import br.unb.cic.td18.permutation.MulticyclePermutation;
import br.unb.cic.td18.search.WitnessSearch;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Research cross-check against the repository's actual bracelet and class keys.
 * See docs/two-cycle-residual-route.md for the argument and runnable commands.
 * Independently enumerates support subsets and compares the production class stream.
 * This audit does not emit sorting certificates or execute root searches.
 */
public final class CheckTwoCycleResiduals {
    private static final int DOMAIN = 18;
    private static final int FULL = (1 << DOMAIN) - 1;
    private static final Cycle BETA = Cycle.of(IntStream.range(0, DOMAIN).toArray());

    private record Family(int row, int length, int exponentA, int exponentB,
                          int candidates, int classes) {}

    private static final List<Family> FAMILIES = List.of(
            new Family(267, 13, 7, 3, 252, 96),
            new Family(267, 13, 10, 3, 252, 115),
            new Family(267, 13, 11, 3, 252, 180),
            new Family(270, 11, 6, 4, 912, 321),
            new Family(274, 9, 5, 5, 765, 193),
            new Family(274, 9, 5, 7, 1387, 439),
            new Family(274, 9, 7, 7, 765, 540));

    private CheckTwoCycleResiduals() {}

    private static Cycle cycle(final int mask, final int exponent) {
        final int[] support = IntStream.range(0, DOMAIN)
                .filter(symbol -> (mask & (1 << symbol)) != 0).toArray();
        final int[] symbols = new int[support.length];
        for (var index = 0; index < symbols.length; index++) {
            symbols[index] = support[index * exponent % symbols.length];
        }
        return Cycle.of(symbols);
    }

    public static void main(final String[] args) {
        if (args.length > 1 || args.length == 1 && !args[0].equals("--prefix")) {
            throw new IllegalArgumentException("Expected --prefix or no arguments");
        }
        final boolean checkPrefix = args.length == 1;
        final var allCandidates = new HashSet<TwistedBraceletKey>();
        final var allClasses = new HashSet<ExtendedToricClassKey>();
        final var classesByRow = new TreeMap<Integer, HashSet<ExtendedToricClassKey>>();
        var totalRaw = 0;
        var totalClosures = 0;
        System.out.println("{\"format\":\"td18-two-cycle-java-audit-v2\",\"families\":[");
        for (var index = 0; index < FAMILIES.size(); index++) {
            final var family = FAMILIES.get(index);
            final var candidates = new HashSet<TwistedBraceletKey>();
            final var pairs = new TreeMap<ExtendedToricClassKey, CyclicTargetPair>(
                    Comparator.comparing(ExtendedToricClassKey::twistedBracelet));
            var raw = 0;
            for (var mask = 0; mask <= FULL; mask++) {
                if (Integer.bitCount(mask) != family.length()
                        || family.length() * 2 == DOMAIN
                        && family.exponentA() == family.exponentB()
                        && mask > (FULL ^ mask)) {
                    continue;
                }
                final var omega = new MulticyclePermutation();
                omega.add(cycle(mask, family.exponentA()));
                omega.add(cycle(FULL ^ mask, family.exponentB()));
                final var candidate = new CyclicTargetCandidate(omega, BETA);
                raw++;
                candidates.add(candidate.twistedBraceletKey());
                candidate.asCyclicTargetPair().ifPresent(pair -> pairs.putIfAbsent(
                        pair.extendedToricClassKey(), pair.canonicalRepresentative()));
            }
            if (candidates.size() != family.candidates() || pairs.size() != family.classes()) {
                throw new IllegalStateException("Independent count mismatch: " + family);
            }
            if (!candidates.stream().allMatch(allCandidates::add)
                    || !pairs.keySet().stream().allMatch(allClasses::add)) {
                throw new IllegalStateException("Local families have overlapping orbits");
            }
            classesByRow.computeIfAbsent(family.row(), ignored -> new HashSet<>()).addAll(pairs.keySet());
            var closures = 0;
            if (checkPrefix) {
                for (final var pair : pairs.values()) {
                    if (WitnessSearch.closesByCpuPrefix(pair, 9)) {
                        closures++;
                    }
                }
            }
            totalRaw += raw;
            totalClosures += closures;
            System.out.printf("{\"row\":%d,\"lengths\":[%d,%d],\"exponents\":[%d,%d],"
                            + "\"raw_candidates\":%d,\"candidate_orbits\":%d,\"valid_pair_classes\":%d,"
                            + "\"cpu_prefix_closures\":%s}%s%n",
                    family.row(), family.length(), DOMAIN - family.length(),
                    family.exponentA(), family.exponentB(), raw, candidates.size(), pairs.size(),
                    checkPrefix ? Integer.toString(closures) : "null",
                    index + 1 < FAMILIES.size() ? "," : "");
        }
        if (totalRaw != 154_768 || allCandidates.size() != 4_585 || allClasses.size() != 1_884) {
            throw new IllegalStateException("Incomplete combined family inventory");
        }
        for (final var entry : classesByRow.entrySet()) {
            final int firstLength = FAMILIES.stream().filter(f -> f.row() == entry.getKey())
                    .findFirst().orElseThrow().length();
            final var generated = new HashSet<ExtendedToricClassKey>();
            DirectTwistedBraceletGenerator.generateRestrictedOddPairRepresentatives(
                    new int[]{firstLength, DOMAIN - firstLength}, new boolean[]{true, true}, pair -> {
                        if (!generated.add(pair.extendedToricClassKey())) {
                            throw new IllegalStateException("Repeated generated restricted class");
                        }
                    });
            if (!generated.equals(entry.getValue())) {
                throw new IllegalStateException("Production/support-subset class sets differ at row " + entry.getKey());
            }
        }
        System.out.printf("],\"raw_candidates\":%d,\"candidate_orbits\":%d,"
                        + "\"valid_pair_classes\":%d,\"production_class_sets_agree\":true,\"cpu_prefix_closures\":%s}%n",
                totalRaw, allCandidates.size(), allClasses.size(),
                checkPrefix ? Integer.toString(totalClosures) : "null");
    }
}

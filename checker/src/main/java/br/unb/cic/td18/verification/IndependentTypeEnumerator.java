package br.unb.cic.td18.verification;

import br.unb.cic.td18.reduction.ReductionTypeEnumerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checker-side reconstruction of the two-reduction inventory.
 *
 * <p>This implementation deliberately uses a work list and its own partition,
 * annotation, closure, reduction, and ordering code. It does not call the
 * emitter's recursive inventory enumerator.</p>
 */
final class IndependentTypeEnumerator {
    static final int ROOT_SYMBOLS = 19;
    static final int MAXIMUM_MOVES = 10;
    private static final int DEPTH_LIMIT = 2;
    private static final int[] TD = {
            0, 0, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 9, 10
    };

    private IndependentTypeEnumerator() {
    }

    record Inventory(List<Entry> entries, int rootPartitions,
                     int annotatedRoots) {
        Inventory {
            entries = List.copyOf(entries);
        }
    }

    record Entry(int[] lengths, boolean[] oriented, int movesUsed) {
        Entry {
            lengths = lengths.clone();
            oriented = oriented.clone();
            if (lengths.length == 0 || lengths.length != oriented.length
                    || movesUsed < 0 || movesUsed > DEPTH_LIMIT) {
                throw new IllegalArgumentException("Malformed inventory entry");
            }
        }

        @Override
        public int[] lengths() {
            return lengths.clone();
        }

        @Override
        public boolean[] oriented() {
            return oriented.clone();
        }

        int nonfixedSymbols() {
            return Arrays.stream(lengths).sum();
        }

        int fixedSymbols() {
            return ROOT_SYMBOLS - nonfixedSymbols();
        }

        int budget() {
            return MAXIMUM_MOVES - movesUsed;
        }

        int threeNorm() {
            return Arrays.stream(lengths).map(length -> length / 2).sum();
        }

        String type() {
            final var value = new StringBuilder("(");
            for (var index = 0; index < lengths.length; index++) {
                if (index > 0) {
                    value.append(',');
                }
                value.append(lengths[index]).append('-')
                        .append(oriented[index] ? 'O' : 'U');
            }
            return value.append(')').toString();
        }
    }

    private record State(int[] lengths, boolean[] oriented, int movesUsed) {
        private State {
            lengths = lengths.clone();
            oriented = oriented.clone();
        }
    }

    /** Checks the full reachable policy, including branches merged in Table 2. */
    static int verifyReductionPolicy() {
        for (var symbols = 0; symbols <= ROOT_SYMBOLS; symbols++) {
            final int diameter = symbols <= 1 ? 0
                    : symbols == ROOT_SYMBOLS ? Integer.MAX_VALUE : TD[symbols - 1];
            if (ReductionTypeEnumerator.knownDiameter(symbols) != diameter) {
                throw new IllegalStateException("Known diameter mismatch at domain " + symbols);
            }
        }
        final var pending = new ArrayDeque<State>();
        for (final var partition : partitionsOf(ROOT_SYMBOLS)) {
            for (final var annotation : annotationsOf(partition)) {
                pending.add(new State(partition, annotation, 0));
            }
        }
        final var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            final var state = pending.removeFirst();
            final var key = typeKey(state.lengths(), state.oriented()) + '@' + state.movesUsed();
            if (!visited.add(key)) {
                continue;
            }
            final boolean open = !closes(state);
            if (ReductionTypeEnumerator.isReachableOpenState(
                    state.lengths(), state.oriented(), state.movesUsed()) != open) {
                throw new IllegalStateException("Reduction reachability mismatch: " + key);
            }
            if (!open) {
                continue;
            }
            final var children = childrenOf(state.lengths(), state.oriented());
            final var checkedChildren = ReductionTypeEnumerator.selectedChildPartitions(
                    state.lengths(), state.oriented());
            if (!children.stream().map(Arrays::toString).sorted().toList().equals(
                    checkedChildren.stream().map(Arrays::toString).sorted().toList())) {
                throw new IllegalStateException("Reduction child mismatch: " + key);
            }
            String kind = "NONE";
            for (var index = 0; index < state.lengths().length; index++) {
                final int length = state.lengths()[index];
                if (length == 3 && state.oriented()[index]) {
                    kind = "ORIENTED_THREE";
                    break;
                }
                if (length >= 5 && length % 2 == 1 && state.oriented()[index]) {
                    kind = "ORIENTED_ODD";
                } else if (length % 2 == 0 && kind.equals("NONE")) {
                    kind = "EVEN_PAIR";
                }
            }
            if (!ReductionTypeEnumerator.selectedReductionKind(
                    state.lengths(), state.oriented()).name().equals(kind)) {
                throw new IllegalStateException("Reduction priority mismatch: " + key);
            }
            if (state.movesUsed() == DEPTH_LIMIT) {
                continue;
            }
            for (final var child : children) {
                final int parentNorm = Arrays.stream(state.lengths()).map(n -> n / 2).sum();
                if (Arrays.stream(child).map(n -> n / 2).sum() != parentNorm - 1) {
                    throw new IllegalStateException("Reduction does not lower the 3-norm: " + key);
                }
                for (final var annotation : annotationsOf(child)) {
                    pending.addLast(new State(child, annotation, state.movesUsed() + 1));
                }
            }
        }
        return visited.size();
    }

    static Inventory enumerate() {
        final var roots = partitionsOf(ROOT_SYMBOLS);
        final var pending = new ArrayDeque<State>();
        var annotatedRootCount = 0;
        for (final var partition : roots) {
            final var annotations = annotationsOf(partition);
            annotatedRootCount += annotations.size();
            for (final var annotation : annotations) {
                pending.addLast(new State(partition, annotation, 0));
            }
        }

        final Set<String> expanded = new HashSet<>();
        final Map<String, Entry> terminalByType = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            final var state = pending.removeFirst();
            final var stateKey = typeKey(state.lengths(), state.oriented())
                    + '@' + state.movesUsed();
            if (!expanded.add(stateKey) || closes(state)) {
                continue;
            }

            final var children = childrenOf(state.lengths(), state.oriented());
            if (state.movesUsed() == DEPTH_LIMIT || children.isEmpty()) {
                final var entry = new Entry(state.lengths(), state.oriented(),
                        state.movesUsed());
                terminalByType.merge(typeKey(entry.lengths(), entry.oriented()),
                        entry, (first, second) -> first.movesUsed() >= second.movesUsed()
                                ? first : second);
                continue;
            }

            for (final var child : children) {
                if (child.length == 0) {
                    continue;
                }
                for (final var annotation : annotationsOf(child)) {
                    pending.addLast(new State(child, annotation,
                            state.movesUsed() + 1));
                }
            }
        }

        final var entries = new ArrayList<>(terminalByType.values());
        entries.sort(tableOrder());
        return new Inventory(entries, roots.size(), annotatedRootCount);
    }

    private static boolean closes(final State state) {
        final int symbols = Arrays.stream(state.lengths()).sum();
        if (symbols >= ROOT_SYMBOLS) {
            return false;
        }
        final int diameter = symbols <= 1 ? 0 : TD[symbols - 1];
        return state.movesUsed() + diameter <= MAXIMUM_MOVES;
    }

    private static List<int[]> partitionsOf(final int total) {
        final var result = new ArrayList<int[]>();
        enumeratePartitions(total, total, new int[total / 2], 0, 0, result);
        return List.copyOf(result);
    }

    private static void enumeratePartitions(final int remaining,
                                            final int maximumPart,
                                            final int[] buffer,
                                            final int size,
                                            final int evenParts,
                                            final List<int[]> result) {
        if (remaining == 0) {
            if (evenParts % 2 == 0) {
                result.add(Arrays.copyOf(buffer, size));
            }
            return;
        }
        for (var part = Math.min(maximumPart, remaining); part >= 2; part--) {
            buffer[size] = part;
            enumeratePartitions(remaining - part, part, buffer, size + 1,
                    evenParts + (part % 2 == 0 ? 1 : 0), result);
        }
    }

    private static List<boolean[]> annotationsOf(final int[] partition) {
        final var result = new ArrayList<boolean[]>();
        annotateRuns(partition, new boolean[partition.length], 0, result);
        return result;
    }

    private static void annotateRuns(final int[] partition,
                                     final boolean[] annotation,
                                     final int start,
                                     final List<boolean[]> result) {
        if (start == partition.length) {
            result.add(annotation.clone());
            return;
        }
        var end = start + 1;
        while (end < partition.length && partition[end] == partition[start]) {
            end++;
        }
        final int maximumOriented = partition[start] == 2 ? 0 : end - start;
        for (var count = 0; count <= maximumOriented; count++) {
            Arrays.fill(annotation, start, end, false);
            Arrays.fill(annotation, end - count, end, true);
            annotateRuns(partition, annotation, end, result);
        }
    }

    private static List<int[]> childrenOf(final int[] lengths,
                                          final boolean[] oriented) {
        for (var index = 0; index < lengths.length; index++) {
            if (lengths[index] == 3 && oriented[index]) {
                return List.of(remove(lengths, index));
            }
        }

        var selectedLength = Integer.MAX_VALUE;
        var selectedIndex = -1;
        for (var index = 0; index < lengths.length; index++) {
            if (oriented[index] && lengths[index] >= 5
                    && lengths[index] % 2 == 1
                    && lengths[index] < selectedLength) {
                selectedLength = lengths[index];
                selectedIndex = index;
            }
        }
        if (selectedIndex >= 0) {
            final var others = remove(lengths, selectedIndex);
            final var result = new ArrayList<int[]>();
            result.add(sortedWith(others, selectedLength - 2));
            for (var first = 3; 2 * first <= selectedLength - 1; first += 2) {
                final int second = selectedLength - 1 - first;
                if (second >= 3 && second % 2 == 1) {
                    result.add(sortedWith(others, first, second));
                }
            }
            return List.copyOf(result);
        }

        var firstEven = -1;
        var secondEven = -1;
        for (var index = 0; index < lengths.length; index++) {
            if (lengths[index] % 2 == 0) {
                if (firstEven < 0) {
                    firstEven = index;
                } else {
                    secondEven = index;
                    break;
                }
            }
        }
        if (secondEven >= 0) {
            final int joined = lengths[firstEven] + lengths[secondEven] - 1;
            final var survivors = new int[lengths.length - 2];
            for (int source = 0, target = 0; source < lengths.length; source++) {
                if (source != firstEven && source != secondEven) {
                    survivors[target++] = lengths[source];
                }
            }
            return List.of(sortedWith(survivors, joined));
        }
        return List.of();
    }

    private static int[] remove(final int[] values, final int removed) {
        final var result = new int[values.length - 1];
        for (int source = 0, target = 0; source < values.length; source++) {
            if (source != removed) {
                result[target++] = values[source];
            }
        }
        return result;
    }

    private static int[] sortedWith(final int[] values, final int... added) {
        final var result = Arrays.copyOf(values, values.length + added.length);
        System.arraycopy(added, 0, result, values.length, added.length);
        Arrays.sort(result);
        for (int left = 0, right = result.length - 1; left < right;
             left++, right--) {
            final int temporary = result[left];
            result[left] = result[right];
            result[right] = temporary;
        }
        return result;
    }

    private static String typeKey(final int[] lengths,
                                  final boolean[] oriented) {
        final var value = new StringBuilder();
        for (var index = 0; index < lengths.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(lengths[index]).append(oriented[index] ? 'O' : 'U');
        }
        return value.toString();
    }

    private static Comparator<Entry> tableOrder() {
        return Comparator.comparingInt(Entry::movesUsed)
                .thenComparingInt(Entry::threeNorm)
                .thenComparing(Comparator.comparingInt(
                        Entry::nonfixedSymbols).reversed())
                .thenComparing(IndependentTypeEnumerator::compareLengths)
                .thenComparing(IndependentTypeEnumerator::compareOrientations);
    }

    private static int compareLengths(final Entry first, final Entry second) {
        final int[] left = first.lengths();
        final int[] right = second.lengths();
        for (var index = 0; index < Math.min(left.length, right.length); index++) {
            if (left[index] != right[index]) {
                return Integer.compare(right[index], left[index]);
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static int compareOrientations(final Entry first,
                                           final Entry second) {
        final boolean[] left = first.oriented();
        final boolean[] right = second.oriented();
        for (var index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                return left[index] ? 1 : -1;
            }
        }
        return 0;
    }
}

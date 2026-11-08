package br.unb.cic.td18.search;

import br.unb.cic.td18.certificate.SortingCertificate;
import br.unb.cic.td18.joint.KnownDiameters;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded sorting-prefix heuristic before the full GPU search. Every result
 * reaches identity or closes the actual contracted remainder with a known
 * diameter. A miss leaves the complete original GPU obligation unchanged.
 */
public final class CpuPrefixSearch {
    private static final int DEFAULT_DEPTH = 3;
    private static final int DEFAULT_TRIPLE_LIMIT = 8192;
    private static final int MAX_TRIPLES = 969; // binomial(19, 3)
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private CpuPrefixSearch() { }

    public static Optional<SortingCertificate> search(CyclicTargetPair pair, int maximumMoves) {
        long started = System.nanoTime();
        try {
            var result = search(pair, maximumMoves,
                    Integer.getInteger("td18.cpuPrefixDepth", DEFAULT_DEPTH),
                    Integer.getInteger("td18.cpuPrefixMaxTriples", DEFAULT_TRIPLE_LIMIT));
            CpuPrefixSearchStats.instance().completed(result, System.nanoTime() - started);
            return result;
        } catch (RuntimeException | Error error) {
            CpuPrefixSearchStats.instance().failed(System.nanoTime() - started);
            throw error;
        }
    }

    static Optional<SortingCertificate> search(CyclicTargetPair pair, int maximumMoves,
                                                int maximumDepth, int maximumTriples) {
        Objects.requireNonNull(pair);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET
                || maximumDepth < 0 || maximumDepth > KnownDiameters.MAX_BUDGET
                || maximumTriples < 0 || pair.getBeta().size() > KnownDiameters.ROOT_SYMBOLS)
            throw new IllegalArgumentException("Invalid CPU prefix domain, budget, depth, or triple limit");
        var work = WORKSPACE.get();
        work.n = pair.getBeta().size();
        work.maximumMoves = maximumMoves;
        work.maximumDepth = Math.min(maximumDepth, maximumMoves);
        work.remainingTriples = maximumTriples;
        work.result = null;
        int fixed = 0;
        for (int symbol = 0; symbol < work.n; symbol++) {
            work.omega[symbol] = pair.getOmega().image(symbol);
            work.beta[0][symbol] = pair.getBeta().get(symbol);
            if (work.omega[symbol] == symbol) fixed++;
        }
        work.visit(0, fixed, work.oddCycles());
        return Optional.ofNullable(work.result);
    }

    private static final class Workspace {
        final int[] omega = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[][] beta = new int[KnownDiameters.MAX_BUDGET + 1][KnownDiameters.ROOT_SYMBOLS];
        final int[][] positions = new int[KnownDiameters.MAX_BUDGET][KnownDiameters.ROOT_SYMBOLS];
        final int[][][] candidates = new int[KnownDiameters.MAX_BUDGET][4][MAX_TRIPLES];
        final int[][] counts = new int[KnownDiameters.MAX_BUDGET][4];
        final int[] stack = new int[KnownDiameters.MAX_BUDGET];
        int n, maximumMoves, maximumDepth, remainingTriples;
        SortingCertificate result;

        boolean visit(int depth, int fixed, int odd) {
            if (fixed == n || KnownDiameters.knownDiameter(n - fixed) <= maximumMoves - depth) {
                var moves = new ArrayList<Cycle>(depth);
                for (int step = 0; step < depth; step++) {
                    int move = stack[step];
                    moves.add(Cycle.of(move & 31, (move >>> 5) & 31, move >>> 10));
                }
                result = fixed == n ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
                return true;
            }
            if (depth == maximumDepth || remainingTriples == 0) return false;
            if (closeConsecutiveSupport(depth, fixed, odd)) return true;
            // At most three new fixed points can be created by one move. This
            // only limits the heuristic; it does not discharge an obligation.
            if (!canClose(depth, fixed)) return false;
            int[] order = beta[depth];
            Arrays.fill(counts[depth], 0);
            triples: for (int i = 0; i < n - 2; i++) {
                int a = order[i];
                if (omega[a] == a) continue;
                for (int j = i + 1; j < n - 1; j++) {
                    int b = order[j];
                    if (omega[b] == b) continue;
                    for (int k = j + 1; k < n; k++) {
                        int c = order[k];
                        if (omega[c] == c) continue;
                        if (remainingTriples == 0) break triples;
                        remainingTriples--;
                        int gain = (omega[c] == a ? 1 : 0) + (omega[a] == b ? 1 : 0)
                                + (omega[b] == c ? 1 : 0);
                        if (canClose(depth + 1, fixed + gain))
                            candidates[depth][gain][counts[depth][gain]++] = i | (j << 5) | (k << 10);
                    }
                }
            }
            // Fixed-point gains order actual sorting moves; no structural
            // reduction or orientation assumption is used, at any depth.
            for (int gain = 3; gain >= 0; gain--) {
                for (int index = 0; index < counts[depth][gain]; index++) {
                    int positions = candidates[depth][gain][index];
                    int i = positions & 31, j = (positions >>> 5) & 31, k = positions >>> 10;
                    int a = order[i], b = order[j], c = order[k];
                    int oa = omega[a], ob = omega[b], oc = omega[c];
                    omega[a] = oc; omega[b] = oa; omega[c] = ob;
                    int childOdd = oddCycles();
                    boolean found = false;
                    if (childOdd - odd == 0 || childOdd - odd == 2) {
                        stack[depth] = a | (b << 5) | (c << 10);
                        int[] nextOrder = beta[depth + 1];
                        System.arraycopy(order, 0, nextOrder, 0, n);
                        System.arraycopy(order, j, nextOrder, i, k - j);
                        System.arraycopy(order, i, nextOrder, i + k - j, j - i);
                        found = visit(depth + 1, fixed + gain, childOdd);
                    }
                    omega[a] = oa; omega[b] = ob; omega[c] = oc;
                    if (found) return true;
                }
            }
            return false;
        }

        /**
         * Apply the paper's consecutive-triple lemma after virtually contracting
         * fixed symbols: deleting them preserves the order on each nonfixed
         * support. Every support creating at least two new fixed points then
         * has two consecutive omega edges after a cyclic rotation.
         * Try these at most N supports for an immediate diameter exit before
         * scanning general triples. This is ordering only; a miss still reaches
         * the general prefix search and the original full GPU obligation.
         */
        boolean closeConsecutiveSupport(int depth, int fixed, int odd) {
            int[] order = beta[depth], position = positions[depth];
            for (int index = 0; index < n; index++) position[order[index]] = index;
            for (int symbol = 0; symbol < n && remainingTriples > 0; symbol++) {
                int a = symbol, b = omega[a], c = omega[b];
                if (a == b || b == c || c == a || omega[c] == c) continue;
                remainingTriples--;
                int gain = 2 + (omega[c] == a ? 1 : 0);
                if (KnownDiameters.knownDiameter(n - fixed - gain) > maximumMoves - depth - 1) continue;
                int pa = position[a], pb = position[b], pc = position[c];
                if (!((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))) continue;
                int i = Math.min(pa, Math.min(pb, pc)), k = Math.max(pa, Math.max(pb, pc));
                int j = pa + pb + pc - i - k;
                a = order[i]; b = order[j]; c = order[k];
                int oa = omega[a], ob = omega[b], oc = omega[c];
                omega[a] = oc; omega[b] = oa; omega[c] = ob;
                int childOdd = oddCycles();
                boolean found = false;
                if (childOdd - odd == 0 || childOdd - odd == 2) {
                    stack[depth] = a | (b << 5) | (c << 10);
                    int[] nextOrder = beta[depth + 1];
                    System.arraycopy(order, 0, nextOrder, 0, n);
                    System.arraycopy(order, j, nextOrder, i, k - j);
                    System.arraycopy(order, i, nextOrder, i + k - j, j - i);
                    found = visit(depth + 1, fixed + gain, childOdd);
                }
                omega[a] = oa; omega[b] = ob; omega[c] = oc;
                if (found) return true;
            }
            return false;
        }

        boolean canClose(int depth, int fixed) {
            for (int used = depth; used <= maximumDepth; used++) {
                int remainder = Math.max(0, n - fixed - 3 * (used - depth));
                if (KnownDiameters.knownDiameter(remainder) <= maximumMoves - used) return true;
            }
            return false;
        }

        int oddCycles() {
            int seen = 0, odd = 0;
            for (int symbol = 0; symbol < n; symbol++) {
                if ((seen & (1 << symbol)) != 0) continue;
                int current = symbol, length = 0;
                do {
                    seen |= 1 << current;
                    length++;
                    current = omega[current];
                } while (current != symbol);
                odd += length & 1;
            }
            return odd;
        }
    }
}

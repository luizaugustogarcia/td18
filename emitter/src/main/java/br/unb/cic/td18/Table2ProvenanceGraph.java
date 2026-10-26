package br.unb.cic.td18;

import br.unb.cic.td18.reduction.AnnotatedPartitions;
import br.unb.cic.td18.reduction.Partitions;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.table.Table2;
import br.unb.cic.td18.table.TableEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Offline visualization of every type-level branch represented by Table 2. */
public final class Table2ProvenanceGraph {
    private static final String DATA_MARKER = "<!-- PROVENANCE_DATA -->";
    private static final int DEPTH_LIMIT = 2;

    private Table2ProvenanceGraph() {
    }

    record Node(String key, String type, int depth, int symbols, int norm,
                int diameter, String status, int row, Long realizable, Long remaining) {
    }

    record Edge(String source, String target, String kind, String label, int fixed) {
        String key() {
            return source + "->" + target;
        }
    }

    record Closure(String source, String kind, int cycleLength, int moves, int symbols,
                   int diameter, int total, String reference) {
    }

    record Graph(List<Node> nodes, List<Edge> edges, List<Closure> closures, int rootPartitions, int annotatedRoots) {
        Graph {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            closures = List.copyOf(closures);
        }
    }

    /** Preserve all incoming edges while expanding each (type, depth) only once. */
    static Graph build(final String latex) {
        final var rows = Table2.parse(latex);
        final var inventory = ReductionTypeEnumerator.enumerate();
        if (rows.size() != Table2.EXPECTED_ENTRIES || inventory.size() != rows.size()) {
            throw new IllegalArgumentException("Expected the complete availability-aware Table 2 inventory");
        }
        final var byState = new TreeMap<String, TableEntry>();
        for (var index = 0; index < rows.size(); index++) {
            final var row = rows.get(index);
            final var entry = inventory.get(index);
            final var key = stateKey(entry);
            final var recorded = new ReductionTypeEnumerator.Obligation(
                    row.partition(), row.orientedByPart(), row.movesUsed());
            if (!key.equals(stateKey(recorded)) || row.recordedRealizableClasses() == null
                    || row.remainingClassObligations() == null) {
                throw new IllegalArgumentException("Table 2/enumerator mismatch at row " + row.number());
            }
            byState.put(key, row);
        }

        final var pending = new ArrayDeque<ReductionTypeEnumerator.Obligation>();
        final var roots = Partitions.generateValidPartitions(19);
        for (final var partition : roots) {
            final int[] descending = Arrays.stream(partition).boxed()
                    .sorted(java.util.Comparator.reverseOrder()).mapToInt(Integer::intValue).toArray();
            for (final var annotation : AnnotatedPartitions.of(descending)) {
                pending.add(new ReductionTypeEnumerator.Obligation(
                        annotation.partition(), annotation.oriented(), 0));
            }
        }
        final int rootCount = pending.size();
        if (roots.size() != 53 || rootCount != 360) {
            throw new IllegalStateException("Root inventory differs from the reduction document");
        }

        final var expanded = new HashSet<String>();
        final var nodes = new TreeMap<String, Node>();
        final var edges = new TreeMap<String, Edge>();
        final var obligationDepths = new TreeMap<Integer, Integer>();
        final var closures = new ArrayList<Closure>();
        while (!pending.isEmpty()) {
            final var state = pending.removeFirst();
            final var key = stateKey(state);
            if (!expanded.add(key)) {
                continue;
            }
            final int diameter = ReductionTypeEnumerator.knownDiameter(state.nonfixedSymbols());
            final boolean closed = diameter != Integer.MAX_VALUE
                    && state.movesUsed() + diameter <= 10;
            final var options = closed || state.movesUsed() == DEPTH_LIMIT
                    ? List.<ReductionTypeEnumerator.ReductionOption>of()
                    : ReductionTypeEnumerator.reductionOptions(state.lengths(), state.oriented(), state.movesUsed());
            final boolean retained = !closed && (state.movesUsed() == DEPTH_LIMIT
                    || ReductionTypeEnumerator.mayHaveNoAvailableReduction(state.lengths(), state.oriented(), state.movesUsed()));
            final var row = byState.get(key);
            if (retained != (row != null)) {
                throw new IllegalStateException("Retention/Table 2 mismatch: " + key);
            }
            if (state.threeNorm() >= state.budget()) {
                throw new IllegalStateException("Invalid reachable budget: " + key);
            }
            nodes.put(key, new Node(key, state.typeKey(), state.movesUsed(), state.nonfixedSymbols(),
                    state.threeNorm(), diameter, closed ? "closed" : retained ? "retained" : "open",
                    row == null ? 0 : row.number(), row == null ? null : row.recordedRealizableClasses(),
                    row == null ? null : row.remainingClassObligations()));
            if (retained) {
                obligationDepths.merge(state.movesUsed(), 1, Integer::sum);
            }
            if (!closed && state.movesUsed() < DEPTH_LIMIT) {
                final int k = ReductionTypeEnumerator.closingOddCycleLength(
                        state.lengths(), state.oriented(), state.movesUsed());
                if (k > 0 && k != 15 && k != 19) {
                    final int moves = (k + 1) / 2;
                    final int remaining = state.nonfixedSymbols() - k;
                    final int bound = ReductionTypeEnumerator.knownDiameter(remaining);
                    closures.add(new Closure(key, "SUPPORT_CLEARING", k, moves, remaining,
                            bound, state.movesUsed() + moves + bound,
                            "../td/docs/paper/main.tex#prop:td16-odd-cycle-reduction"));
                }
                if (k == 9 || k == 19) {
                    closures.add(new Closure(key, k == 9 ? "EXCEPTIONAL_NINE" : "EXCEPTIONAL_NINETEEN",
                            k, 10, 0, 0, 10, "td18-reduction-count.tex"));
                }
                if (k == 15) {
                    closures.add(new Closure(key, "EXCEPTIONAL_FIFTEEN", k,
                            state.budget(), 0, 0, 10,
                            "td18-reduction-count.tex#sec:exceptional-fifteen"));
                }
            }
            for (final var option : options) {
                for (final var annotation : AnnotatedPartitions.of(option.childLengths())) {
                    final var child = new ReductionTypeEnumerator.Obligation(
                            annotation.partition(), annotation.oriented(), state.movesUsed() + 1);
                    if (child.threeNorm() != state.threeNorm() - 1) {
                        throw new IllegalStateException("Invalid reduction norm at " + key);
                    }
                    final var edge = edgeFor(state, child, option);
                    edges.put(edge.key(), edge);
                    pending.addLast(child);
                }
            }
        }
        if (!obligationDepths.equals(ReductionTypeEnumerator.EXPECTED_DEPTH_COUNTS)
                || !nodes.keySet().containsAll(byState.keySet())) {
            throw new IllegalStateException("Incomplete obligation provenance");
        }
        return new Graph(List.copyOf(nodes.values()), List.copyOf(edges.values()), closures, roots.size(), rootCount);
    }

    private static String stateKey(final ReductionTypeEnumerator.Obligation state) {
        return state.typeKey() + '@' + state.movesUsed();
    }

    private static Edge edgeFor(final ReductionTypeEnumerator.Obligation parent,
                                final ReductionTypeEnumerator.Obligation child,
                                final ReductionTypeEnumerator.ReductionOption option) {
        final int fixed = parent.nonfixedSymbols() - child.nonfixedSymbols();
        final String kind;
        final String label;
        switch (option.kind()) {
            case ORIENTED_THREE -> {
                kind = "CLEAR_THREE";
                label = "Oriented 3-cycle: clear 3O";
            }
            case ORIENTED_ODD -> {
                final int selected = option.cycleLength();
                if (fixed == 2) {
                    kind = "ODD_TWO_FIXED";
                    label = "Qualifying 2-move: " + selected + "O → " + (selected - 2);
                } else {
                    kind = "ODD_SPLIT";
                    // Remove the unchanged companion lengths as a multiset.
                    final var added = Arrays.stream(child.lengths()).boxed()
                            .collect(Collectors.toCollection(java.util.ArrayList::new));
                    var removedSelected = false;
                    for (final int length : parent.lengths()) {
                        if (length == selected && !removedSelected) {
                            removedSelected = true;
                        } else if (!added.remove(Integer.valueOf(length))) {
                            throw new IllegalStateException("Invalid odd-length child partition");
                        }
                    }
                    kindCheck(added.size() == 2 && fixed == 1);
                    added.sort(Integer::compareTo);
                    label = "Qualifying 2-move: " + selected + "O → " + added.get(0) + " + " + added.get(1);
                }
            }
            case EVEN_PAIR -> {
                kind = "EVEN_PAIR";
                final int[] even = Arrays.stream(parent.lengths()).filter(length -> length % 2 == 0).toArray();
                label = "Even-pair reduction: " + even[0] + " + " + even[1] + " → " + (even[0] + even[1] - 1);
            }
            default -> throw new IllegalStateException("Edge without a reduction option");
        }
        kindCheck(fixed == (kind.equals("CLEAR_THREE") ? 3 : kind.equals("ODD_TWO_FIXED") ? 2 : 1));
        return new Edge(stateKey(parent), stateKey(child), kind, label, fixed);
    }

    private static void kindCheck(final boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Reduction fixed-point gain mismatch");
        }
    }

    static String payload(final Graph graph) {
        final var nodes = graph.nodes().stream().map(node -> "{\"key\":" + quote(node.key())
                + ",\"type\":" + quote(node.type()) + ",\"depth\":" + node.depth()
                + ",\"N\":" + node.symbols() + ",\"kappa\":" + node.norm()
                + ",\"D\":" + (node.diameter() == Integer.MAX_VALUE ? "null" : node.diameter())
                + ",\"status\":" + quote(node.status()) + ",\"row\":" + node.row()
                + ",\"R\":" + (node.realizable() == null ? "null" : quote(node.realizable().toString()))
                + ",\"Q\":" + (node.remaining() == null ? "null" : quote(node.remaining().toString())) + "}")
                .collect(Collectors.joining(",\n"));
        final var edges = graph.edges().stream().map(edge -> "{\"source\":" + quote(edge.source())
                + ",\"target\":" + quote(edge.target()) + ",\"kind\":" + quote(edge.kind())
                + ",\"label\":" + quote(edge.label()) + ",\"fixed\":" + edge.fixed() + "}")
                .collect(Collectors.joining(",\n"));
        final var closures = graph.closures().stream().map(c -> "{\"source\":" + quote(c.source())
                + ",\"kind\":" + quote(c.kind()) + ",\"cycleLength\":" + c.cycleLength()
                + ",\"moves\":" + c.moves() + ",\"N\":" + c.symbols() + ",\"D\":" + c.diameter()
                + ",\"total\":" + c.total() + ",\"reference\":" + quote(c.reference()) + "}")
                .collect(Collectors.joining(",\n"));
        return "{\"rootPartitions\":" + graph.rootPartitions() + ",\"annotatedRoots\":" + graph.annotatedRoots()
                + ",\"nodeCount\":" + graph.nodes().size() + ",\"edgeCount\":" + graph.edges().size()
                + ",\"rowCount\":" + Table2.EXPECTED_ENTRIES + ",\"nodes\":[\n" + nodes + "],\"edges\":[\n" + edges + "],\"closures\":[\n" + closures + "]}";
    }

    private static String quote(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replace("<", "\\u003c") + '"';
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 0) {
            throw new IllegalArgumentException("Table2ProvenanceGraph takes no arguments; run from this repository");
        }
        final var root = ProjectPaths.root(Path.of(""));
        final var source = Files.readString(root.resolve("docs/td18-reduction-count.tex"), StandardCharsets.UTF_8);
        final var graph = build(source);
        final var payload = payload(graph);
        final var template = Files.readString(root.resolve("docs/table2-provenance.template.html"), StandardCharsets.UTF_8);
        if (template.indexOf(DATA_MARKER) < 0 || template.indexOf(DATA_MARKER) != template.lastIndexOf(DATA_MARKER)) {
            throw new IllegalArgumentException("Expected exactly one visualization data marker");
        }
        final var data = "<script id=\"provenance-data\" type=\"application/json\">"
                + "{\"format\":\"td18-type-provenance\",\"version\":5,\"complete\":true,"
                + "\"sourceSha256\":" + quote(sha256(source)) + ",\"payloadSha256\":" + quote(sha256(payload))
                + ",\"payload\":" + quote(payload) + "}</script>";
        Files.writeString(root.resolve("docs/table2-provenance.html"), template.replace(DATA_MARKER, data), StandardCharsets.UTF_8);
        System.out.println("Table 2 provenance: 53 partitions, 360 roots, " + Table2.EXPECTED_ENTRIES + " rows, " + graph.nodes().size()
                + " reached states, " + graph.edges().size() + " edges; SHA-256 " + sha256(payload));
    }
}

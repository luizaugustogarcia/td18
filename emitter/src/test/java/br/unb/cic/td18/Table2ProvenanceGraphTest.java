package br.unb.cic.td18;

import br.unb.cic.td18.runtime.ProjectPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Table2ProvenanceGraphTest {
    private static String source() throws Exception {
        return Files.readString(ProjectPaths.root(Path.of(""))
                .resolve("docs/td18-reduction-count.tex"));
    }

    @Test
    void preservesAllRowsAndStopsAtKnownDiameterClosures() throws Exception {
        final var graph = Table2ProvenanceGraph.build(source());
        final var nodes = graph.nodes().stream().collect(Collectors.toMap(
                Table2ProvenanceGraph.Node::key, Function.identity()));
        assertEquals(360, graph.nodes().stream().filter(node -> node.depth() == 0).count());
        assertEquals(Map.of(0, 59L, 1, 45L, 2, 188L), graph.nodes().stream()
                .filter(node -> node.row() != 0).collect(Collectors.groupingBy(
                        Table2ProvenanceGraph.Node::depth, Collectors.counting())));
        for (final var edge : graph.edges()) {
            final var parent = nodes.get(edge.source());
            final var child = nodes.get(edge.target());
            assertTrue(parent.status().equals("open") || parent.status().equals("retained"));
            assertTrue(parent.depth() < 2);
            assertEquals(parent.depth() + 1, child.depth());
            assertEquals(parent.norm() - 1, child.norm());
            assertEquals(parent.symbols() - child.symbols(), edge.fixed());
        }
        assertTrue(graph.nodes().stream().anyMatch(node -> node.status().equals("closed")));
        assertEquals(graph.nodes().size() - 360, graph.edges().stream()
                .map(Table2ProvenanceGraph.Edge::target).distinct().count());
    }

    @Test
    void totallyUnorientedTypesHaveNoChildrenAndEverySuchRootIsAnObligation() throws Exception {
        final var graph = Table2ProvenanceGraph.build(source());
        final var unoriented = graph.nodes().stream().filter(n -> !n.type().contains("O")).toList();
        assertEquals(53, unoriented.stream().filter(n -> n.depth() == 0 && n.row() > 0).count());
        for (final var node : unoriented) {
            assertTrue(graph.edges().stream().noneMatch(e -> e.source().equals(node.key())));
            assertTrue(graph.closures().stream().noneMatch(c -> c.source().equals(node.key())));
            assertTrue(node.status().equals("closed") || node.row() > 0);
        }
        assertTrue(unoriented.stream().anyMatch(n -> n.key().equals("5U,4U,4U,3U,3U@0") && n.row() > 0));
        assertTrue(unoriented.stream().anyMatch(n -> n.key().equals("6U,6U,3U,3U@1") && n.row() > 0));
    }

    @Test
    void retainsMergedParentsAndFreshCompanionAnnotations() throws Exception {
        final var graph = Table2ProvenanceGraph.build(source());
        // Clearing 3O leaves the companion 7-cycle free to receive O afresh.
        assertTrue(graph.edges().stream().anyMatch(edge ->
                edge.source().equals("7U,3U,3U,3O,3O@0")
                        && edge.target().equals("7O,3O,3O,3O@1")
                        && edge.fixed() == 3 && edge.kind().equals("CLEAR_THREE")));
        // Several incoming branches must survive the terminal-type merge.
        assertTrue(graph.edges().stream().filter(edge ->
                edge.target().equals("11O,5O@2")).count() > 1);
        assertEquals(Table2ProvenanceGraph.payload(graph),
                Table2ProvenanceGraph.payload(Table2ProvenanceGraph.build(source())));
    }

    @Test
    void rejectsMissingRowsAndInconsistentRowMetadata() throws Exception {
        final var latex = source();
        assertThrows(IllegalArgumentException.class, () -> Table2ProvenanceGraph.build(
                latex.replaceFirst("(?m)^  292 &.*\\R", "")));
        assertThrows(IllegalArgumentException.class, () -> Table2ProvenanceGraph.build(
                latex.replace("& 19 & 0 & 0 & 10 & 7 & 96", "& 19 & 0 & 1 & 9 & 7 & 96")));
    }
    @Test
    void displaysFallbackAndRetainedPathsWhenQualifyingFiveIsUnavailable() throws Exception {
        final var graph = Table2ProvenanceGraph.build(source());
        final var sourceKey = "5O,4U,4U,3U,3U@0";
        assertTrue(graph.nodes().stream().anyMatch(n -> n.key().equals(sourceKey) && n.row() == 0));
        assertEquals(12, graph.edges().stream().filter(e -> e.source().equals(sourceKey)
                && e.kind().equals("EVEN_PAIR") && e.fixed() == 1 && e.label().equals("Even-pair reduction: 4 + 4 → 7")).count());
        assertTrue(graph.edges().stream().anyMatch(e -> e.source().equals(sourceKey)
                && e.kind().equals("ODD_TWO_FIXED")));
        final var retainedKey = "7O,5O,3U,3U@1";
        assertTrue(graph.nodes().stream().anyMatch(n -> n.key().equals(retainedKey)
                && n.status().equals("retained") && n.row() > 204));
        assertTrue(graph.edges().stream().anyMatch(e -> e.source().equals(retainedKey) && e.label().contains("5O →")));
        assertTrue(graph.edges().stream().anyMatch(e -> e.source().equals(retainedKey) && e.label().contains("7O →")));
    }

    @Test
    void orientedThirteenHasClearingClosureAndQualifyingChildrenWithoutAnObligation() throws Exception {
        final var graph = Table2ProvenanceGraph.build(source());
        final var key = "13O,3U,3U@0";
        assertTrue(graph.nodes().stream().anyMatch(n -> n.key().equals(key) && n.row() == 0));
        final var closure = graph.closures().stream().filter(c -> c.source().equals(key)).findFirst().orElseThrow();
        assertEquals("SUPPORT_CLEARING", closure.kind());
        assertEquals(7, closure.moves());
        assertEquals(6, closure.symbols());
        assertEquals(3, closure.diameter());
        assertEquals(10, closure.total());
        assertEquals(26, graph.edges().stream().filter(e -> e.source().equals(key)).count());
    }

    @Test
    void fifteenUsesRestrictedInterleavingsAtTheStateBudgetInsteadOfAWholeTypeRow() throws Exception {
        final var graph = Table2ProvenanceGraph.build(source());
        for (final var key : new String[]{"15O,2U,2U@0", "15O,3U@1"}) {
            final var node = graph.nodes().stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
            assertEquals(0, node.row());
            final var alternatives = graph.closures().stream().filter(c -> c.source().equals(key)).toList();
            assertEquals(1, alternatives.size());
            final var closure = alternatives.getFirst();
            assertEquals("EXCEPTIONAL_FIFTEEN", closure.kind());
            assertEquals(10 - node.depth(), closure.moves());
            assertEquals(10, closure.total());
            assertTrue(graph.edges().stream().filter(e -> e.source().equals(key))
                    .allMatch(e -> e.kind().startsWith("ODD_")));
            assertTrue(graph.edges().stream().anyMatch(e -> e.source().equals(key)));
        }
        assertTrue(graph.closures().stream().noneMatch(c -> c.cycleLength() == 15
                && c.kind().equals("SUPPORT_CLEARING")));
    }

}

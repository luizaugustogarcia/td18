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
        assertEquals(Map.of(0, 8L, 1, 8L, 2, 188L), graph.nodes().stream()
                .filter(node -> node.row() != 0).collect(Collectors.groupingBy(
                        Table2ProvenanceGraph.Node::depth, Collectors.counting())));
        for (final var edge : graph.edges()) {
            final var parent = nodes.get(edge.source());
            final var child = nodes.get(edge.target());
            assertEquals("open", parent.status());
            assertEquals(parent.depth() + 1, child.depth());
            assertEquals(parent.norm() - 1, child.norm());
            assertEquals(parent.symbols() - child.symbols(), edge.fixed());
        }
        assertTrue(graph.nodes().stream().anyMatch(node -> node.status().equals("closed")));
        assertEquals(graph.nodes().size() - 360, graph.edges().stream()
                .map(Table2ProvenanceGraph.Edge::target).distinct().count());
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
                latex.replaceFirst("(?m)^  204 &.*\\R", "")));
        assertThrows(IllegalArgumentException.class, () -> Table2ProvenanceGraph.build(
                latex.replace("& 19 & 0 & 0 & 10 & 7 & 96", "& 19 & 0 & 1 & 9 & 7 & 96")));
    }
}

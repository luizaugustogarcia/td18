package br.unb.cic.td18;

import br.unb.cic.td18.base.WitnessSearch;
import br.unb.cic.td18.base.AsyncCertificateWriter;
import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.permutation.Cycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientedNineteenCycleEmitterTest {
    private static final List<List<Cycle>> WITNESSES = List.of(
            cycles(new int[][]{
                    {1, 5, 12}, {3, 12, 17}, {12, 13, 4},
                    {11, 2, 12}, {10, 1, 18}, {7, 17, 15},
                    {0, 10, 9}, {7, 6, 16}, {14, 5, 11},
                    {3, 8, 18}
            }),
            cycles(new int[][]{
                    {0, 13, 18}, {4, 8, 18}, {3, 12, 6},
                    {4, 10, 7}, {1, 11, 8}, {5, 2, 18},
                    {16, 6, 9}, {15, 12, 0}, {13, 16, 6},
                    {17, 14, 11}
            }));

    @Test
    void importsAndReusesBothKnownWitnessesWithoutCuda(@TempDir final Path directory) throws Exception {
        final var pairs = OrientedNineteenCycleEmitter.survivorPairs();
        final var lines = new java.util.ArrayList<String>();
        for (var index = 0; index < pairs.size(); index++) {
            lines.add(HexFormat.of().formatHex(CertificateCodec.keyFor(pairs.get(index))) + " "
                    + HexFormat.of().formatHex(CertificateCodec.valueFor(WITNESSES.get(index))));
        }
        final var source = directory.resolve("witnesses.txt");
        Files.write(source, lines);
        final var imported = OrientedNineteenCycleEmitter.readWitnesses(source);
        try (final var writer = new AsyncCertificateWriter(directory.resolve("database"), 60)) {
            final var emitted = OrientedNineteenCycleEmitter.ensure(writer, (pair, rate, budget) -> {
                final var moves = imported.get(HexFormat.of().formatHex(CertificateCodec.keyFor(pair)));
                writer.enqueue(pair, moves);
                return WitnessSearch.SearchResult.certificate(moves);
            });
            writer.flush();
            assertEquals(2, emitted.certificates());
            final var reused = OrientedNineteenCycleEmitter.ensure(writer, (pair, rate, budget) -> {
                throw new AssertionError("Persisted witnesses must not start a search");
            });
            assertEquals(2, reused.certificates());
            for (final var pair : pairs) {
                assertTrue(writer.find(pair, 10).isPresent());
            }
        }
        Files.write(source, lines.subList(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> OrientedNineteenCycleEmitter.readWitnesses(source));
        Files.write(source, List.of(lines.getFirst(), lines.getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> OrientedNineteenCycleEmitter.readWitnesses(source));
        final var invalidMoves = new java.util.ArrayList<>(WITNESSES.getFirst());
        invalidMoves.set(0, Cycle.of(0, 1, 2));
        Files.write(source, List.of(lines.getFirst().split(" ")[0] + " "
                + HexFormat.of().formatHex(CertificateCodec.valueFor(invalidMoves)), lines.get(1)));
        assertThrows(IllegalArgumentException.class,
                () -> OrientedNineteenCycleEmitter.readWitnesses(source));
    }

    @Test
    void emitsOneValidatedCertificatePerConfiguredSurvivor() {
        final var calls = new AtomicInteger();
        final var pairs = OrientedNineteenCycleEmitter.survivorPairs();

        final var result = OrientedNineteenCycleEmitter.emit(
                (pair, minimumRate, maximumMoves) -> {
                    final int index = pairs.indexOf(pair);
                    assertTrue(index >= 0);
                    assertEquals(1.8f, minimumRate);
                    assertEquals(10, maximumMoves);
                    calls.incrementAndGet();
                    return WitnessSearch.SearchResult.certificate(
                            WITNESSES.get(index));
                });

        assertEquals(2, calls.get());
        assertEquals(2, result.survivors());
        assertEquals(2, result.certificates());
    }

    @Test
    void rejectsANativeMiss() {
        final var failure = assertThrows(IllegalStateException.class,
                () -> OrientedNineteenCycleEmitter.emit(
                        (pair, rate, moves) ->
                                WitnessSearch.SearchResult.residual()));

        assertTrue(failure.getMessage().contains(
                "was not emitted as a complete sorting certificate"));
    }

    private static List<Cycle> cycles(final int[][] moves) {
        return java.util.Arrays.stream(moves)
                .map(move -> Cycle.of(move.clone()))
                .toList();
    }
}

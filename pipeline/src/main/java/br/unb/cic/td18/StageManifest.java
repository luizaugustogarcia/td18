package br.unb.cic.td18;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic completion record for one immutable pipeline data product. */
public final class StageManifest {
    private static final String FILE_NAME = "manifest.txt";
    private static final String FORMAT = "td18-extremal-v2";

    private StageManifest() {
    }

    public static boolean isComplete(final Path stageDirectory) {
        final var values = read(stageDirectory);
        return "complete".equals(values.get("status"));
    }

    public static void requireComplete(
            final Path stageDirectory,
            final String expectedStage,
            final int expectedDomainSize
    ) {
        final var values = read(stageDirectory);
        if (!FORMAT.equals(values.get("format"))
                || !"complete".equals(values.get("status"))
                || !expectedStage.equals(values.get("stage"))
                || !Integer.toString(expectedDomainSize).equals(
                values.get("domainSize"))) {
            throw new IllegalStateException(
                    "Incomplete or incompatible stage at " + stageDirectory);
        }
    }

    public static void complete(
            final Path stageDirectory,
            final String stage,
            final int domainSize,
            final ClassStore.Summary summary
    ) {
        complete(stageDirectory, stage, domainSize, summary, "");
    }

    static void completeKnownDiameter(
            final Path stageDirectory,
            final String stage,
            final int domainSize,
            final ClassStore.Summary summary
    ) {
        complete(stageDirectory, stage, domainSize, summary,
                "upperBoundSource=known-diameter\nupperBound="
                        + KnownDiameters.forDomain(domainSize) + "\n");
    }

    private static void complete(
            final Path stageDirectory,
            final String stage,
            final int domainSize,
            final ClassStore.Summary summary,
            final String evidence
    ) {
        try {
            Files.createDirectories(stageDirectory);
            final var contents = "format=" + FORMAT + "\n"
                    + "stage=" + stage + "\n"
                    + "domainSize=" + domainSize + "\n"
                    + "status=complete\n"
                    + "count=" + summary.count() + "\n"
                    + "sha256=" + summary.sha256() + "\n"
                    + evidence;
            final var temporary = stageDirectory.resolve(FILE_NAME + ".tmp");
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            final var destination = stageDirectory.resolve(FILE_NAME);
            try {
                Files.move(temporary, destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException failure) {
            throw new IllegalStateException("Cannot write stage manifest", failure);
        }
    }

    public static void verify(
            final Path stageDirectory,
            final String stage,
            final int domainSize,
            final ClassStore.Summary actual
    ) {
        requireComplete(stageDirectory, stage, domainSize);
        final var values = read(stageDirectory);
        if (!Long.toString(actual.count()).equals(values.get("count"))
                || !actual.sha256().equals(values.get("sha256"))) {
            throw new IllegalStateException(
                    "Stage count or checksum mismatch at " + stageDirectory);
        }
    }

    private static Map<String, String> read(final Path stageDirectory) {
        final var path = stageDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            final var result = new LinkedHashMap<String, String>();
            for (final var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                final var separator = line.indexOf('=');
                if (separator > 0) {
                    result.put(line.substring(0, separator),
                            line.substring(separator + 1));
                }
            }
            return Map.copyOf(result);
        } catch (final IOException failure) {
            throw new IllegalStateException("Cannot read stage manifest " + path, failure);
        }
    }
}

package br.unb.cic.td18.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Read-only access to versioned proof-completion sidecar manifests. */
public final class CertificateManifestStore {
    private static final String DIRECTORY = "proof-manifests";
    private static final String ROW_PREFIX = "row-";
    private static final String AVOIDANCE_PREFIX = "avoidance-";
    private static final String SUFFIX = ".bin";

    private final Path directory;

    public CertificateManifestStore(final Path databaseDirectory) {
        directory = databaseDirectory.toAbsolutePath().resolve(DIRECTORY);
    }

    public CertificateRowManifest requireRow(final int row) {
        final var path = directory.resolve(String.format("%s%05d%s",
                ROW_PREFIX, row, SUFFIX));
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        "Missing certificate row manifest: " + row);
            }
            return CertificateRowManifest.decode(Files.readAllBytes(path));
        } catch (final IOException failure) {
            throw new IllegalStateException("Cannot read row manifest " + path, failure);
        }
    }

    public List<AvoidancePhaseManifest> avoidanceManifests() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (final var paths = Files.list(directory)) {
            return paths.filter(path -> {
                        final var name = path.getFileName().toString();
                        return name.startsWith(AVOIDANCE_PREFIX)
                                && name.endsWith(SUFFIX);
                    })
                    .map(CertificateManifestStore::readAvoidance)
                    .sorted(Comparator.comparingInt(
                            AvoidancePhaseManifest::firstRow))
                    .toList();
        } catch (final IOException failure) {
            throw new IllegalStateException(
                    "Cannot list avoidance manifests in " + directory, failure);
        }
    }

    private static AvoidancePhaseManifest readAvoidance(final Path path) {
        try {
            return AvoidancePhaseManifest.decode(Files.readAllBytes(path));
        } catch (final IOException failure) {
            throw new IllegalStateException(
                    "Cannot read avoidance manifest " + path, failure);
        }
    }
}

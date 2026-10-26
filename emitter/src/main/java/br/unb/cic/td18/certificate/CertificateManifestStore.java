package br.unb.cic.td18.certificate;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/** Versioned sidecar manifests for counts, checksums, and completion markers. */
public final class CertificateManifestStore {
    private static final String DIRECTORY = "proof-manifests";
    private static final String ROW_PREFIX = "row-";
    private static final String AVOIDANCE_PREFIX = "avoidance-";
    private static final String SUFFIX = ".bin";

    private final Path directory;

    public CertificateManifestStore(final Path databaseDirectory) {
        directory = databaseDirectory.toAbsolutePath().resolve(DIRECTORY);
    }

    public void write(final CertificateRowManifest manifest) {
        writeAtomically(rowPath(manifest.row()), manifest.encoded());
    }

    public void write(final AvoidancePhaseManifest manifest) {
        writeAtomically(avoidancePath(manifest.firstRow(), manifest.lastRow()),
                manifest.encoded());
    }

    public CertificateRowManifest requireRow(final int row) {
        final var path = rowPath(row);
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

    private Path rowPath(final int row) {
        return directory.resolve(String.format("%s%05d%s",
                ROW_PREFIX, row, SUFFIX));
    }

    private Path avoidancePath(final int firstRow, final int lastRow) {
        return directory.resolve(String.format("%s%05d-%05d%s",
                AVOIDANCE_PREFIX, firstRow, lastRow, SUFFIX));
    }

    private static AvoidancePhaseManifest readAvoidance(final Path path) {
        try {
            return AvoidancePhaseManifest.decode(Files.readAllBytes(path));
        } catch (final IOException failure) {
            throw new IllegalStateException(
                    "Cannot read avoidance manifest " + path, failure);
        }
    }

    private void writeAtomically(final Path target, final byte[] encoded) {
        try {
            Files.createDirectories(directory);
            final var temporary = Files.createTempFile(
                    directory, target.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, encoded);
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (final IOException failure) {
            throw new IllegalStateException(
                    "Cannot write proof manifest " + target, failure);
        }
    }
}

package br.unb.cic.td18;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Append-only set of completed generation units. */
public final class CheckpointSet {
    private final Path path;
    private final Set<String> completed;

    public CheckpointSet(final Path path) {
        this.path = path;
        try {
            completed = Files.isRegularFile(path)
                    ? new HashSet<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                    : new HashSet<>();
        } catch (final IOException failure) {
            throw new IllegalStateException("Cannot read checkpoints " + path, failure);
        }
    }

    public synchronized boolean contains(final String key) {
        return completed.contains(key);
    }

    public synchronized void add(final String key) {
        if (!completed.add(key)) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, key + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (final IOException failure) {
            completed.remove(key);
            throw new IllegalStateException("Cannot append checkpoint " + path, failure);
        }
    }

    public synchronized Set<String> snapshot() {
        return Set.copyOf(completed);
    }
}

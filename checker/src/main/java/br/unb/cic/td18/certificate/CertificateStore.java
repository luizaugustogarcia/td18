package br.unb.cic.td18.certificate;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/** Read-only version-1 witness/obstruction schema, with independent replay. */
public final class CertificateStore implements AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final byte[] OBSTRUCTIONS = "obstructions".getBytes(StandardCharsets.UTF_8);
    static { RocksDB.loadLibrary(); }
    private final DBOptions options = new DBOptions().setMaxOpenFiles(256);
    private final ColumnFamilyOptions witnessOptions = new ColumnFamilyOptions();
    private final ColumnFamilyOptions obstructionOptions = new ColumnFamilyOptions();
    private final ReadOptions reads = new ReadOptions().setFillCache(false);
    private final ArrayList<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB database;

    public CertificateStore(final Path directory) {
        RocksDB opened = null;
        try (final var listing = new Options()) {
            final var families = RocksDB.listColumnFamilies(listing, directory.toString());
            if (families.size() != 2 || families.stream().noneMatch(x -> Arrays.equals(x, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(x -> Arrays.equals(x, OBSTRUCTIONS)))
                throw new IllegalStateException("Unsupported certificate format: expected version-1 two-family schema");
            opened = RocksDB.openReadOnly(options, directory.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                    new ColumnFamilyDescriptor(OBSTRUCTIONS, obstructionOptions)), handles);
            database = opened;
            final var marker = database.get(handles.get(0), "td18-schema".getBytes(StandardCharsets.UTF_8));
            if (!Arrays.equals(marker, "joint-selection-v1".getBytes(StandardCharsets.UTF_8)))
                throw new IllegalStateException("Missing or unsupported certificate format marker");
        } catch (final RocksDBException | RuntimeException failure) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            closeOptions();
            throw new IllegalStateException("Cannot open certificate database " + directory, failure);
        }
    }
    public Optional<List<Cycle>> find(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 0) throw new IllegalArgumentException("Negative move budget");
        final var canonical = pair.canonicalRepresentative();
        final byte[] value = get(handles.get(0), CertificateCodec.keyFor(canonical));
        if (value == null) return Optional.empty();
        final var moves = CertificateCodec.decodeValue(value);
        // A longer valid witness is unavailable for this budget. Corruption is
        // rejected even when the witness would not satisfy the smaller budget.
        CertificateValidator.validate(canonical, moves, moves.size());
        return moves.size() <= maximumMoves ? Optional.of(moves) : Optional.empty();
    }
    public List<Optional<List<Cycle>>> find(final CyclicTargetPair[] pairs, final int maximumMoves) {
        final var result = new ArrayList<Optional<List<Cycle>>>(pairs.length);
        for (final var pair : pairs) result.add(find(pair, maximumMoves));
        return List.copyOf(result);
    }
    public List<Cycle> require(final CyclicTargetPair pair, final int maximumMoves) {
        return find(pair, maximumMoves).orElseThrow(() -> new IllegalStateException(
                "Missing sorting certificate: classKey=" + pair.extendedToricClassKey() + ", maximumMoves=" + maximumMoves));
    }
    public boolean hasObstruction(final CyclicTargetPair pair) {
        final byte[] value = get(handles.get(1), CertificateCodec.keyFor(pair.canonicalRepresentative()));
        if (value != null && value.length != 0) throw new IllegalStateException("An obstruction must have an empty value");
        return value != null;
    }
    public void forEachObstruction(final Consumer<byte[]> consumer) {
        try (final var iterator = database.newIterator(handles.get(1), reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                if (iterator.value().length != 0) throw new IllegalStateException("An obstruction must have an empty value");
                consumer.accept(iterator.key());
            }
            iterator.status();
        } catch (final RocksDBException failure) { throw new IllegalStateException("Cannot read obstruction keys", failure); }
    }
    private byte[] get(final ColumnFamilyHandle family, final byte[] key) {
        try { return database.get(family, reads, key); }
        catch (final RocksDBException failure) { throw new IllegalStateException("Cannot read certificate", failure); }
    }
    @Override public void close() { handles.forEach(ColumnFamilyHandle::close); database.close(); closeOptions(); }
    private void closeOptions() { reads.close(); witnessOptions.close(); obstructionOptions.close(); options.close(); }
}

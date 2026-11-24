package br.unb.cic.td18.certificate;

import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.permutation.Cycle;
import lombok.val;
import org.rocksdb.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads and replays complete witnesses from an independently supplied database.
 */
public final class ReadOnlyWitnessSource implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions options = new DBOptions();
    private final List<ColumnFamilyOptions> familyOptions = new ArrayList<>();
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses;

    public ReadOnlyWitnessSource(final Path directory) {
        RocksDB opened = null;

        try (val listing = new Options()) {
            val families = RocksDB.listColumnFamilies(listing, directory.toAbsolutePath().toString());
            val descriptors = new ArrayList<ColumnFamilyDescriptor>();
            int defaultIndex = -1;
            for (int i = 0; i < families.size(); i++) {
                val family = new ColumnFamilyOptions();
                familyOptions.add(family);
                descriptors.add(new ColumnFamilyDescriptor(families.get(i), family));
                if (Arrays.equals(families.get(i), RocksDB.DEFAULT_COLUMN_FAMILY)) {
                    defaultIndex = i;
                }
            }
            if (defaultIndex < 0) {
                throw new IllegalStateException("Witness source has no default column family");
            }

            opened = RocksDB.openReadOnly(options, directory.toAbsolutePath().toString(), descriptors, handles);
            database = opened;
            witnesses = handles.get(defaultIndex);
        } catch (final RocksDBException | RuntimeException failure) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) {
                opened.close();
            }

            familyOptions.forEach(ColumnFamilyOptions::close);
            options.close();
            throw new IllegalStateException("Cannot open witness source " + directory, failure);
        }
    }

    public Optional<List<Cycle>> find(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > 10) {
            throw new IllegalArgumentException("Budget must be 1..10");
        }

        try {
            final byte[] encoded = database.get(witnesses, CertificateCodec.keyFor(pair));
            if (encoded == null) {
                return Optional.empty();
            }

            val moves = CertificateCodec.decodeValue(encoded);
            CertificateValidator.validate(pair, moves, moves.size());

            return moves.size() <= maximumMoves ? Optional.of(moves) : Optional.empty();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot read witness source", failure);
        }
    }

    @Override
    public void close() {
        handles.forEach(ColumnFamilyHandle::close);
        database.close();
        familyOptions.forEach(ColumnFamilyOptions::close);
        options.close();
    }
}

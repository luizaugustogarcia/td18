package br.unb.cic.td18.verification;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Independent checker-owned scratch storage; certificate databases remain read-only.
 */
final class CheckerStateSet implements AutoCloseable {

    private final CheckerStateStorage storage;

    record Inventory(long count) {
    }

    CheckerStateSet(final Path directory) {
        storage = new CheckerStateStorage(directory);
    }

    void add(final SearchState state) {
        storage.addGenerated(state.maximumMoves(), output -> output.accept(state.pair()));
    }

    Inventory seal() {
        return new Inventory(storage.seal());
    }

    boolean contains(final SearchState state) {
        return storage.contains(new CheckerStateStorage.State(state.pair(), state.maximumMoves()));
    }

    private static SearchState convert(final CheckerStateStorage.State state) {
        return new SearchState(state.maximumMoves(), state.pair());
    }

    void forEachBatch(final Consumer<List<SearchState>> consumer) {
        storage.forEachBatch(batch -> consumer.accept(batch.stream().map(CheckerStateSet::convert).toList()));
    }

    void forEachConcurrently(final int workers, final Consumer<SearchState> consumer) {
        storage.forEachConcurrently(workers, state -> consumer.accept(convert(state)));
    }

    void forEachConcurrently(final int workers,
            final Function<List<SearchState>, boolean[]> lookup,
            final Consumer<SearchState> present,
            final Consumer<SearchState> missing) {
        storage.forEachConcurrently(workers,
                batch -> lookup.apply(batch.stream().map(CheckerStateSet::convert).toList()),
                state -> present.accept(convert(state)),
                state -> missing.accept(convert(state)));
    }

    @Override
    public void close() {
        storage.close();
    }
}

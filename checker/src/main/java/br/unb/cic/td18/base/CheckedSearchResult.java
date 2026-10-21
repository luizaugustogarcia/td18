package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.Cycle;

import java.util.List;
import java.util.Objects;

/** Checker-side classification of one bounded sorting obligation. */
public record CheckedSearchResult(Disposition disposition, List<Cycle> moves) {
    public CheckedSearchResult {
        Objects.requireNonNull(disposition);
        moves = List.copyOf(moves);
        if ((disposition == Disposition.CERTIFICATE) != !moves.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a certificate result may contain moves");
        }
    }

    public static CheckedSearchResult triviallySettled() {
        return new CheckedSearchResult(
                Disposition.TRIVIALLY_SETTLED, List.of());
    }

    public static CheckedSearchResult certificate(final List<Cycle> moves) {
        return new CheckedSearchResult(Disposition.CERTIFICATE, moves);
    }

    public static CheckedSearchResult residual() {
        return new CheckedSearchResult(Disposition.RESIDUAL, List.of());
    }

    public enum Disposition {
        TRIVIALLY_SETTLED,
        CERTIFICATE,
        RESIDUAL
    }
}

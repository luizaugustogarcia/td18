package br.unb.cic.td18.table;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** One annotated type and its recorded Table 2 obligations. */
public record TableEntry(int number, int[] partition, boolean[] orientedByPart,
                  int nonfixedSymbols, int fixedSymbols, int movesUsed,
                  int remainingMoves, int threeNorm,
                  Long recordedRealizableClasses,
                  Long remainingClassObligations) {
    public TableEntry {
        partition = partition.clone();
        orientedByPart = orientedByPart.clone();
        if (Arrays.stream(partition).sum() != nonfixedSymbols
                || nonfixedSymbols + fixedSymbols != 19
                || movesUsed + remainingMoves != 10
                || Arrays.stream(partition).map(part -> part / 2).sum() != threeNorm
                || partition.length != orientedByPart.length
                || (remainingClassObligations != null
                && recordedRealizableClasses == null)
                || (remainingClassObligations != null
                && (remainingClassObligations < 0
                || remainingClassObligations > recordedRealizableClasses))
                || Arrays.stream(partition).filter(part -> part % 2 == 0).count() % 2 != 0) {
            throw new IllegalArgumentException("Inconsistent metadata in Table 2 row " + number);
        }
        for (var i = 0; i < partition.length; i++) {
            if (partition[i] < 2 || (partition[i] == 2 && orientedByPart[i])
                    || (i > 0 && (partition[i] > partition[i - 1]
                    || (partition[i] == partition[i - 1] && orientedByPart[i - 1] && !orientedByPart[i])))) {
                throw new IllegalArgumentException("Noncanonical annotation in Table 2 row " + number);
            }
        }
    }

    @Override
    public int[] partition() {
        return partition.clone();
    }

    @Override
    public boolean[] orientedByPart() {
        return orientedByPart.clone();
    }

    public String type() {
        return IntStream.range(0, partition.length)
                .mapToObj(i -> partition[i] + "-" + (orientedByPart[i] ? "O" : "U"))
                .collect(Collectors.joining(",", "(", ")"));
    }
}

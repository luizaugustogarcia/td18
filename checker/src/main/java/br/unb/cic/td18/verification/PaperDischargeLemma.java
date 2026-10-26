package br.unb.cic.td18.verification;

import br.unb.cic.td18.table.TableEntry;
import br.unb.cic.td18.model.RestrictedOddPair;

import java.util.List;
import java.util.Set;

/**
 * Applies the checked TD(16) successor lemma recorded in the SBT paper.
 *
 * <p>The cited source is {@code ../td/docs/paper/main.tex}, Section
 * {@code sec:td16}. Corollary {@code cor:oriented-three-cycles} clears an
 * oriented 3-cycle. The one-fixed-point-successor computation immediately
 * preceding Table {@code tab:td16-avoidance} states that the same 52 searches
 * generated 4,511,223,147 classes and left residuals only in the two types
 * listed below. Thus the other 50 searched types, together with the 52 direct
 * oriented-3 types, are paper-backed {@code Q = 0} discharges. A further 35
 * rows have {@code R = Q = 0}; the checker skips those only after the Burnside
 * count has independently confirmed that the type is empty. This lemma does
 * not turn either residual type into a partial TD(18) row: both starred rows
 * retain {@code Q = R}. Separately, rows 267, 270 and 274 have independently
 * enumerated restricted root-lifting obligations. These are not consequences
 * of the TD(16) paper discharge.</p>
 */
final class PaperDischargeLemma {
    static final String PAPER = "https://arxiv.org/abs/2609.17493";
    static final String LOCAL_SOURCE = "../td/docs/paper/main.tex";
    static final String ORIENTED_THREE_RESULT =
            "cor:oriented-three-cycles";
    static final String SUCCESSOR_RESULT =
            "sec:td16, one-fixed-point-successor computation";
    static final long PAPER_SUCCESSOR_CLASSES = 4_511_223_147L;
    static final long PAPER_SORTED_SUCCESSOR_CLASSES = 4_511_221_111L;
    static final long PAPER_RESIDUAL_SUCCESSOR_CLASSES = 2_036L;
    static final long EXPECTED_TOTAL_Q = 45_498_334_516L;

    private static final Set<String> RESIDUAL_TYPES = Set.of(
            "(11-O,5-O)",
            "(9-O,7-O)");

    private PaperDischargeLemma() {
    }

    record Audit(int orientedThreeRows, int successorSearchRows,
                 int paperSettledSuccessorRows, int retainedResidualRows,
                 int emptyUncoveredRows, int zeroObligationRows, long totalQ) {
    }

    static Audit verify(final List<TableEntry> entries) {
        var orientedThreeRows = 0;
        var successorSearchRows = 0;
        var paperSettledSuccessorRows = 0;
        var retainedResidualRows = 0;
        var emptyUncoveredRows = 0;
        var zeroObligationRows = 0;
        var successorClasses = 0L;
        var totalQ = 0L;
        var restrictedRows = 0;
        var restrictedClasses = 0L;

        for (final var entry : entries) {
            final Long realizable = entry.recordedRealizableClasses();
            final Long recordedQ = entry.remainingClassObligations();
            if (realizable == null || recordedQ == null) {
                throw new IllegalArgumentException(
                        "Table row has no R/Q values: " + entry.number());
            }

            final boolean coveredDomain = entry.nonfixedSymbols() == 16
                    && entry.movesUsed() == 2;
            final boolean orientedThree = coveredDomain
                    && containsOrientedThree(entry);
            final boolean successorSearch = coveredDomain && !orientedThree;
            final boolean retainedResidualType = successorSearch
                    && RESIDUAL_TYPES.contains(entry.type());

            final long expectedQ;
            if (RestrictedOddPair.isObligation(entry.partition(), entry.orientedByPart(), entry.movesUsed())) {
                restrictedRows++;
                // Independently enumerated powers/supports supply this Q;
                // it is a root-lifting obligation, not a paper discharge.
                expectedQ = RestrictedOddPairVerifier.representatives(
                        entry.partition(), entry.orientedByPart()).size();
                restrictedClasses += expectedQ;
            } else if (orientedThree) {
                orientedThreeRows++;
                expectedQ = 0;
            } else if (successorSearch) {
                successorSearchRows++;
                successorClasses = Math.addExact(successorClasses, realizable);
                if (retainedResidualType) {
                    retainedResidualRows++;
                    expectedQ = realizable;
                } else {
                    paperSettledSuccessorRows++;
                    expectedQ = 0;
                }
            } else {
                expectedQ = realizable;
                if (realizable == 0) {
                    emptyUncoveredRows++;
                }
            }

            if (recordedQ != expectedQ) {
                throw new IllegalStateException(
                        "Checked obligation policy gives Q=" + expectedQ
                                + " for Table 2 row " + entry.number()
                                + ", but the table records " + recordedQ);
            }
            if (recordedQ == 0) {
                zeroObligationRows++;
            }
            totalQ = Math.addExact(totalQ, recordedQ);
        }

        if (orientedThreeRows != 52 || successorSearchRows != 52
                || paperSettledSuccessorRows != 50
                || retainedResidualRows != 2 || emptyUncoveredRows != 35
                || zeroObligationRows != 137
                || restrictedRows != 3 || restrictedClasses != 1_884
                || successorClasses != PAPER_SUCCESSOR_CLASSES
                || PAPER_SUCCESSOR_CLASSES
                - PAPER_SORTED_SUCCESSOR_CLASSES
                != PAPER_RESIDUAL_SUCCESSOR_CLASSES
                || totalQ != EXPECTED_TOTAL_Q) {
            throw new IllegalStateException(
                    "Table 2 does not match the checked Q partition: "
                            + "orientedThree=" + orientedThreeRows
                            + ", successorSearch=" + successorSearchRows
                            + ", settledSuccessors="
                            + paperSettledSuccessorRows
                            + ", retainedResidualTypes="
                            + retainedResidualRows
                            + ", emptyUncovered=" + emptyUncoveredRows
                            + ", Q0=" + zeroObligationRows
                            + ", successorClasses=" + successorClasses
                            + ", totalQ=" + totalQ);
        }

        return new Audit(orientedThreeRows, successorSearchRows,
                paperSettledSuccessorRows, retainedResidualRows,
                emptyUncoveredRows, zeroObligationRows, totalQ);
    }

    private static boolean containsOrientedThree(
            final TableEntry entry
    ) {
        final int[] partition = entry.partition();
        final boolean[] oriented = entry.orientedByPart();
        for (var index = 0; index < partition.length; index++) {
            if (partition[index] == 3 && oriented[index]) {
                return true;
            }
        }
        return false;
    }
}

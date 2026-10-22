package br.unb.cic.td18;

import br.unb.cic.td18.base.AvoidancePhaseManifest;
import br.unb.cic.td18.base.AvoidanceSearchMiss;
import br.unb.cic.td18.base.CertificateCodec;
import br.unb.cic.td18.base.CertificateRowManifest;
import br.unb.cic.td18.base.CertificateStore;
import br.unb.cic.td18.base.CheckedSearchResult;
import br.unb.cic.td18.base.CommonOperations;
import br.unb.cic.td18.base.CyclicTargetPair;
import br.unb.cic.td18.base.ResidualObligation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.CRC32C;

/** CPU-only checker for the versioned TD(18) certificate database. */
public final class CertificateChecker {
    static final long EXPECTED_INVENTORY_CRC32C = 2_232_227_918L;

    private CertificateChecker() {
    }

    public static void main(final String[] arguments) throws IOException {
        final var root = CountTD18Bracelets.projectRoot(Path.of(""));
        final var options = Options.parse(arguments, root);
        if (options.help()) {
            printUsage();
            return;
        }

        final var started = Instant.now();
        final var entries = CountTD18Bracelets.readTableEntries(
                Files.readString(options.table(), StandardCharsets.UTF_8));
        final long inventoryChecksum = verifyInventory(entries);
        final var paperAudit = PaperDischargeLemma.verify(entries);
        reportStructuralChecks(inventoryChecksum, paperAudit);
        final var orientedNineteenSurvivors =
                verifyOrientedNineteenClassification();
        if (options.structuralOnly()) {
            return;
        }

        final var residuals = new ConcurrentLinkedQueue<ResidualObligation>();
        try (final var certificates = new CertificateStore(options.database())) {
            requireOrientedNineteenCertificates(
                    certificates, orientedNineteenSurvivors);
            verifyRows(entries, certificates, residuals);
            verifyAvoidance(certificates, residuals);
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }

        System.out.printf(
                "TD(18) certificate verified: rows=%d, Q=%d, "
                        + "paper=%s, elapsed=%s%n",
                entries.size(), paperAudit.totalQ(),
                PaperDischargeLemma.PAPER,
                Duration.between(started, Instant.now()));
    }

    static long verifyInventory(
            final List<CountTD18Bracelets.TableEntry> table
    ) {
        final var inventory = IndependentTypeEnumerator.enumerate();
        if (inventory.rootPartitions() != 53
                || inventory.annotatedRoots() != 360
                || inventory.entries().size() != 204
                || table.size() != inventory.entries().size()) {
            throw new IllegalStateException(
                    "Independent inventory mismatch: partitions="
                            + inventory.rootPartitions()
                            + ", annotatedRoots=" + inventory.annotatedRoots()
                            + ", generatedRows=" + inventory.entries().size()
                            + ", tableRows=" + table.size());
        }

        final var depthCounts = new int[3];
        final var checksum = new CRC32C();
        for (var index = 0; index < table.size(); index++) {
            final var expected = inventory.entries().get(index);
            final var actual = table.get(index);
            if (actual.number() != index + 1
                    || !actual.type().equals(expected.type())
                    || actual.nonfixedSymbols() != expected.nonfixedSymbols()
                    || actual.fixedSymbols() != expected.fixedSymbols()
                    || actual.movesUsed() != expected.movesUsed()
                    || actual.remainingMoves() != expected.budget()
                    || actual.threeNorm() != expected.threeNorm()) {
                throw new IllegalStateException(
                        "Independent inventory disagrees with Table 2 row "
                                + (index + 1) + ": generated=" + expected
                                + ", table=" + actual);
            }
            depthCounts[expected.movesUsed()]++;
            checksum.update(inventoryLine(actual)
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (depthCounts[0] != 8 || depthCounts[1] != 8
                || depthCounts[2] != 188) {
            throw new IllegalStateException(
                    "Independent depth distribution is not 8, 8, 188");
        }
        final long value = checksum.getValue();
        if (value != EXPECTED_INVENTORY_CRC32C) {
            throw new IllegalStateException(
                    "Table 2 inventory checksum changed: expected "
                            + EXPECTED_INVENTORY_CRC32C + ", found " + value);
        }
        return value;
    }

    private static String inventoryLine(
            final CountTD18Bracelets.TableEntry entry
    ) {
        return entry.number() + "|" + entry.type() + "|"
                + entry.nonfixedSymbols() + "|" + entry.fixedSymbols() + "|"
                + entry.movesUsed() + "|" + entry.remainingMoves() + "|"
                + entry.threeNorm() + "|"
                + entry.recordedRealizableClasses() + "|"
                + entry.remainingClassObligations() + "\n";
    }

    private static void reportStructuralChecks(
            final long inventoryChecksum,
            final PaperDischargeLemma.Audit audit
    ) {
        System.out.printf(
                "inventory verified: rows=204, roots=53/360, "
                        + "depths=8/8/188, crc32c=%d%n",
                inventoryChecksum);
        System.out.printf(
                "Q lemma verified from %s: oriented-3=%d, "
                + "TD(16)-successor=%d (%d settled, %d retained), "
                        + "empty=%d, literal-Q0=%d, totalQ=%d%n",
                PaperDischargeLemma.PAPER, audit.orientedThreeRows(),
                audit.successorSearchRows(),
                audit.paperSettledSuccessorRows(),
                audit.retainedResidualRows(), audit.emptyUncoveredRows(),
                audit.zeroObligationRows(),
                audit.totalQ());
    }

    private static List<CyclicTargetPair>
    verifyOrientedNineteenClassification() {
        final var classification =
                OrientedNineteenCycleClassification.classify();
        final var survivors =
                OrientedNineteenCycleClassification.expectedOrientedPairs();
        if (classification.visitedNodes()
                != OrientedNineteenCycleClassification.EXPECTED_VISITED_NODES
                || classification.retainedCycles() != 3
                || classification.unorientedCycles() != 1
                || classification.orientedPairs().size() != 2
                || survivors.size() != 2
                || !classification.orientedPairs().stream()
                .map(CyclicTargetPair::extendedToricClassKey)
                .toList().equals(survivors.stream()
                        .map(CyclicTargetPair::extendedToricClassKey)
                        .toList())) {
            throw new IllegalStateException(
                    "Oriented-19 DFS does not reproduce the configured "
                            + "two survivor classes: " + classification);
        }
        System.out.printf(
                "oriented 19-cycle DFS verified: nodes=%d, retained=3, "
                        + "unoriented=1, survivors=2%n",
                classification.visitedNodes());
        return survivors;
    }

    private static void requireOrientedNineteenCertificates(
            final CertificateStore certificates,
            final List<CyclicTargetPair> survivors
    ) {
        for (final var survivor : survivors) {
            certificates.require(survivor, 10);
        }
        System.out.println(
                "oriented 19-cycle survivor certificates verified: 2");
    }

    private static void verifyRows(
            final List<CountTD18Bracelets.TableEntry> entries,
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residuals
    ) {
        long verifiedR = 0;
        long verifiedQ = 0;
        for (final var entry : entries) {
            final long counted = DirectTwistedBraceletGenerator
                    .countRepresentativesByBurnside(
                            entry.partition(), entry.orientedByPart());
            if (counted != entry.recordedRealizableClasses()) {
                throw new IllegalStateException(
                        "Table 2 row " + entry.number() + " records R="
                                + entry.recordedRealizableClasses()
                                + " but the checked count is " + counted);
            }
            verifiedR = Math.addExact(verifiedR, counted);

            final var result = verifyRow(entry, certificates, residuals);
            final var expectedManifest = new CertificateRowManifest(
                    entry.number(), entry.type(), entry.remainingMoves(),
                    entry.recordedRealizableClasses(),
                    entry.remainingClassObligations(), result.generated(),
                    result.trivialClosures(), result.certificates(),
                    result.residuals(), result.checksum(),
                    result.residuals() == 0);
            final var actualManifest = certificates.requireRowManifest(
                    entry.number());
            if (!actualManifest.equals(expectedManifest)) {
                throw new IllegalStateException(
                        "Certificate manifest mismatch in Table 2 row "
                                + entry.number() + "; expected="
                                + expectedManifest + ", actual="
                                + actualManifest);
            }
            verifiedQ = Math.addExact(verifiedQ, result.generated());
            System.out.printf(
                    "row %d verified: R=%d Q=%d trivial=%d "
                            + "certificates=%d residuals=%d%n",
                    entry.number(), counted, result.generated(),
                    result.trivialClosures(), result.certificates(),
                    result.residuals());
        }
        if (verifiedR != 33_544_626_415L
                || verifiedQ != PaperDischargeLemma.EXPECTED_TOTAL_Q) {
            throw new IllegalStateException(
                    "Table totals disagree: R=" + verifiedR
                            + ", Q=" + verifiedQ);
        }
    }

    private static RowResult verifyRow(
            final CountTD18Bracelets.TableEntry entry,
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residualSink
    ) {
        if (entry.remainingClassObligations() == 0) {
            return RowResult.EMPTY;
        }

        final var generated = new LongAdder();
        final var trivial = new LongAdder();
        final var certified = new LongAdder();
        final var residual = new LongAdder();
        final var checksum = new LongAdder();
        DirectTwistedBraceletGenerator
                .generateRepresentativeBatchesConcurrently(
                        entry.partition(), entry.orientedByPart(), () -> { },
                        pairs -> verifyBatch(entry, pairs, certificates,
                                residualSink, generated, trivial, certified,
                                residual, checksum));

        final var result = new RowResult(generated.sum(), trivial.sum(),
                certified.sum(), residual.sum(), checksum.sum());
        if (result.generated() != entry.remainingClassObligations()) {
            throw new IllegalStateException(
                    "Table 2 row " + entry.number() + " records Q="
                            + entry.remainingClassObligations()
                            + " but the checker generated "
                            + result.generated());
        }
        return result;
    }

    private static void verifyBatch(
            final CountTD18Bracelets.TableEntry entry,
            final CyclicTargetPair[] pairs,
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residualSink,
            final LongAdder generated,
            final LongAdder trivial,
            final LongAdder certified,
            final LongAdder residual,
            final LongAdder checksum
    ) {
        generated.add(pairs.length);
        final var databasePairs = new ArrayList<CyclicTargetPair>(pairs.length);
        for (final var pair : pairs) {
            if (cpuClosure(pair, entry.remainingMoves())) {
                trivial.increment();
            } else {
                databasePairs.add(pair);
            }
        }
        final var queried = databasePairs.toArray(CyclicTargetPair[]::new);
        if (queried.length == 0) {
            return;
        }
        final var witnesses = certificates.find(queried, entry.remainingMoves());
        for (var index = 0; index < queried.length; index++) {
            final var pair = queried[index];
            final var moves = witnesses.get(index);
            if (moves.isPresent()) {
                certified.increment();
                checksum.add(CertificateCodec.checksumContribution(
                        pair, moves.orElseThrow()));
                continue;
            }

            final var expectedResidual = new ResidualObligation(
                    entry.number(), entry.remainingMoves(), pair);
            final var storedResidual = certificates.findResidual(
                    expectedResidual).orElseThrow(() ->
                    new IllegalStateException(
                            "Missing certificate or residual for row "
                                    + entry.number() + ", classKey="
                                    + pair.extendedToricClassKey()));
            residualSink.add(storedResidual);
            residual.increment();
            checksum.add(storedResidual.checksumContribution());
        }
    }

    private static boolean cpuClosure(final CyclicTargetPair pair,
                                      final int maximumMoves) {
        final byte[] omega = pair.getOmega().getOneLineNotation();
        final byte[] beta = betaBytes(pair);
        final var prefix = CommonOperations.searchForSorting(
                omega, beta, maximumMoves);
        if (prefix.isEmpty()) {
            return false;
        }
        validateCpuClosure(omega, beta, prefix.orElseThrow(), maximumMoves);
        return true;
    }

    /** Independently replays and classifies a non-certificate CPU prefix. */
    private static void validateCpuClosure(final byte[] initialOmega,
                                           final byte[] initialBeta,
                                           final List<int[]> prefix,
                                           final int maximumMoves) {
        if (initialOmega.length != initialBeta.length || prefix.isEmpty()
                || prefix.size() > 3) {
            throw new IllegalStateException("Malformed CPU prefix closure");
        }
        final byte[] omega = initialOmega.clone();
        byte[] beta = initialBeta.clone();
        final int initialFixedPoints = countFixedPoints(omega);
        for (final var move : prefix) {
            if (move.length != 3) {
                throw new IllegalStateException(
                        "A CPU prefix move must have three symbols");
            }
            final int first = move[0];
            final int second = move[1];
            final int third = move[2];
            final int firstPosition = indexOf(beta, first);
            final int secondPosition = indexOf(beta, second);
            final int thirdPosition = indexOf(beta, third);
            if (first < 0 || first >= omega.length
                    || second < 0 || second >= omega.length
                    || third < 0 || third >= omega.length
                    || first == second || first == third || second == third
                    || !(firstPosition < secondPosition
                    && secondPosition < thirdPosition)
                    || Byte.toUnsignedInt(omega[first]) == first
                    || Byte.toUnsignedInt(omega[second]) == second
                    || Byte.toUnsignedInt(omega[third]) == third) {
                throw new IllegalStateException(
                        "CPU prefix contains an inapplicable move");
            }

            final byte imageFirst = omega[first];
            final byte imageSecond = omega[second];
            final byte imageThird = omega[third];
            omega[first] = imageThird;
            omega[second] = imageFirst;
            omega[third] = imageSecond;
            beta = applyTransposition(beta, firstPosition, secondPosition,
                    thirdPosition);
        }

        final int fixedPoints = countFixedPoints(omega);
        final int gain = fixedPoints - initialFixedPoints;
        final int remainingSymbols = omega.length - fixedPoints;
        final int diameter = ReductionTypeEnumerator.knownDiameter(
                remainingSymbols);
        if (5 * gain < 8 * prefix.size()
                || diameter == Integer.MAX_VALUE
                || prefix.size() + diameter > maximumMoves) {
            throw new IllegalStateException(
                    "CPU prefix does not satisfy the paper closure bound");
        }
    }

    private static int countFixedPoints(final byte[] omega) {
        var result = 0;
        for (var symbol = 0; symbol < omega.length; symbol++) {
            if (Byte.toUnsignedInt(omega[symbol]) == symbol) {
                result++;
            }
        }
        return result;
    }

    private static int indexOf(final byte[] permutation, final int symbol) {
        if (symbol < 0 || symbol >= permutation.length) {
            return -1;
        }
        for (var index = 0; index < permutation.length; index++) {
            if (Byte.toUnsignedInt(permutation[index]) == symbol) {
                return index;
            }
        }
        return -1;
    }

    private static byte[] applyTransposition(final byte[] beta,
                                             final int first,
                                             final int second,
                                             final int third) {
        final byte[] result = beta.clone();
        System.arraycopy(beta, second, result, first, third - second);
        System.arraycopy(beta, first, result, first + third - second,
                second - first);
        return result;
    }

    private static byte[] betaBytes(final CyclicTargetPair pair) {
        final var beta = pair.getBeta();
        final var result = new byte[beta.size()];
        for (var index = 0; index < beta.size(); index++) {
            result[index] = (byte) beta.get(index);
        }
        return result;
    }

    private static void verifyAvoidance(
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residuals
    ) {
        final var manifests = new ArrayList<>(
                certificates.avoidanceManifests());
        manifests.sort(Comparator.comparingInt(
                AvoidancePhaseManifest::firstRow));
        var expectedFirstRow = 1;
        for (final var manifest : manifests) {
            if (manifest.firstRow() != expectedFirstRow) {
                throw new IllegalStateException(
                        "Avoidance manifest ranges overlap or leave a gap at row "
                                + expectedFirstRow);
            }
            expectedFirstRow = manifest.lastRow() + 1;
        }
        if (manifests.isEmpty() || expectedFirstRow != 205) {
            throw new IllegalStateException(
                    "Avoidance manifests do not cover Table 2 rows 1-204");
        }

        for (final var manifest : manifests) {
            final List<ResidualObligation> rangeResiduals = residuals.stream()
                    .filter(item -> item.row() >= manifest.firstRow()
                            && item.row() <= manifest.lastRow())
                    .toList();
            final var checked = ObstructionAvoidance.resolve(
                    rangeResiduals,
                    (pair, rate, maximumMoves, fullDomain) -> {
                        if (cpuClosure(pair, maximumMoves)) {
                                return CheckedSearchResult
                                    .triviallySettled();
                        }
                        final var moves = fullDomain
                                ? certificates.find(pair, maximumMoves)
                                : certificates.findIntermediate(
                                pair, maximumMoves);
                        return moves.isPresent()
                                ? CheckedSearchResult.certificate(
                                moves.orElseThrow())
                                : CheckedSearchResult.residual();
                    },
                    miss -> requireAvoidanceMiss(certificates, miss));

            final var expected = new AvoidancePhaseManifest(
                    manifest.firstRow(), manifest.lastRow(),
                    checked.terminalResiduals(),
                    checked.reconstructionApplications(),
                    checked.intermediateSearchObligations(),
                    checked.propagatedResiduals(),
                    checked.rootSearchObligations(),
                    checked.trivialClosures(), checked.certificates(),
                    checked.unresolved(), checked.checksum(),
                    checked.complete());
            if (!manifest.equals(expected)) {
                throw new IllegalStateException(
                        "Avoidance manifest mismatch for rows "
                                + manifest.firstRow() + '-' + manifest.lastRow()
                                + "; expected=" + expected + ", actual="
                                + manifest);
            }
            if (!checked.complete()) {
                throw new IllegalStateException(
                        "Unresolved avoidance obligations remain for rows "
                                + manifest.firstRow() + '-' + manifest.lastRow());
            }
        }
    }

    private static void requireAvoidanceMiss(
            final CertificateStore certificates,
            final AvoidanceSearchMiss miss
    ) {
        if (!certificates.hasAvoidanceMiss(miss)) {
            throw new IllegalStateException(
                    "Missing persisted avoidance miss: classKey="
                            + miss.pair().extendedToricClassKey());
        }
    }

    private static void printUsage() {
        System.out.println("CertificateChecker [--table=path] "
                + "[--database=path] [--structural-only]");
    }

    private record RowResult(long generated, long trivialClosures,
                             long certificates, long residuals,
                             long checksum) {
        private static final RowResult EMPTY =
                new RowResult(0, 0, 0, 0, 0);

        private RowResult {
            if (generated < 0 || trivialClosures < 0 || certificates < 0
                    || residuals < 0
                    || trivialClosures + certificates + residuals
                    != generated) {
                throw new IllegalArgumentException(
                        "Inconsistent checked row result");
            }
        }
    }

    private record Options(Path table, Path database,
                           boolean structuralOnly, boolean help) {
        private static Options parse(final String[] arguments,
                                     final Path root) {
            var table = root.resolve("docs/td18-reduction-count.tex");
            var database = Path.of(System.getProperty(
                    "td18.certificateDb",
                    root.resolve("certificates").toString()));
            var structuralOnly = false;
            var help = false;
            for (final var argument : arguments) {
                if (argument.equals("--help")) {
                    help = true;
                } else if (argument.equals("--structural-only")) {
                    structuralOnly = true;
                } else if (argument.startsWith("--table=")) {
                    table = Path.of(argument.substring(
                            "--table=".length())).toAbsolutePath();
                } else if (argument.startsWith("--database=")) {
                    database = Path.of(argument.substring(
                            "--database=".length())).toAbsolutePath();
                } else {
                    throw new IllegalArgumentException(
                            "Unknown argument: " + argument);
                }
            }
            return new Options(table.toAbsolutePath(),
                    database.toAbsolutePath(), structuralOnly, help);
        }
    }
}

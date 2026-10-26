package br.unb.cic.td18.verification;

import br.unb.cic.td18.avoidance.ObstructionAvoidance;
import br.unb.cic.td18.avoidance.ResidualObligation;
import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.CertificateCodec;
import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.model.RestrictedOddPair;
import br.unb.cic.td18.reduction.ReductionTypeEnumerator;
import br.unb.cic.td18.runtime.ProjectPaths;
import br.unb.cic.td18.search.CheckedSearchResult;
import br.unb.cic.td18.search.CommonOperations;
import br.unb.cic.td18.table.Table2;
import br.unb.cic.td18.table.TableEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** CPU-only checker for the versioned TD(18) certificate database. */
public final class CertificateChecker {
    private CertificateChecker() {
    }

    public static void main(final String[] arguments) throws IOException {
        final var root = ProjectPaths.root(Path.of(""));
        final var options = Options.parse(arguments, root);
        if (options.help()) {
            printUsage();
            return;
        }

        final var started = Instant.now();
        try (final var progress = new VerificationProgress()) {
            progress.phase("CPU-only structural verification; certificate=" + options.database());
            final var entries = Table2.read(options.table());
            verifyInventory(entries);
            System.out.printf("reduction policy independently verified: reachedStates=%d%n",
                    IndependentTypeEnumerator.verifyReductionPolicy());
            progress.phase("independent restricted odd-pair classification and support enumeration");
            RestrictedOddPairVerifier.verifyInventory();
            final var paperAudit = PaperDischargeLemma.verify(entries);
            reportStructuralChecks(paperAudit);
            progress.phase("CPU-only oriented 19-cycle classification");
            final var orientedNineteenSurvivors =
                    verifyOrientedNineteenClassification();
            ExceptionalNineCycleVerifier.verifyInventory();
            ExceptionalFifteenCycleVerifier.verifyInventory();
            if (options.structuralOnly()) {
                return;
            }

            final var residuals = new ConcurrentLinkedQueue<ResidualObligation>();
            try (final var certificates = new CertificateStore(options.database())) {
                if (options.checksExceptionalNine()) {
                    ExceptionalNineCycleVerifier.verify(certificates, options.nineType(), progress);
                }
                if (options.checksExceptionalFifteen()) {
                    ExceptionalFifteenCycleVerifier.verify(certificates, options.fifteenType(), progress);
                }
                final var missingSurvivors = options.checksSurvivors()
                        ? checkOrientedNineteenCertificates(certificates, orientedNineteenSurvivors)
                        : List.<String>of();
                final var selected = entries.stream().filter(entry -> options.includes(entry.number())).toList();
                if (options.checksRows()) {
                    verifyRows(selected, certificates, residuals, progress);
                } else if (options.checksAvoidance()) {
                    residuals.addAll(loadTerminalResiduals(entries, certificates, options, progress));
                }
                if (options.checksAvoidance()) {
                    verifyAvoidance(certificates, residuals, options.work(), progress);
                }
                if (!missingSurvivors.isEmpty()) {
                    throw new IllegalStateException("Certificate verification failed: missing "
                            + missingSurvivors.size() + " oriented 19-cycle survivor witnesses: "
                            + missingSurvivors);
                }
            } finally {
                DirectTwistedBraceletGenerator.shutdownExecutor();
            }

            System.out.printf(
                    "certificate checks complete: phase=%s rows=%d-%d elapsed=%s%n",
                    options.phase(), options.firstRow(), options.lastRow(),
                    Duration.between(started, Instant.now()));
        }
    }

    static void verifyInventory(
            final List<TableEntry> table
    ) {
        final var inventory = IndependentTypeEnumerator.enumerate();
        if (inventory.rootPartitions() != 53
                || inventory.annotatedRoots() != 360
                || inventory.entries().size() != Table2.EXPECTED_ENTRIES
                || table.size() != inventory.entries().size()) {
            throw new IllegalStateException(
                    "Independent inventory mismatch: partitions="
                            + inventory.rootPartitions()
                            + ", annotatedRoots=" + inventory.annotatedRoots()
                            + ", generatedRows=" + inventory.entries().size()
                            + ", tableRows=" + table.size());
        }

        final var depthCounts = new int[3];
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
        }
        if (depthCounts[0] != 59 || depthCounts[1] != 45
                || depthCounts[2] != 188) {
            throw new IllegalStateException(
                    "Independent depth distribution is not 59, 45, 188");
        }
    }

    private static void reportStructuralChecks(
            final PaperDischargeLemma.Audit audit
    ) {
        System.out.printf(
                "inventory verified: rows=292, roots=53/360, "
                        + "depths=59/45/188%n");
        System.out.printf(
                "Q partition verified: paper=%s oriented-3=%d, "
                + "TD(16)-successor=%d (%d settled, %d retained), "
                        + "empty=%d, literal-Q0=%d, restrictedRows=3 restrictedSeeds=1884 totalQ=%d%n",
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

    static List<String> checkOrientedNineteenCertificates(
            final CertificateStore certificates,
            final List<CyclicTargetPair> survivors
    ) {
        final var missing = new ArrayList<String>();
        for (final var survivor : survivors) {
            if (certificates.find(survivor, 10).isEmpty()) {
                final var key = java.util.HexFormat.of().formatHex(CertificateCodec.keyFor(survivor));
                missing.add(key);
                System.err.printf("VERIFICATION FAILURE: missing oriented 19-cycle witness: "
                        + "key=%s maximumMoves=10 pair=%s%n", key, survivor);
            }
        }
        System.out.printf("oriented 19-cycle survivor certificates: verified=%d missing=%d%n",
                survivors.size() - missing.size(), missing.size());
        return List.copyOf(missing);
    }

    static void verifyRows(
            final List<TableEntry> entries,
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residuals,
            final VerificationProgress progress
    ) {
        long verifiedR = 0;
        long verifiedQ = 0;
        for (final var entry : entries) {
            progress.phase("row " + entry.number() + "/" + Table2.EXPECTED_ENTRIES
                    + " Burnside count: " + entry.type());
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

            final var result = verifyRow(entry, certificates, residuals, progress);
            verifiedQ = Math.addExact(verifiedQ, result.generated());
            System.out.printf(
                    "row %d verified: R=%d Q=%d trivial=%d "
                            + "certificates=%d residuals=%d%n",
                    entry.number(), counted, result.generated(),
                    result.trivialClosures(), result.certificates(),
                    result.residuals());
        }
        if (entries.size() == Table2.EXPECTED_ENTRIES && (verifiedR != 692_894_074_401L
                || verifiedQ != PaperDischargeLemma.EXPECTED_TOTAL_Q)) {
            throw new IllegalStateException(
                    "Table totals disagree: R=" + verifiedR
                            + ", Q=" + verifiedQ);
        }
    }

    static RowResult verifyRow(
            final TableEntry entry,
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residualSink,
            final VerificationProgress progress
    ) {
        if (RestrictedOddPair.isObligation(entry.partition(), entry.orientedByPart(), entry.movesUsed())) {
            final var seeds = RestrictedOddPairVerifier.representatives(
                    entry.partition(), entry.orientedByPart());
            if (seeds.size() != entry.remainingClassObligations()) {
                throw new IllegalStateException("Restricted row Q disagrees with independent enumeration");
            }
            for (final var pair : seeds) {
                residualSink.add(new ResidualObligation(entry.number(), entry.remainingMoves(), pair));
            }
            // This branch has no nine-move premise and requires no persisted
            // seed marker. Every root is reconstructed and checked afterwards.
            return new RowResult(seeds.size(), 0, 0, seeds.size());
        }
        if (entry.remainingClassObligations() == 0) {
            return RowResult.EMPTY;
        }

        final var generated = new LongAdder();
        final var trivial = new LongAdder();
        final var certified = new LongAdder();
        final var residual = new LongAdder();
        final long rowStarted = System.nanoTime();
        progress.phase(() -> String.format(java.util.Locale.ROOT,
                "row %d/%d %s checked=%d/%d trivial=%d witnesses=%d residuals=%d rate=%.1f/s",
                entry.number(), Table2.EXPECTED_ENTRIES, entry.type(),
                trivial.sum() + certified.sum() + residual.sum(),
                entry.remainingClassObligations(), trivial.sum(), certified.sum(), residual.sum(),
                (trivial.sum() + certified.sum() + residual.sum())
                        / Math.max(1e-9, (System.nanoTime() - rowStarted) / 1e9)));
        DirectTwistedBraceletGenerator
                .generateRepresentativeBatchesConcurrently(
                        entry.partition(), entry.orientedByPart(), () -> { },
                        pairs -> verifyBatch(entry, pairs, certificates,
                                residualSink, generated, trivial, certified,
                                residual));

        final var result = new RowResult(generated.sum(), trivial.sum(),
                certified.sum(), residual.sum());
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
            final TableEntry entry,
            final CyclicTargetPair[] pairs,
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residualSink,
            final LongAdder generated,
            final LongAdder trivial,
            final LongAdder certified,
            final LongAdder residual
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
        }
    }

    static boolean cpuClosure(final CyclicTargetPair pair,
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

    static List<ResidualObligation> loadTerminalResiduals(
            final List<TableEntry> entries,
            final CertificateStore certificates,
            final Options options,
            final VerificationProgress progress
    ) {
        final var contexts = new java.util.HashMap<String, TableEntry>();
        final var domains = new java.util.TreeSet<Integer>();
        for (final var entry : entries) {
            if (entry.remainingClassObligations() > 0) {
                contexts.put(entry.type(), entry);
                domains.add(entry.nonfixedSymbols());
            }
        }
        final var result = new java.util.LinkedHashMap<String, ResidualObligation>();
        final var scanned = new LongAdder();
        final var selected = new LongAdder();
        progress.phase(() -> "loading residual obstructions: scanned=" + scanned.sum()
                + " selected=" + selected.sum());
        certificates.forEachObstruction(key -> {
            CyclicTargetPair rootMiss = null;
            ResidualObligation recovered = null;
            boolean obsoleteRestricted = false;
            for (final int domain : domains) {
                if (key.length != (5 * domain + 7) / 8) {
                    continue;
                }
                final CyclicTargetPair pair;
                try {
                    pair = CertificateCodec.pairForKey(key, domain);
                } catch (final IllegalArgumentException | IllegalStateException invalidDomain) {
                    continue;
                }
                final var entry = contexts.get(ObstructionAvoidance.annotatedTypeOf(pair));
                if (entry != null && entry.nonfixedSymbols() == domain) {
                    if (!java.util.Arrays.equals(key,
                            CertificateCodec.keyFor(pair.canonicalRepresentative()))) {
                        throw new IllegalStateException("Noncanonical terminal obstruction key");
                    }
                    if (RestrictedOddPair.isObligation(entry.partition(), entry.orientedByPart(),
                            entry.movesUsed()) && !RestrictedOddPair.contains(pair)) {
                        // Old full-row misses with an available qualifying move belong
                        // to the preserved child branches in the active route.
                        obsoleteRestricted = true;
                        continue;
                    }
                    if (recovered != null) {
                        throw new IllegalStateException("Ambiguous residual context");
                    }
                    recovered = new ResidualObligation(entry.number(), entry.remainingMoves(), pair);
                    ObstructionAvoidance.validateTerminalResidual(recovered);
                } else if (domain == 19) {
                    rootMiss = pair;
                }
            }
            if (recovered == null && rootMiss == null && !obsoleteRestricted) {
                throw new IllegalStateException("Obstruction has no valid Table 2 or root context: "
                        + java.util.HexFormat.of().formatHex(key));
            }
            if (recovered != null && options.includes(recovered.row())) {
                result.put(residualKey(recovered), recovered);
                selected.increment();
            }
            scanned.increment();
        });
        for (final var entry : entries) {
            if (options.includes(entry.number()) && RestrictedOddPair.isObligation(
                    entry.partition(), entry.orientedByPart(), entry.movesUsed())) {
                final var seeds = RestrictedOddPairVerifier.representatives(
                        entry.partition(), entry.orientedByPart());
                if (seeds.size() != entry.remainingClassObligations()) {
                    throw new IllegalStateException("Restricted row Q disagrees with regenerated seeds");
                }
                for (final var pair : seeds) {
                    final var seed = new ResidualObligation(entry.number(), entry.remainingMoves(), pair);
                    result.put(residualKey(seed), seed);
                }
            }
        }
        System.out.printf("residual obstructions loaded and restricted seeds regenerated: "
                        + "scanned=%d selected=%d rows=%d-%d%n",
                scanned.sum(), result.size(), options.firstRow(), options.lastRow());
        return List.copyOf(result.values());
    }

    private static String residualKey(final ResidualObligation residual) {
        return residual.row() + ":" + residual.maximumMoves() + ":"
                + java.util.HexFormat.of().formatHex(residual.key());
    }

    static void verifyAvoidance(
            final CertificateStore certificates,
            final ConcurrentLinkedQueue<ResidualObligation> residuals,
            final Path work,
            final VerificationProgress progress
    ) {
        final var checked = DiskObstructionVerifier.resolve(
                List.copyOf(residuals),
                (pair, rate, maximumMoves, fullDomain) -> {
                    if (cpuClosure(pair, maximumMoves)) {
                        return CheckedSearchResult.triviallySettled();
                    }
                    if (!fullDomain) {
                        return CheckedSearchResult.residual();
                    }
                    final var moves = certificates.find(pair, maximumMoves);
                    return moves.isPresent()
                            ? CheckedSearchResult.certificate(moves.orElseThrow())
                            : CheckedSearchResult.residual();
                }, miss -> { },
                work.resolve("avoidance"), progress);
        System.out.printf("avoidance checked: terminalResiduals=%d intermediate=%d "
                        + "propagatedMisses=%d roots=%d witnesses=%d unresolved=%d%n",
                checked.terminalResiduals(), checked.intermediatePrefixObligations(),
                checked.propagatedPrefixMisses(), checked.rootSearchObligations(),
                checked.certificates(), checked.unresolved());
        if (!checked.complete()) {
            throw new IllegalStateException("Unresolved avoidance obligations: " + checked.unresolved());
        }
    }

    private static void printUsage() {
        System.out.println("CertificateChecker [--table=path] "
                + "[--database=path] [--work=path] [--rows=first-last] "
                + "[--phase=all|rows|avoidance|survivors|exceptional-nine|exceptional-fifteen] "
                + "[--nine-type=9O-...] [--fifteen-type=15O-...@depth] [--structural-only]");
    }

    record RowResult(long generated, long trivialClosures,
                             long certificates, long residuals) {
        private static final RowResult EMPTY =
                new RowResult(0, 0, 0, 0);

        RowResult {
            if (generated < 0 || trivialClosures < 0 || certificates < 0
                    || residuals < 0
                    || trivialClosures + certificates + residuals
                    != generated) {
                throw new IllegalArgumentException(
                        "Inconsistent checked row result");
            }
        }
    }

    record Options(Path table, Path database, Path work, int firstRow, int lastRow,
                   String phase, String nineType, String fifteenType, boolean structuralOnly, boolean help) {
        static Options parse(final String[] arguments,
                                     final Path root) throws IOException {
            var table = root.resolve("docs/td18-reduction-count.tex");
            var database = Path.of(System.getProperty(
                    "td18.certificateDb",
                    root.resolve("certificates").toString()));
            var work = root.resolve("checker/target/verification-work-"
                    + ProcessHandle.current().pid() + "-" + System.currentTimeMillis());
            var firstRow = 1;
            var lastRow = Table2.EXPECTED_ENTRIES;
            var phase = "all";
            String nineType = null;
            String fifteenType = null;
            var structuralOnly = false;
            var help = false;
            for (final var argument : arguments) {
                if (argument.equals("--help")) {
                    help = true;
                } else if (argument.equals("--structural-only")) {
                    structuralOnly = true;
                } else if (argument.startsWith("--rows=")) {
                    final var bounds = argument.substring("--rows=".length()).split("-", -1);
                    if (bounds.length != 2) {
                        throw new IllegalArgumentException("Expected --rows=first-last");
                    }
                    firstRow = Integer.parseInt(bounds[0]);
                    lastRow = Integer.parseInt(bounds[1]);
                } else if (argument.startsWith("--nine-type=")) {
                    nineType = argument.substring("--nine-type=".length());
                } else if (argument.startsWith("--fifteen-type=")) {
                    fifteenType = argument.substring("--fifteen-type=".length());
                } else if (argument.startsWith("--phase=")) {
                    phase = argument.substring("--phase=".length());
                } else if (argument.startsWith("--table=")) {
                    table = Path.of(argument.substring(
                            "--table=".length())).toAbsolutePath();
                } else if (argument.startsWith("--work=")) {
                    work = Path.of(argument.substring("--work=".length())).toAbsolutePath();
                } else if (argument.startsWith("--database=")) {
                    database = Path.of(argument.substring(
                            "--database=".length())).toAbsolutePath();
                } else {
                    throw new IllegalArgumentException(
                            "Unknown argument: " + argument);
                }
            }
            if (firstRow < 1 || lastRow < firstRow || lastRow > Table2.EXPECTED_ENTRIES
                    || !List.of("all", "rows", "avoidance", "survivors", "exceptional-nine",
                    "exceptional-fifteen").contains(phase)
                    || (nineType != null && (!phase.equals("exceptional-nine")
                    || !br.unb.cic.td18.model.ExceptionalNineCycle.rootTypes().stream()
                    .map(br.unb.cic.td18.model.ExceptionalNineCycle::typeKey).toList().contains(nineType)))
                    || (fifteenType != null && (!phase.equals("exceptional-fifteen")
                    || !br.unb.cic.td18.model.ExceptionalFifteenCycle.obligations().stream()
                    .map(br.unb.cic.td18.model.ExceptionalFifteenCycle.Obligation::key)
                    .toList().contains(fifteenType)))) {
                throw new IllegalArgumentException("Invalid row range or checker phase");
            }
            work = work.toAbsolutePath().normalize();
            if (!structuralOnly && !help) {
                final var databaseReal = database.toRealPath();
                Files.createDirectories(work.getParent());
                final var workReal = work.getParent().toRealPath().resolve(work.getFileName());
                if (workReal.startsWith(databaseReal) || databaseReal.startsWith(workReal)
                        || Files.exists(work)) {
                    throw new IllegalArgumentException(
                            "Checker work must be a fresh directory outside the certificate: " + work);
                }
                Files.createDirectory(work);
            }
            return new Options(table.toAbsolutePath(),
                    database.toAbsolutePath(), work, firstRow, lastRow,
                    phase, nineType, fifteenType, structuralOnly, help);
        }

        boolean includes(final int row) {
            return row >= firstRow && row <= lastRow;
        }

        boolean checksExceptionalNine() {
            return phase.equals("all") || phase.equals("exceptional-nine");
        }

        boolean checksExceptionalFifteen() {
            return phase.equals("all") || phase.equals("exceptional-fifteen");
        }

        boolean checksSurvivors() {
            return phase.equals("all") || phase.equals("survivors");
        }

        boolean checksRows() {
            return phase.equals("all") || phase.equals("rows");
        }

        boolean checksAvoidance() {
            return phase.equals("all") || phase.equals("avoidance");
        }

    }
}

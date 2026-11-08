package br.unb.cic.td18.verification;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.joint.CpuClosure;
import br.unb.cic.td18.joint.IndependentJointRootGenerator;
import br.unb.cic.td18.joint.JointInventory;
import br.unb.cic.td18.joint.JointInverseReconstruction;
import br.unb.cic.td18.joint.SupportClearing;
import br.unb.cic.td18.model.CyclicTargetPair;
import br.unb.cic.td18.runtime.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** CPU-only regeneration of all requested obligations, followed by witness replay. */
public final class CertificateChecker {
    private CertificateChecker() { }
    public static void main(final String[] arguments) throws IOException {
        final var options = Options.parse(arguments,ProjectPaths.root(Path.of("")));
        if (options.help) { usage(); return; }
        final var inventory = JointInventory.read(options.paper);
        final var endpoints = JointInventory.endpoints();
        System.out.printf("joint-selection inventory checked: rootPartitions=%d endpointAnnotations=%d elementaryEndpoints=%d%n",
                inventory.size(),endpoints.size(),endpoints.stream().filter(JointInventory.Endpoint::elementaryClosure).count());
        if (options.structuralOnly) {
            System.out.println("Structural inventory checks only; sorting certificates and final avoidance remain unchecked.");
            return;
        }
        try (final var progress = new VerificationProgress(); final var certificates = new CertificateStore(options.database);
             final var residuals = new CheckerStateSet(options.work.resolve("endpoint-residuals"))) {
            if (options.roots()) for (final var row : inventory)
                if (options.includesPartition(row.number())) verifyRoot(row,certificates,progress);
            if (options.endpoints() || options.avoidance()) for (final var endpoint : endpoints)
                if (options.includesEndpoint(endpoint.number())) verifyEndpoint(endpoint,certificates,residuals,progress);
            final long residualCount = residuals.seal().count();
            if (options.avoidance()) verifyAvoidance(certificates,residuals,options.work,progress);
            else if (residualCount > 0) System.out.printf("Eight-move endpoint failures requiring final avoidance: %d%n",residualCount);
            if (options.completeScope()) System.out.println("All regenerated obligations in the complete joint-selection scope are verified.");
            else System.out.println("Requested partial verification scope completed; this is not a complete TD(18) certificate check.");
        } finally { DirectTwistedBraceletGenerator.shutdownExecutor(); }
    }
    static void verifyRoot(final JointInventory.Root row, final CertificateStore certificates,
                           final VerificationProgress progress) {
        final var unoriented = new LongAdder();
        progress.phase(() -> "root partition " + row.number() + " totally unoriented: regenerated=" + unoriented.sum());
        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(row.partition(),new boolean[row.partition().length],
                () -> { }, pair -> { certificates.require(pair,10); unoriented.increment(); });
        if (unoriented.sum() != row.unorientedClasses()) throw new IllegalStateException("Totally unoriented root count mismatch: partition="+row.number());
        final long[] oriented = {0}, cleared = {0};
        progress.phase(() -> "root partition " + row.number() + " oriented joint residuals: regenerated=" + oriented[0]);
        IndependentJointRootGenerator.generateResiduals(row.partition(),pair -> {
            oriented[0]++;
            if (SupportClearing.closes(pair)) { cleared[0]++; return; }
            if (!CpuClosure.closes(pair,10)) certificates.require(pair,10);
        });
        if (oriented[0] != row.jointResidualClasses() || cleared[0] != row.supportClearingClasses()
                || oriented[0]-cleared[0] != row.orientedClasses())
            throw new IllegalStateException("Oriented root count mismatch: partition=" + row.number()
                    + " generated=" + oriented[0] + " cleared=" + cleared[0]);
        System.out.printf("root partition %d verified: unoriented=%d jointResiduals=%d supportCleared=%d directOriented=%d%n",
                row.number(),unoriented.sum(),oriented[0],cleared[0],oriented[0]-cleared[0]);
    }
    static void verifyEndpoint(final JointInventory.Endpoint endpoint, final CertificateStore certificates,
                               final CheckerStateSet residuals, final VerificationProgress progress) {
        if (endpoint.elementaryClosure()) return;
        progress.phase("independent Burnside count for endpoint " + endpoint.number());
        final long expected = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                endpoint.partition(),endpoint.orientedByPart());
        final var generated = new LongAdder(); final var residual = new LongAdder();
        progress.phase(() -> "endpoint " + endpoint.number() + ": regenerated=" + generated.sum() + " residual=" + residual.sum());
        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(endpoint.partition(),endpoint.orientedByPart(),
                () -> { }, pair -> {
                    generated.increment();
                    if (checkEndpointPair(pair,certificates,residuals)) residual.increment();
                });
        if (generated.sum() != expected) throw new IllegalStateException(
                "Endpoint generation count disagrees with Burnside: endpoint=" + endpoint.number()
                        + " generated=" + generated.sum() + " expected=" + expected);
        System.out.printf("endpoint %d checked: generated=%d residuals=%d%n",endpoint.number(),generated.sum(),residual.sum());
    }
    /** Missing witnesses become regenerated obligations even without a stored marker. */
    static boolean checkEndpointPair(final CyclicTargetPair pair, final CertificateStore certificates,
                                     final CheckerStateSet residuals) {
        if (CpuClosure.closes(pair,8) || certificates.find(pair,8).isPresent()) return false;
        residuals.add(new JointInverseReconstruction.SearchState(8,pair));
        return true;
    }
    static void verifyAvoidance(final CertificateStore certificates, final CheckerStateSet endpoints,
                                final Path work, final VerificationProgress progress) {
        try (final var intermediate = new CheckerStateSet(work.resolve("intermediate-preimages"));
             final var roots = new CheckerStateSet(work.resolve("root-preimages"))) {
            progress.phase("regenerating gain-two inverse supports");
            endpoints.forEachBatch(batch -> batch.forEach(state -> JointInverseReconstruction.intermediates(state.pair(),
                    parent -> intermediate.add(new JointInverseReconstruction.SearchState(9,parent)))));
            final long intermediateCount = intermediate.seal().count();
            final long[] closed = {0};
            progress.phase("screening " + intermediateCount + " intermediate classes and regenerating gain-one inverse supports");
            intermediate.forEachBatch(batch -> batch.forEach(state -> {
                if (CpuClosure.closes(state.pair(),9)) { closed[0]++; return; }
                JointInverseReconstruction.roots(state.pair(),parent -> roots.add(new JointInverseReconstruction.SearchState(10,parent)));
            }));
            final long rootCount = roots.seal().count();
            final long[] checked = {0};
            progress.phase(() -> "avoidance roots: verified=" + checked[0] + "/" + rootCount);
            roots.forEachBatch(batch -> batch.forEach(state -> {
                if (!CpuClosure.closes(state.pair(),10)) certificates.require(state.pair(),10);
                checked[0]++;
            }));
            System.out.printf("final avoidance verified: endpointResiduals=%d intermediates=%d intermediateClosures=%d roots=%d%n",
                    endpoints.seal().count(),intermediateCount,closed[0],rootCount);
        }
    }
    private static void usage() {
        System.out.println("CertificateChecker [--phase=roots|endpoints|avoidance|all] [--partitions=FIRST-LAST]"
                + " [--endpoints=FIRST-LAST] [--paper=PATH] [--database=PATH] [--work=PATH] [--structural-only]");
    }
    record Options(Path paper,Path database,Path work,String phase,int firstPartition,int lastPartition,
                   int firstEndpoint,int lastEndpoint,boolean structuralOnly,boolean help) {
        static Options parse(final String[] args, final Path root) throws IOException {
            Path paper=root.resolve("docs/td18-reduction-count.tex"), database=Path.of(System.getProperty("td18.certificateDb",root.resolve("certificates").toString()));
            Path work=root.resolve("checker/target/joint-verification-"+ProcessHandle.current().pid()+"-"+System.nanoTime());
            String phase="all"; int firstPartition=1,lastPartition=53,firstEndpoint=1,lastEndpoint=104;
            boolean structural=false,help=false;
            for (final var arg:args) {
                if(arg.equals("--help")) help=true;
                else if(arg.equals("--structural-only")) structural=true;
                else if(arg.startsWith("--phase=")) phase=arg.substring(8);
                else if(arg.startsWith("--paper=")) paper=Path.of(arg.substring(8));
                else if(arg.startsWith("--database=")) database=Path.of(arg.substring(11));
                else if(arg.startsWith("--work=")) work=Path.of(arg.substring(7));
                else if(arg.startsWith("--partitions=")) { final int[] r=range(arg.substring(13),53);firstPartition=r[0];lastPartition=r[1]; }
                else if(arg.startsWith("--endpoints=")) { final int[] r=range(arg.substring(12),104);firstEndpoint=r[0];lastEndpoint=r[1]; }
                else throw new IllegalArgumentException("Unknown argument: "+arg);
            }
            if(!List.of("roots","endpoints","avoidance","all").contains(phase)) throw new IllegalArgumentException("Invalid checker phase");
            paper=paper.toAbsolutePath().normalize();database=database.toAbsolutePath().normalize();work=work.toAbsolutePath().normalize();
            if(!help&&!structural) {
                final var db=database.toRealPath(); Files.createDirectories(work.getParent());
                final var actualWork=work.getParent().toRealPath().resolve(work.getFileName());
                if(actualWork.startsWith(db)||db.startsWith(actualWork)||Files.exists(work))
                    throw new IllegalArgumentException("Checker work must be fresh and outside the certificate database");
                Files.createDirectory(work);
            }
            return new Options(paper,database,work,phase,firstPartition,lastPartition,firstEndpoint,lastEndpoint,structural,help);
        }
        private static int[] range(final String value, final int maximum) {
            final var parts=value.split("-",-1);
            if(parts.length!=2) throw new IllegalArgumentException("Expected FIRST-LAST range");
            final int first=Integer.parseInt(parts[0]),last=Integer.parseInt(parts[1]);
            if(first<1||last<first||last>maximum) throw new IllegalArgumentException("Invalid range");
            return new int[]{first,last};
        }
        boolean roots(){return phase.equals("all")||phase.equals("roots");}
        boolean endpoints(){return phase.equals("all")||phase.equals("endpoints");}
        boolean avoidance(){return phase.equals("all")||phase.equals("avoidance");}
        boolean includesPartition(final int n){return firstPartition<=n&&n<=lastPartition;}
        boolean includesEndpoint(final int n){return firstEndpoint<=n&&n<=lastEndpoint;}
        boolean completeScope(){return phase.equals("all")&&firstPartition==1&&lastPartition==53&&firstEndpoint==1&&lastEndpoint==104;}
    }
}

package br.unb.cic.td18.joint;

import br.unb.cic.td18.reduction.AnnotatedPartitions;
import br.unb.cic.td18.reduction.Partitions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Regenerates the partition inventory and checks every machine-readable paper row. */
public final class JointInventory {
    public static final int ROOT_COUNT = 53;
    public static final int ENDPOINT_COUNT = 104;
    private JointInventory() { }
    public record Root(int number, int[] partition, long unorientedClasses,
                       long jointResidualClasses, long supportClearingClasses, long orientedClasses, long unorientedResidualClasses) {
        public Root {
            partition = partition.clone();
            if (unorientedResidualClasses < -1 || unorientedResidualClasses > unorientedClasses)
                throw new IllegalArgumentException("Invalid unoriented residual count");
            if (number < 1 || unorientedClasses < 0 || jointResidualClasses < 0
                    || supportClearingClasses < 0 || orientedClasses < 0
                    || jointResidualClasses - supportClearingClasses != orientedClasses)
                throw new IllegalArgumentException("Inconsistent root count");
        }
        public boolean countsComplete() { return unorientedResidualClasses >= 0; }
        public void requireCompleteCounts() {
            if (!countsComplete()) throw new IllegalStateException("Pending unoriented joint residual counts: partition=" + number);
        }
        @Override public int[] partition() { return partition.clone(); }
    }
    public record Endpoint(int number, int[] partition, boolean[] orientedByPart) {
        public Endpoint { partition = partition.clone(); orientedByPart = orientedByPart.clone(); }
        @Override public int[] partition() { return partition.clone(); }
        @Override public boolean[] orientedByPart() { return orientedByPart.clone(); }
        public boolean elementaryClosure() {
            for (int i = 0; i < partition.length; i++)
                if (partition[i] == 3 && orientedByPart[i]) return true;
            return false;
        }
    }
    public static List<Root> read(final Path paper) throws IOException {
        final var residuals = UnorientedResidualInventory.read(paper, 53);
        final var syntax = Pattern.compile("^\\\\jointrow\\{([0-9]+)}\\{([0-9,]+)}\\{([0-9]+)}\\{([0-9]+)}\\{([0-9]+)}\\{([0-9]+)}\\s*(?:%.*)?$");
        final var rows = new ArrayList<Root>();
        for (final var line : Files.readAllLines(paper)) {
            final var m = syntax.matcher(line.trim());
            if (!m.matches()) {
                if (line.trim().startsWith("\\jointrow{")) throw new IllegalStateException("Malformed paper inventory row");
                continue;
            }
            rows.add(new Root(Integer.parseInt(m.group(1)), Arrays.stream(m.group(2).split(","))
                    .mapToInt(Integer::parseInt).toArray(), Long.parseLong(m.group(3)),
                    Long.parseLong(m.group(4)), Long.parseLong(m.group(5)), Long.parseLong(m.group(6)), residuals.get(Integer.parseInt(m.group(1)))));
        }
        final var expected = Partitions.generateValidPartitions(19);
        if (rows.size() != ROOT_COUNT || expected.size() != ROOT_COUNT)
            throw new IllegalStateException("The paper must contain exactly 53 root partitions");
        for (int i = 0; i < ROOT_COUNT; i++) {
            if (rows.get(i).number() != i + 1 || !Arrays.equals(expected.get(i), rows.get(i).partition()))
                throw new IllegalStateException("Root partition mismatch at " + (i + 1));
        }
        if (rows.stream().mapToLong(Root::unorientedClasses).sum() != 214_474_050L
                || rows.stream().mapToLong(Root::jointResidualClasses).sum() != 37_858_324L
                || rows.stream().mapToLong(Root::supportClearingClasses).sum() != 4_249_763L
                || rows.stream().mapToLong(Root::orientedClasses).sum() != 33_608_561L)
            throw new IllegalStateException("Paper root totals disagree with the checked counting inventory");
        return List.copyOf(rows);
    }
    public static List<Endpoint> endpoints() {
        final var result = new ArrayList<Endpoint>();
        for (final var p : Partitions.generateValidPartitions(16)) {
            if (KnownDiameters.threeNorm(p) > 7) continue;
            for (final var a : AnnotatedPartitions.of(p))
                result.add(new Endpoint(result.size() + 1, p, a.oriented()));
        }
        if (result.size() != ENDPOINT_COUNT || result.stream().filter(Endpoint::elementaryClosure).count() != 52)
            throw new IllegalStateException("Endpoint annotation inventory mismatch");
        return List.copyOf(result);
    }
}

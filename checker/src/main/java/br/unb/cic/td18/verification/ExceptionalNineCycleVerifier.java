package br.unb.cic.td18.verification;

import br.unb.cic.td18.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td18.certificate.CertificateStore;
import br.unb.cic.td18.model.ExceptionalNineCycle;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Regenerates the entire root branch; neither local nor TD(16) certificates suffice. */
public final class ExceptionalNineCycleVerifier {
    private ExceptionalNineCycleVerifier() {
    }

    public static void verifyInventory() {
        final var types = ExceptionalNineCycle.rootTypes();
        final var partitions = types.stream().map(type -> Arrays.toString(type.partition()))
                .collect(Collectors.toSet());
        final var keys = types.stream().map(ExceptionalNineCycle::typeKey).collect(Collectors.toSet());
        if (types.size() != 18 || keys.size() != 18 || !partitions.equals(Set.of(
                "[9, 8, 2]", "[9, 7, 3]", "[9, 6, 4]", "[9, 5, 5]",
                "[9, 4, 2, 2, 2]", "[9, 3, 3, 2, 2]"))) {
            throw new IllegalStateException("Exceptional-nine root inventory mismatch");
        }
        final var local = ExceptionalNineCycle.candidate();
        if (local.formsCyclicTargetPair() || !ExceptionalNineCycle.contains(local)) {
            throw new IllegalStateException("Incorrect exceptional-nine local candidate");
        }
        System.out.println("exceptional-nine root inventory verified: partitions=6 annotations=18");
    }

    public static void verify(final CertificateStore certificates, final String selectedType,
                              final VerificationProgress progress) {
        var selected = 0;
        for (final var type : ExceptionalNineCycle.rootTypes()) {
            final var key = ExceptionalNineCycle.typeKey(type);
            if (selectedType != null && !selectedType.equals(key)) {
                continue;
            }
            selected++;
            final long[] verified = {0};
            progress.phase("exceptional-nine root verification: " + key);
            DirectTwistedBraceletGenerator.generateExceptionalNineRepresentatives(
                    type.partition(), type.oriented(), pair -> {
                        if (pair.getBeta().size() != ExceptionalNineCycle.DOMAIN
                                || !ExceptionalNineCycle.contains(pair)) {
                            throw new IllegalStateException("Invalid exceptional-nine root: " + pair);
                        }
                        certificates.find(pair, ExceptionalNineCycle.MAXIMUM_MOVES)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Missing exceptional-nine root witness: type=" + key
                                                + " classKey=" + pair.extendedToricClassKey()));
                        verified[0]++;
                    });
            System.out.printf("exceptional-nine type verified: type=%s classes=%d%n", key, verified[0]);
        }
        if (selected == 0) {
            throw new IllegalArgumentException("Unknown exceptional-nine type: " + selectedType);
        }
    }
}

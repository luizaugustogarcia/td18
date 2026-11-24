package br.unb.cic.td18;

import java.io.IOException;

/**
 * Command-line entry point; implementation lives in {@link br.unb.cic.td18.verification.ErikssonCertificateChecker}.
 */
public final class CertificateChecker {

    private CertificateChecker() {
    }

    public static void main(final String[] args) throws IOException {
        br.unb.cic.td18.verification.ErikssonCertificateChecker.main(args);
    }
}

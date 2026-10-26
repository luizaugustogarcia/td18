package br.unb.cic.td18.certificate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateRowManifestTest {
    @Test
    void recordsAZeroObligationRowWithoutGeneration() {
        final var manifest = new CertificateRowManifest(
                126, "(13-O,3-O)", 8, 1_197_556_864L, 0,
                0, 0, 0, 0, 0, true);

        final var decoded = CertificateRowManifest.decode(
                manifest.key(), manifest.value());

        assertEquals(manifest, decoded);
        assertTrue(decoded.complete());
    }

    @Test
    void requiresGenerationToEqualRemainingObligations() {
        assertThrows(IllegalArgumentException.class,
                () -> new CertificateRowManifest(
                        126, "(13-O,3-O)", 8, 1_197_556_864L, 0,
                        1_197_556_864L, 0, 1_197_556_864L, 0, 0, true));
    }
}

package br.unb.cic.td18.certificate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateRowManifestTest {
    @Test
    void distinguishesRestrictedSeedsFromOldWholeRowCompletionRecords() {
        final var manifest = new CertificateRowManifest(267, "(13-O,5-O)", 9,
                284_235_604_416L, 391, 391, 0, 0, 391, 0, false);
        assertEquals(manifest, CertificateRowManifest.decode(manifest.encoded()));
        final byte[] legacy = manifest.encoded();
        assertEquals(4, Byte.toUnsignedInt(legacy[1]));
        legacy[1] = 3;
        assertThrows(IllegalArgumentException.class, () -> CertificateRowManifest.decode(legacy));
    }

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

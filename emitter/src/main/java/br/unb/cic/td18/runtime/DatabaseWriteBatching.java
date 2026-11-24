package br.unb.cic.td18.runtime;

/**
 * Maximum entries per database write batch or external SST table.
 */
public final class DatabaseWriteBatching {

    private DatabaseWriteBatching() {
    }

    public static int entries() {
        final String configured = System.getProperty("td18.dbWriteBatchEntries");
        final int entries = configured == null ? 524_288 : Integer.parseInt(configured.trim());
        if (entries < 1) {
            throw new IllegalArgumentException("td18.dbWriteBatchEntries must be positive");
        }

        return entries;
    }
}

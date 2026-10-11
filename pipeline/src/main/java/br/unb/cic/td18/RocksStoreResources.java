package br.unb.cic.td18;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;

/** Shared bounded cache policy for class, checkpoint, and witness stores. */
final class RocksStoreResources implements AutoCloseable {
    static {
        RocksDB.loadLibrary();
    }

    private final LRUCache blockCache;
    private final BloomFilter bloomFilter;
    final Options options;
    final ReadOptions reads;
    final ReadOptions scans;
    final WriteOptions writes;

    RocksStoreResources(final boolean writable) {
        final var cacheMiB = Long.getLong("td18.rocksdbCacheMiB", 256L);
        if (cacheMiB < 1) {
            throw new IllegalArgumentException("RocksDB cache must be positive");
        }
        blockCache = new LRUCache(Math.multiplyExact(cacheMiB, 1024L * 1024L),
                -1, false, 0.2);
        bloomFilter = new BloomFilter(10);
        options = new Options()
                .setCreateIfMissing(writable)
                .setMaxOpenFiles(-1)
                .setTableFormatConfig(new BlockBasedTableConfig()
                        .setBlockCache(blockCache)
                        .setFilterPolicy(bloomFilter)
                        .setCacheIndexAndFilterBlocks(true)
                        .setCacheIndexAndFilterBlocksWithHighPriority(true)
                        .setPinL0FilterAndIndexBlocksInCache(true));
        reads = new ReadOptions().setVerifyChecksums(true).setFillCache(true);
        // Sequential scans must not evict hot membership or witness blocks.
        scans = new ReadOptions().setVerifyChecksums(true).setFillCache(false);
        // Pipeline stores are resumable and do not require per-write durability.
        writes = new WriteOptions().setDisableWAL(true).setSync(false);
    }

    @Override
    public void close() {
        writes.close();
        scans.close();
        reads.close();
        options.close();
        bloomFilter.close();
        blockCache.close();
    }
}

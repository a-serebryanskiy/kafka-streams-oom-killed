package ru.raif.sdp;

import java.util.Map;
import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Cache;
import org.rocksdb.CompressionType;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.WriteBufferManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//https://docs.confluent.io/platform/current/streams/developer-guide/memory-mgmt.html#rocksdb - is taken as an instruction
public class BoundedMemoryRocksDBConfig implements RocksDBConfigSetter {

  private static final Logger logger = LoggerFactory.getLogger(BoundedMemoryRocksDBConfig.class);
  private static final int N_MEMTABLES = 2;
  private static final long WRITE_BUFFER_SIZE_BYTES = 10 * 1024 * 1024;
  private static final long TOTAL_MEMTABLE_MEMORY = WRITE_BUFFER_SIZE_BYTES * N_MEMTABLES;
  private static final long MIN_BLOCK_CACHE_SIZE_BYTES = 50 * 1024 * 1024; // 50 Mb
  private static final long MIN_ROCKSDB_MEMORY_BYTES = MIN_BLOCK_CACHE_SIZE_BYTES + TOTAL_MEMTABLE_MEMORY;

  private static final Cache cache = new LRUCache(computeTotalRocksDbMem(), -1, false);

  private static final WriteBufferManager writeBufferManager = new WriteBufferManager(TOTAL_MEMTABLE_MEMORY, cache);

  private static long computeTotalRocksDbMem() {
    long totalContainerMemoryBytes = Long.parseLong(getRequiredEnv("CONTAINER_MEMORY_LIMIT"));
    double osPercentage = Double.parseDouble(getRequiredEnv("OS_MEMORY_PERCENTAGE"));
    long offHeapSizeMb = Long.parseLong(getRequiredEnv("OFF_HEAP_SIZE_MB"));
    long maxHeapSizeMb = Long.parseLong(getRequiredEnv("MAX_HEAP_SIZE_MB"));
    long jvmMemoryBytes = (offHeapSizeMb + maxHeapSizeMb) * 1024 * 1024;
    logger.info("Container memory limit: {}, osPercentage: {}, offHeapSizeMb: {}, maxHeapSizeMb: {}",
        totalContainerMemoryBytes, osPercentage, offHeapSizeMb, maxHeapSizeMb);

    long rocksDbAvailableMem = (long) (totalContainerMemoryBytes * (1 - osPercentage)) - jvmMemoryBytes;

    if (rocksDbAvailableMem < MIN_ROCKSDB_MEMORY_BYTES) {
      throw new RuntimeException(String.format(
          "RocksDB does not have enough available memory. Current computed memory in bytes: %d, min required memory: %d. Try increasing CONTAINER_MEMORY_LIMIT env var.",
          rocksDbAvailableMem, MIN_ROCKSDB_MEMORY_BYTES));
    }

    logger.info("RocksDb available memory: {}", rocksDbAvailableMem);
    return rocksDbAvailableMem;
  }

  private static String getRequiredEnv(String envName) {
    String envVal = System.getenv(envName);
    if (envVal == null) {
      throw new RuntimeException("Env var " + envName + " is required");
    }
    return envVal;
  }

  @Override
  public void setConfig(final String storeName, final Options options, final Map<String, Object> configs) {
    BlockBasedTableConfig tableConfig = (BlockBasedTableConfig) options.tableFormatConfig();

    tableConfig.setBlockCache(cache);
    tableConfig.setCacheIndexAndFilterBlocks(true);
    options.setWriteBufferManager(writeBufferManager);

    tableConfig.setPinTopLevelIndexAndFilter(true);
    options.setMaxWriteBufferNumber(N_MEMTABLES);
    options.setWriteBufferSize(WRITE_BUFFER_SIZE_BYTES);
    options.setCompressionType(CompressionType.LZ4_COMPRESSION);
    options.setTableFormatConfig(tableConfig);
  }

  @Override
  public void close(final String storeName, final Options options) {
  }
}
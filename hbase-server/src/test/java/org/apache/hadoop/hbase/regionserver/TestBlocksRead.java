/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManagerTestHelper;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(MediumTests.class)
public class TestBlocksRead extends HBaseTestCase {
  static final Log LOG = LogFactory.getLog(TestBlocksRead.class);
  static final BloomType[] BLOOM_TYPE = new BloomType[] { BloomType.ROWCOL,
      BloomType.ROW, BloomType.NONE };

  private static BlockCache blockCache;

  private HBaseConfiguration getConf() {
    HBaseConfiguration conf = new HBaseConfiguration();

    // disable compactions in this test.
    conf.setInt("hbase.hstore.compactionThreshold", 10000);
    return conf;
  }

  HRegion region = null;
  private HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private final String DIR = TEST_UTIL.getDataTestDir("TestBlocksRead").toString();

  /**
   * @see org.apache.hadoop.hbase.HBaseTestCase#setUp()
   */
  @SuppressWarnings("deprecation")
  @Override
  protected void setUp() throws Exception {
    super.setUp();
  }

  @SuppressWarnings("deprecation")
  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    EnvironmentEdgeManagerTestHelper.reset();
  }

  /**
   * Callers must afterward call {@link HRegion#closeHRegion(HRegion)}
   * @param tableName
   * @param callingMethod
   * @param conf
   * @param families
   * @throws IOException
   * @return created and initialized region.
   */
  private HRegion initHRegion(byte[] tableName, String callingMethod,
      HBaseConfiguration conf, String family) throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor familyDesc;
    for (int i = 0; i < BLOOM_TYPE.length; i++) {
      BloomType bloomType = BLOOM_TYPE[i];
      familyDesc = new HColumnDescriptor(family + "_" + bloomType)
          .setBlocksize(1)
          .setBloomFilterType(BLOOM_TYPE[i]);
      htd.addFamily(familyDesc);
    }

    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    Path path = new Path(DIR + callingMethod);
    HRegion r = HRegion.createHRegion(info, path, conf, htd);
    blockCache = new CacheConfig(conf).getBlockCache();
    return r;
  }

  private void putData(String family, String row, String col, long version)
      throws IOException {
    for (int i = 0; i < BLOOM_TYPE.length; i++) {
      putData(Bytes.toBytes(family + "_" + BLOOM_TYPE[i]), row, col, version,
          version);
    }
  }

  // generates a value to put for a row/col/version.
  private static byte[] genValue(String row, String col, long version) {
    return Bytes.toBytes("Value:" + row + "#" + col + "#" + version);
  }

  private void putData(byte[] cf, String row, String col, long versionStart,
      long versionEnd) throws IOException {
    byte columnBytes[] = Bytes.toBytes(col);
    Put put = new Put(Bytes.toBytes(row));
    put.setWriteToWAL(false);

    for (long version = versionStart; version <= versionEnd; version++) {
      put.add(cf, columnBytes, version, genValue(row, col, version));
    }
    region.put(put);
  }

  private KeyValue[] getData(String family, String row, List<String> columns,
      int expBlocks) throws IOException {
    return getData(family, row, columns, expBlocks, expBlocks, expBlocks);
  }

  private KeyValue[] getData(String family, String row, List<String> columns,
      int expBlocksRowCol, int expBlocksRow, int expBlocksNone)
      throws IOException {
    int[] expBlocks = new int[] { expBlocksRowCol, expBlocksRow, expBlocksNone };
    KeyValue[] kvs = null;

    for (int i = 0; i < BLOOM_TYPE.length; i++) {
      BloomType bloomType = BLOOM_TYPE[i];
      byte[] cf = Bytes.toBytes(family + "_" + bloomType);
      long blocksStart = getBlkAccessCount(cf);
      Get get = new Get(Bytes.toBytes(row));

      for (String column : columns) {
        get.addColumn(cf, Bytes.toBytes(column));
      }

      kvs = region.get(get, null).raw();
      long blocksEnd = getBlkAccessCount(cf);
      if (expBlocks[i] != -1) {
        assertEquals("Blocks Read Check for Bloom: " + bloomType, expBlocks[i],
            blocksEnd - blocksStart);
      }
      System.out.println("Blocks Read for Bloom: " + bloomType + " = "
          + (blocksEnd - blocksStart) + "Expected = " + expBlocks[i]);
    }
    return kvs;
  }

  private KeyValue[] getData(String family, String row, String column,
      int expBlocks) throws IOException {
    return getData(family, row, Arrays.asList(column), expBlocks, expBlocks,
        expBlocks);
  }

  private KeyValue[] getData(String family, String row, String column,
      int expBlocksRowCol, int expBlocksRow, int expBlocksNone)
      throws IOException {
    return getData(family, row, Arrays.asList(column), expBlocksRowCol,
        expBlocksRow, expBlocksNone);
  }

  private void deleteFamily(String family, String row, long version)
      throws IOException {
    Delete del = new Delete(Bytes.toBytes(row));
    del.deleteFamily(Bytes.toBytes(family + "_ROWCOL"), version);
    del.deleteFamily(Bytes.toBytes(family + "_ROW"), version);
    del.deleteFamily(Bytes.toBytes(family + "_NONE"), version);
    region.delete(del, null, true);
  }

  private static void verifyData(KeyValue kv, String expectedRow,
      String expectedCol, long expectedVersion) {
    assertEquals("RowCheck", expectedRow, Bytes.toString(kv.getRow()));
    assertEquals("ColumnCheck", expectedCol, Bytes.toString(kv.getQualifier()));
    assertEquals("TSCheck", expectedVersion, kv.getTimestamp());
    assertEquals("ValueCheck",
        Bytes.toString(genValue(expectedRow, expectedCol, expectedVersion)),
        Bytes.toString(kv.getValue()));
  }

  private static long getBlkAccessCount(byte[] cf) {
      return HFile.dataBlockReadCnt.get();
  }

  private static long getBlkCount() {
    return blockCache.getBlockCount();
  }

  /**
   * Test # of blocks read for some simple seek cases.
   *
   * @throws Exception
   */
  @Test
  public void testBlocksRead() throws Exception {
    byte[] TABLE = Bytes.toBytes("testBlocksRead");
    String FAMILY = "cf1";
    KeyValue kvs[];
    HBaseConfiguration conf = getConf();
    this.region = initHRegion(TABLE, getName(), conf, FAMILY);

    try {
      putData(FAMILY, "row", "col1", 1);
      putData(FAMILY, "row", "col2", 2);
      putData(FAMILY, "row", "col3", 3);
      putData(FAMILY, "row", "col4", 4);
      putData(FAMILY, "row", "col5", 5);
      putData(FAMILY, "row", "col6", 6);
      putData(FAMILY, "row", "col7", 7);
      region.flushcache();

      // Expected block reads: 1
      // The top block has the KV we are
      // interested. So only 1 seek is needed.
      kvs = getData(FAMILY, "row", "col1", 1);
      assertEquals(1, kvs.length);
      verifyData(kvs[0], "row", "col1", 1);

      // Expected block reads: 2
      // The top block and next block has the KVs we are
      // interested. So only 2 seek is needed.
      kvs = getData(FAMILY, "row", Arrays.asList("col1", "col2"), 2);
      assertEquals(2, kvs.length);
      verifyData(kvs[0], "row", "col1", 1);
      verifyData(kvs[1], "row", "col2", 2);

      // Expected block reads: 3
      // The first 2 seeks is to find out col2. [HBASE-4443]
      // One additional seek for col3
      // So 3 seeks are needed.
      kvs = getData(FAMILY, "row", Arrays.asList("col2", "col3"), 3);
      assertEquals(2, kvs.length);
      verifyData(kvs[0], "row", "col2", 2);
      verifyData(kvs[1], "row", "col3", 3);

      // Expected block reads: 2. [HBASE-4443]
      kvs = getData(FAMILY, "row", Arrays.asList("col5"), 2);
      assertEquals(1, kvs.length);
      verifyData(kvs[0], "row", "col5", 5);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * Test # of blocks read (targetted at some of the cases Lazy Seek optimizes).
   *
   * @throws Exception
   */
  @Test
  public void testLazySeekBlocksRead() throws Exception {
    byte[] TABLE = Bytes.toBytes("testLazySeekBlocksRead");
    String FAMILY = "cf1";
    KeyValue kvs[];
    HBaseConfiguration conf = getConf();
    this.region = initHRegion(TABLE, getName(), conf, FAMILY);

    try {
      // File 1
      putData(FAMILY, "row", "col1", 1);
      putData(FAMILY, "row", "col2", 2);
      region.flushcache();

      // File 2
      putData(FAMILY, "row", "col1", 3);
      putData(FAMILY, "row", "col2", 4);
      region.flushcache();

      // Expected blocks read: 1.
      // File 2's top block is also the KV we are
      // interested. So only 1 seek is needed.
      kvs = getData(FAMILY, "row", Arrays.asList("col1"), 1);
      assertEquals(1, kvs.length);
      verifyData(kvs[0], "row", "col1", 3);

      // Expected blocks read: 2
      // File 2's top block has the "col1" KV we are
      // interested. We also need "col2" which is in a block
      // of its own. So, we need that block as well.
      kvs = getData(FAMILY, "row", Arrays.asList("col1", "col2"), 2);
      assertEquals(2, kvs.length);
      verifyData(kvs[0], "row", "col1", 3);
      verifyData(kvs[1], "row", "col2", 4);

      // File 3: Add another column
      putData(FAMILY, "row", "col3", 5);
      region.flushcache();

      // Expected blocks read: 1
      // File 3's top block has the "col3" KV we are
      // interested. So only 1 seek is needed.
      kvs = getData(FAMILY, "row", "col3", 1);
      assertEquals(1, kvs.length);
      verifyData(kvs[0], "row", "col3", 5);

      // Get a column from older file.
      // For ROWCOL Bloom filter: Expected blocks read: 1.
      // For ROW Bloom filter: Expected blocks read: 2.
      // For NONE Bloom filter: Expected blocks read: 2.
      kvs = getData(FAMILY, "row", Arrays.asList("col1"), 1, 2, 2);
      assertEquals(1, kvs.length);
      verifyData(kvs[0], "row", "col1", 3);

      // File 4: Delete the entire row.
      deleteFamily(FAMILY, "row", 6);
      region.flushcache();

      // For ROWCOL Bloom filter: Expected blocks read: 2.
      // For ROW Bloom filter: Expected blocks read: 3.
      // For NONE Bloom filter: Expected blocks read: 3.
      kvs = getData(FAMILY, "row", "col1", 2, 3, 3);
      assertEquals(0, kvs.length);
      kvs = getData(FAMILY, "row", "col2", 3, 4, 4);
      assertEquals(0, kvs.length);
      kvs = getData(FAMILY, "row", "col3", 2);
      assertEquals(0, kvs.length);
      kvs = getData(FAMILY, "row", Arrays.asList("col1", "col2", "col3"), 4);
      assertEquals(0, kvs.length);

      // File 5: Delete
      deleteFamily(FAMILY, "row", 10);
      region.flushcache();

      // File 6: some more puts, but with timestamps older than the
      // previous delete.
      putData(FAMILY, "row", "col1", 7);
      putData(FAMILY, "row", "col2", 8);
      putData(FAMILY, "row", "col3", 9);
      region.flushcache();

      // Baseline expected blocks read: 8. [HBASE-4532]
      kvs = getData(FAMILY, "row", Arrays.asList("col1", "col2", "col3"), 5);
      assertEquals(0, kvs.length);
 
      // File 7: Put back new data
      putData(FAMILY, "row", "col1", 11);
      putData(FAMILY, "row", "col2", 12);
      putData(FAMILY, "row", "col3", 13);
      region.flushcache();


      // Expected blocks read: 5. [HBASE-4585]
      kvs = getData(FAMILY, "row", Arrays.asList("col1", "col2", "col3"), 5);
      assertEquals(3, kvs.length);
      verifyData(kvs[0], "row", "col1", 11);
      verifyData(kvs[1], "row", "col2", 12);
      verifyData(kvs[2], "row", "col3", 13);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * Test # of blocks read to ensure disabling cache-fill on Scan works.
   * @throws Exception
   */
  @Test
  public void testBlocksStoredWhenCachingDisabled() throws Exception {
    byte [] TABLE = Bytes.toBytes("testBlocksReadWhenCachingDisabled");
    String FAMILY = "cf1";

    HBaseConfiguration conf = getConf();
    this.region = initHRegion(TABLE, getName(), conf, FAMILY);

    try {
      putData(FAMILY, "row", "col1", 1);
      putData(FAMILY, "row", "col2", 2);
      region.flushcache();

      // Execute a scan with caching turned off
      // Expected blocks stored: 0
      long blocksStart = getBlkCount();
      Scan scan = new Scan();
      scan.setCacheBlocks(false);
      RegionScanner rs = region.getScanner(scan);
      List<KeyValue> result = new ArrayList<KeyValue>(2);
      rs.next(result);
      assertEquals(2 * BLOOM_TYPE.length, result.size());
      rs.close();
      long blocksEnd = getBlkCount();

      assertEquals(blocksStart, blocksEnd);

      // Execute with caching turned on
      // Expected blocks stored: 2
      blocksStart = blocksEnd;
      scan.setCacheBlocks(true);
      rs = region.getScanner(scan);
      result = new ArrayList<KeyValue>(2);
      rs.next(result);
      assertEquals(2 * BLOOM_TYPE.length, result.size());
      rs.close();
      blocksEnd = getBlkCount();
    
      assertEquals(2 * BLOOM_TYPE.length, blocksEnd - blocksStart);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  @Test
  public void testLazySeekBlocksReadWithDelete() throws Exception {
    byte[] TABLE = Bytes.toBytes("testLazySeekBlocksReadWithDelete");
    String FAMILY = "cf1";
    KeyValue kvs[];
    HBaseConfiguration conf = getConf();
    this.region = initHRegion(TABLE, getName(), conf, FAMILY);
    try {
      deleteFamily(FAMILY, "row", 200);
      for (int i = 0; i < 100; i++) {
        putData(FAMILY, "row", "col" + i, i);
      }
      putData(FAMILY, "row", "col99", 201);
      region.flushcache();

      kvs = getData(FAMILY, "row", Arrays.asList("col0"), 2);
      assertEquals(0, kvs.length);

      kvs = getData(FAMILY, "row", Arrays.asList("col99"), 2);
      assertEquals(1, kvs.length);
      verifyData(kvs[0], "row", "col99", 201);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

}

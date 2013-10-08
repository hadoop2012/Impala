// Copyright 2013 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.util.Map;

import junit.framework.Assert;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.cloudera.impala.catalog.Catalog.CatalogInitStrategy;
import com.cloudera.impala.thrift.ImpalaInternalServiceConstants;
import com.cloudera.impala.thrift.THBaseTable;
import com.cloudera.impala.thrift.THdfsPartition;
import com.cloudera.impala.thrift.THdfsTable;
import com.cloudera.impala.thrift.TTable;
import com.cloudera.impala.thrift.TTableType;
import com.cloudera.impala.thrift.TUniqueId;

/**
 * Test suite to verify proper conversion of Catalog objects to/from Thrift structs.
 */
public class CatalogObjectToFromThriftTest {
  private static Catalog catalog;

  @BeforeClass
  public static void setUp() throws Exception {
    catalog = new CatalogServiceCatalog(new TUniqueId(0L, 0L),
        CatalogInitStrategy.LAZY);
  }

  @AfterClass
  public static void cleanUp() { catalog.close(); }

  @Test
  public void TestPartitionedTable() throws DatabaseNotFoundException,
      TableNotFoundException, TableLoadingException {
    String[] dbNames = {"functional", "functional_avro", "functional_parquet"};
    for (String dbName: dbNames) {
      Table table = catalog.getTable(dbName, "alltypes");
      TTable thriftTable = table.toThrift();
      Assert.assertEquals(thriftTable.tbl_name, "alltypes");
      Assert.assertEquals(thriftTable.db_name, dbName);
      Assert.assertTrue(thriftTable.isSetTable_type());
      Assert.assertEquals(thriftTable.getPartition_columns().size(), 2);
      Assert.assertEquals(thriftTable.getTable_type(), TTableType.HDFS_TABLE);
      THdfsTable hdfsTable = thriftTable.getHdfs_table();
      Assert.assertTrue(hdfsTable.hdfsBaseDir != null);

      // The table has 24 partitions + the default partition
      Assert.assertEquals(hdfsTable.getPartitions().size(), 25);
      Assert.assertTrue(hdfsTable.getPartitions().containsKey(
          new Long(ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID)));

      for (Map.Entry<Long, THdfsPartition> kv: hdfsTable.getPartitions().entrySet()) {
        if (kv.getKey() == ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID) {
          Assert.assertEquals(kv.getValue().getPartitionKeyExprs().size(), 0);
        } else {
          Assert.assertEquals(kv.getValue().getPartitionKeyExprs().size(), 2);
        }
      }

      // Now try to load the thrift struct.
      Table newTable = Table.fromMetastoreTable(catalog.getNextTableId(),
          catalog.getDb(dbName), thriftTable.getMetastore_table());
      newTable.loadFromTTable(thriftTable);
      Assert.assertTrue(newTable instanceof HdfsTable);
      Assert.assertEquals(newTable.name, thriftTable.tbl_name);
      Assert.assertEquals(newTable.numClusteringCols, 2);
      // Currently only have table stats on "functional.alltypes"
      Assert.assertEquals(newTable.numRows, dbName.equals("functional") ? 7300 : -1);
      HdfsTable newHdfsTable = (HdfsTable) newTable;
      Assert.assertEquals(newHdfsTable.getPartitions().size(), 25);
      boolean foundDefaultPartition = false;
      for (HdfsPartition hdfsPart: newHdfsTable.getPartitions()) {
        if (hdfsPart.getId() == ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID) {
          Assert.assertEquals(foundDefaultPartition, false);
          foundDefaultPartition = true;
        } else {
          Assert.assertEquals(hdfsPart.getFileDescriptors().size(), 1);
          Assert.assertTrue(
              hdfsPart.getFileDescriptors().get(0).getFileBlocks().size() > 0);
        }
      }
      Assert.assertEquals(foundDefaultPartition, true);
    }
  }

  @Test
  public void TestHBaseTables() throws DatabaseNotFoundException,
      TableNotFoundException, TableLoadingException {
    String dbName = "functional_hbase";
    Table table = catalog.getTable(dbName, "alltypes");
    TTable thriftTable = table.toThrift();
    Assert.assertEquals(thriftTable.tbl_name, "alltypes");
    Assert.assertEquals(thriftTable.db_name, dbName);
    Assert.assertTrue(thriftTable.isSetTable_type());
    Assert.assertEquals(thriftTable.getPartition_columns().size(), 0);
    Assert.assertEquals(thriftTable.getTable_type(), TTableType.HBASE_TABLE);
    THBaseTable hbaseTable = thriftTable.getHbase_table();
    Assert.assertEquals(hbaseTable.getFamilies().size(), 13);
    Assert.assertEquals(hbaseTable.getQualifiers().size(), 13);
    Assert.assertEquals(hbaseTable.getBinary_encoded().size(), 13);
    for (boolean isBinaryEncoded: hbaseTable.getBinary_encoded()) {
      // None of the columns should be binary encoded.
      Assert.assertTrue(!isBinaryEncoded);
    }

    Table newTable = Table.fromMetastoreTable(catalog.getNextTableId(),
        catalog.getDb(dbName), thriftTable.getMetastore_table());
    newTable.loadFromTTable(thriftTable);
    Assert.assertTrue(newTable instanceof HBaseTable);
    HBaseTable newHBaseTable = (HBaseTable) newTable;
    Assert.assertEquals(newHBaseTable.getColumns().size(), 13);
    Assert.assertEquals(newHBaseTable.getColumn("double_col").getType(),
        PrimitiveType.DOUBLE);
    Assert.assertEquals(newHBaseTable.getNumClusteringCols(), 1);
  }

  @Test
  public void TestHBaseTableWithBinaryEncodedCols()
      throws DatabaseNotFoundException, TableNotFoundException,
      TableLoadingException {
    String dbName = "functional_hbase";
    Table table = catalog.getTable(dbName, "alltypessmallbinary");
    TTable thriftTable = table.toThrift();
    Assert.assertEquals(thriftTable.tbl_name, "alltypessmallbinary");
    Assert.assertEquals(thriftTable.db_name, dbName);
    Assert.assertTrue(thriftTable.isSetTable_type());
    Assert.assertEquals(thriftTable.getPartition_columns().size(), 0);
    Assert.assertEquals(thriftTable.getTable_type(), TTableType.HBASE_TABLE);
    THBaseTable hbaseTable = thriftTable.getHbase_table();
    Assert.assertEquals(hbaseTable.getFamilies().size(), 13);
    Assert.assertEquals(hbaseTable.getQualifiers().size(), 13);
    Assert.assertEquals(hbaseTable.getBinary_encoded().size(), 13);

    // Count the number of columns that are binary encoded.
    int numBinaryEncodedCols = 0;
    for (boolean isBinaryEncoded: hbaseTable.getBinary_encoded()) {
      if (isBinaryEncoded) ++numBinaryEncodedCols;
    }
    Assert.assertEquals(numBinaryEncodedCols, 10);

    // Verify that creating a table from this thrift struct results in a valid
    // Table.
    Table newTable = Table.fromMetastoreTable(catalog.getNextTableId(),
        catalog.getDb(dbName), thriftTable.getMetastore_table());
    newTable.loadFromTTable(thriftTable);
    Assert.assertTrue(newTable instanceof HBaseTable);
    HBaseTable newHBaseTable = (HBaseTable) newTable;
    Assert.assertEquals(newHBaseTable.getColumns().size(), 13);
    Assert.assertEquals(newHBaseTable.getColumn("double_col").getType(),
        PrimitiveType.DOUBLE);
    Assert.assertEquals(newHBaseTable.getNumClusteringCols(), 1);
  }

  @Test
  public void TestTableLoadingErrors() throws DatabaseNotFoundException,
      TableNotFoundException, TableLoadingException {
    Table table = catalog.getTable("functional", "hive_index_tbl");
    TTable thriftTable = table.toThrift();
    Assert.assertEquals(thriftTable.tbl_name, "hive_index_tbl");
    Assert.assertEquals(thriftTable.db_name, "functional");
  }

  @Test
  public void TestView() throws DatabaseNotFoundException,
      TableNotFoundException, TableLoadingException {
    Table table = catalog.getTable("functional", "view_view");
    TTable thriftTable = table.toThrift();
    Assert.assertEquals(thriftTable.tbl_name, "view_view");
    Assert.assertEquals(thriftTable.db_name, "functional");
    Assert.assertFalse(thriftTable.isSetHdfs_table());
    Assert.assertFalse(thriftTable.isSetHbase_table());
    Assert.assertTrue(thriftTable.isSetMetastore_table());
  }
}

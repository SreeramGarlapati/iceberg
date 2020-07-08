/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.sql;

import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.spark.SparkCatalogTestBase;
import org.apache.iceberg.types.Types;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.when;

public class TestCreateTableAsSelect extends SparkCatalogTestBase {

  private final String sourceName;

  public TestCreateTableAsSelect(String catalogName, String implementation, Map<String, String> config) {
    super(catalogName, implementation, config);
    this.sourceName = tableName("source");

    sql("CREATE TABLE IF NOT EXISTS %s (id bigint NOT NULL, data string) " +
        "USING iceberg PARTITIONED BY (truncate(id, 3))", sourceName);
    sql("INSERT INTO %s VALUES (1, 'a'), (2, 'b'), (3, 'c')", sourceName);
  }

  @After
  public void removeTables() {
    sql("DROP TABLE IF EXISTS %s", tableName);
  }

  @Test
  public void testUnpartitionedCTAS() {
    sql("CREATE TABLE %s USING iceberg AS SELECT * FROM %s", tableName, sourceName);

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get())
    );

    Table ctasTable = validationCatalog.loadTable(tableIdent);

    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), ctasTable.schema().asStruct());
    Assert.assertEquals("Should be an unpartitioned table",
        0, ctasTable.spec().fields().size());
    assertEquals("Should have rows matching the source table",
        sql("SELECT * FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @Test
  public void testPartitionedCTAS() {
    sql("CREATE TABLE %s USING iceberg PARTITIONED BY (id) AS SELECT * FROM %s ORDER BY id", tableName, sourceName);

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get())
    );

    PartitionSpec expectedSpec = PartitionSpec.builderFor(expectedSchema)
        .identity("id")
        .build();

    Table ctasTable = validationCatalog.loadTable(tableIdent);

    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), ctasTable.schema().asStruct());
    Assert.assertEquals("Should be partitioned by id",
        expectedSpec, ctasTable.spec());
    assertEquals("Should have rows matching the source table",
        sql("SELECT * FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @Test
  public void testRTAS() {
    sql("CREATE TABLE %s USING iceberg AS SELECT * FROM %s", tableName, sourceName);

    assertEquals("Should have rows matching the source table",
        sql("SELECT * FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    sql("REPLACE TABLE %s USING iceberg PARTITIONED BY (part) AS " +
        "SELECT id, data, CASE WHEN (id %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
        "FROM %s ORDER BY 3, 1", tableName, sourceName);

    // spark_catalog does not use an atomic replace, so the table history and old spec is dropped
    // the other catalogs do use atomic replace, so the spec id is incremented
    boolean isAtomic = !"spark_catalog".equals(catalogName);

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get()),
        Types.NestedField.optional(3, "part", Types.StringType.get())
    );

    int specId = isAtomic ? 1 : 0;
    PartitionSpec expectedSpec = PartitionSpec.builderFor(expectedSchema)
        .identity("part")
        .withSpecId(specId)
        .build();

    Table rtasTable = validationCatalog.loadTable(tableIdent);

    // the replacement table has a different schema and partition spec than the original
    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), rtasTable.schema().asStruct());
    Assert.assertEquals("Should be partitioned by part",
        expectedSpec, rtasTable.spec());

    assertEquals("Should have rows matching the source table",
        sql("SELECT id, data, CASE WHEN (id %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
            "FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    Assert.assertEquals("Table should have expected snapshots",
        isAtomic ? 2 : 1, Iterables.size(rtasTable.snapshots()));
  }

  @Test
  public void testCreateRTAS() {
    sql("CREATE OR REPLACE TABLE %s USING iceberg PARTITIONED BY (part) AS " +
        "SELECT id, data, CASE WHEN (id %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
        "FROM %s ORDER BY 3, 1", tableName, sourceName);

    assertEquals("Should have rows matching the source table",
        sql("SELECT id, data, CASE WHEN (id %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
            "FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    sql("CREATE OR REPLACE TABLE %s USING iceberg PARTITIONED BY (part) AS " +
        "SELECT 2 * id as id, data, CASE WHEN ((2 * id) %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
        "FROM %s ORDER BY 3, 1", tableName, sourceName);

    // spark_catalog does not use an atomic replace, so the table history is dropped
    boolean isAtomic = !"spark_catalog".equals(catalogName);

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get()),
        Types.NestedField.optional(3, "part", Types.StringType.get())
    );

    PartitionSpec expectedSpec = PartitionSpec.builderFor(expectedSchema)
        .identity("part")
        .withSpecId(0) // the spec is identical and should be reused
        .build();

    Table rtasTable = validationCatalog.loadTable(tableIdent);

    // the replacement table has a different schema and partition spec than the original
    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), rtasTable.schema().asStruct());
    Assert.assertEquals("Should be partitioned by part",
        expectedSpec, rtasTable.spec());

    assertEquals("Should have rows matching the source table",
        sql("SELECT 2 * id, data, CASE WHEN ((2 * id) %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
            "FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    Assert.assertEquals("Table should have expected snapshots",
        isAtomic ? 2 : 1, Iterables.size(rtasTable.snapshots()));
  }

  @Test
  public void testDataFrameV2Create() throws Exception {
    spark.table(sourceName).writeTo(tableName).using("iceberg").create();

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get())
    );

    Table ctasTable = validationCatalog.loadTable(tableIdent);

    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), ctasTable.schema().asStruct());
    Assert.assertEquals("Should be an unpartitioned table",
        0, ctasTable.spec().fields().size());
    assertEquals("Should have rows matching the source table",
        sql("SELECT * FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @Test
  public void testDataFrameV2Replace() throws Exception {
    spark.table(sourceName).writeTo(tableName).using("iceberg").create();

    assertEquals("Should have rows matching the source table",
        sql("SELECT * FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    spark.table(sourceName)
        .select(
            col("id"),
            col("data"),
            when(col("id").mod(lit(2)).equalTo(lit(0)), lit("even")).otherwise("odd").as("part"))
        .orderBy("part", "id")
        .writeTo(tableName)
        .partitionedBy(col("part"))
        .using("iceberg")
        .replace();

    // spark_catalog does not use an atomic replace, so the table history and old spec is dropped
    // the other catalogs do use atomic replace, so the spec id is incremented
    boolean isAtomic = !"spark_catalog".equals(catalogName);

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get()),
        Types.NestedField.optional(3, "part", Types.StringType.get())
    );

    int specId = isAtomic ? 1 : 0;
    PartitionSpec expectedSpec = PartitionSpec.builderFor(expectedSchema)
        .identity("part")
        .withSpecId(specId)
        .build();

    Table rtasTable = validationCatalog.loadTable(tableIdent);

    // the replacement table has a different schema and partition spec than the original
    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), rtasTable.schema().asStruct());
    Assert.assertEquals("Should be partitioned by part",
        expectedSpec, rtasTable.spec());

    assertEquals("Should have rows matching the source table",
        sql("SELECT id, data, CASE WHEN (id %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
            "FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    Assert.assertEquals("Table should have expected snapshots",
        isAtomic ? 2 : 1, Iterables.size(rtasTable.snapshots()));
  }

  @Test
  public void testDataFrameV2CreateOrReplace() {
    spark.table(sourceName)
        .select(
            col("id"),
            col("data"),
            when(col("id").mod(lit(2)).equalTo(lit(0)), lit("even")).otherwise("odd").as("part"))
        .orderBy("part", "id")
        .writeTo(tableName)
        .partitionedBy(col("part"))
        .using("iceberg")
        .createOrReplace();

    assertEquals("Should have rows matching the source table",
        sql("SELECT id, data, CASE WHEN (id %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
            "FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    spark.table(sourceName)
        .select(col("id").multiply(lit(2)).as("id"), col("data"))
        .select(
            col("id"),
            col("data"),
            when(col("id").mod(lit(2)).equalTo(lit(0)), lit("even")).otherwise("odd").as("part"))
        .orderBy("part", "id")
        .writeTo(tableName)
        .partitionedBy(col("part"))
        .using("iceberg")
        .createOrReplace();

    // spark_catalog does not use an atomic replace, so the table history is dropped
    boolean isAtomic = !"spark_catalog".equals(catalogName);

    Schema expectedSchema = new Schema(
        Types.NestedField.optional(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "data", Types.StringType.get()),
        Types.NestedField.optional(3, "part", Types.StringType.get())
    );

    PartitionSpec expectedSpec = PartitionSpec.builderFor(expectedSchema)
        .identity("part")
        .withSpecId(0) // the spec is identical and should be reused
        .build();

    Table rtasTable = validationCatalog.loadTable(tableIdent);

    // the replacement table has a different schema and partition spec than the original
    Assert.assertEquals("Should have expected nullable schema",
        expectedSchema.asStruct(), rtasTable.schema().asStruct());
    Assert.assertEquals("Should be partitioned by part",
        expectedSpec, rtasTable.spec());

    assertEquals("Should have rows matching the source table",
        sql("SELECT 2 * id, data, CASE WHEN ((2 * id) %% 2) = 0 THEN 'even' ELSE 'odd' END AS part " +
            "FROM %s ORDER BY id", sourceName),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    Assert.assertEquals("Table should have expected snapshots",
        isAtomic ? 2 : 1, Iterables.size(rtasTable.snapshots()));
  }
}
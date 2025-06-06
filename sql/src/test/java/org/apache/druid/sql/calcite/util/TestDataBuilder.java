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

package org.apache.druid.sql.calcite.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.inject.Injector;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.data.input.ResourceInputSource;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.DoubleDimensionSchema;
import org.apache.druid.data.input.impl.JsonInputFormat;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.MapInputRowParser;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.parsers.JSONPathSpec;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.GlobalTableDataSource;
import org.apache.druid.query.InlineDataSource;
import org.apache.druid.query.NestedDataTestUtils;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.aggregation.FloatSumAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.first.DoubleFirstAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.first.LongFirstAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.first.StringFirstAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.last.DoubleLastAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.last.FloatLastAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.last.LongLastAggregatorFactory;
import org.apache.druid.query.aggregation.firstlast.last.StringLastAggregatorFactory;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesAggregatorFactory;
import org.apache.druid.query.lookup.LookupExtractorFactoryContainerProvider;
import org.apache.druid.segment.AutoTypeColumnSchema;
import org.apache.druid.segment.IndexBuilder;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.SegmentWrangler;
import org.apache.druid.segment.TestIndex;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.column.StringEncodingStrategy;
import org.apache.druid.segment.generator.GeneratorBasicSchemas;
import org.apache.druid.segment.generator.GeneratorSchemaInfo;
import org.apache.druid.segment.generator.SegmentGenerator;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IncrementalIndexSchema;
import org.apache.druid.segment.join.JoinConditionAnalysis;
import org.apache.druid.segment.join.Joinable;
import org.apache.druid.segment.join.JoinableFactory;
import org.apache.druid.segment.join.JoinableFactoryWrapper;
import org.apache.druid.segment.join.table.IndexedTableJoinable;
import org.apache.druid.segment.join.table.RowBasedIndexedTable;
import org.apache.druid.segment.transform.TransformSpec;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.apache.druid.server.QueryScheduler;
import org.apache.druid.server.QueryStackTests;
import org.apache.druid.server.SpecificSegmentsQuerySegmentWalker;
import org.apache.druid.sql.calcite.util.datasets.TestDataSet;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds a set of test data used by the Calcite query tests. The test data is
 * hard-coded as a set of segment builders wrapped in a segment walker. Call
 * {@link #createMockWalker(Injector, QueryRunnerFactoryConglomerate, File)},
 * or one of the variations, to create the test data.
 */
public class TestDataBuilder
{
  private static final ObjectMapper MAPPER = new DefaultObjectMapper();

  public static final String TIMESTAMP_COLUMN = "t";
  public static final GlobalTableDataSource CUSTOM_TABLE = new GlobalTableDataSource(CalciteTests.BROADCAST_DATASOURCE);

  public static QueryableIndex QUERYABLE_INDEX_FOR_BENCHMARK_DATASOURCE = null;

  public static final JoinableFactory CUSTOM_ROW_TABLE_JOINABLE = new JoinableFactory()
  {
    @Override
    public boolean isDirectlyJoinable(DataSource dataSource)
    {
      return CUSTOM_TABLE.equals(dataSource);
    }

    @Override
    public Optional<Joinable> build(
        DataSource dataSource,
        JoinConditionAnalysis condition
    )
    {
      if (dataSource instanceof GlobalTableDataSource) {
        return Optional.of(new IndexedTableJoinable(JOINABLE_TABLE));
      }
      return Optional.empty();
    }
  };

  public static final JsonInputFormat DEFAULT_JSON_INPUT_FORMAT = new JsonInputFormat(
      JSONPathSpec.DEFAULT,
      null,
      null,
      null,
      null
  );

  private static final InputRowSchema FOO_SCHEMA = new InputRowSchema(
      new TimestampSpec(TIMESTAMP_COLUMN, "iso", null),
      new DimensionsSpec(
          DimensionsSpec.getDefaultSchemas(ImmutableList.of("dim1", "dim2", "dim3"))
      ),
      null
  );

  private static final InputRowSchema LOTS_OF_COLUMNS_SCHEMA = new InputRowSchema(
      new TimestampSpec("timestamp", "millis", null),
      new DimensionsSpec(
          DimensionsSpec.getDefaultSchemas(
              ImmutableList.<String>builder().add("dimHyperUnique")
                           .add("dimMultivalEnumerated")
                           .add("dimMultivalEnumerated2")
                           .add("dimMultivalSequentialWithNulls")
                           .add("dimSequential")
                           .add("dimSequentialHalfNull")
                           .add("dimUniform")
                           .add("dimZipf")
                           .add("metFloatNormal")
                           .add("metFloatZipf")
                           .add("metLongSequential")
                           .add("metLongUniform")
                           .build()
          )
      ),
      null
  );


  public static final IncrementalIndexSchema INDEX_SCHEMA = new IncrementalIndexSchema.Builder()
      .withMetrics(
          new CountAggregatorFactory("cnt"),
          new FloatSumAggregatorFactory("m1", "m1"),
          new DoubleSumAggregatorFactory("m2", "m2"),
          new HyperUniquesAggregatorFactory("unique_dim1", "dim1")
      )
      .withRollup(false)
      .build();

  private static final IncrementalIndexSchema INDEX_SCHEMA_DIFFERENT_DIM3_M1_TYPES = new IncrementalIndexSchema.Builder()
      .withDimensionsSpec(
          new DimensionsSpec(
              ImmutableList.of(
                  new StringDimensionSchema("dim1"),
                  new StringDimensionSchema("dim2"),
                  new LongDimensionSchema("dim3")
              )
          )
      )
      .withMetrics(
          new CountAggregatorFactory("cnt"),
          new LongSumAggregatorFactory("m1", "m1"),
          new DoubleSumAggregatorFactory("m2", "m2"),
          new HyperUniquesAggregatorFactory("unique_dim1", "dim1")
      )
      .withRollup(false)
      .build();

  private static final IncrementalIndexSchema INDEX_SCHEMA_WITH_X_COLUMNS = new IncrementalIndexSchema.Builder()
      .withMetrics(
          new CountAggregatorFactory("cnt_x"),
          new FloatSumAggregatorFactory("m1_x", "m1_x"),
          new DoubleSumAggregatorFactory("m2_x", "m2_x"),
          new HyperUniquesAggregatorFactory("unique_dim1_x", "dim1_x")
      )
      .withRollup(false)
      .build();

  public static final IncrementalIndexSchema INDEX_SCHEMA_NUMERIC_DIMS = TestDataSet.NUMFOO.getIndexSchema();

  public static final IncrementalIndexSchema INDEX_SCHEMA_LOTS_O_COLUMNS = new IncrementalIndexSchema.Builder()
      .withMetrics(
          new CountAggregatorFactory("count")
      )
      .withDimensionsSpec(LOTS_OF_COLUMNS_SCHEMA.getDimensionsSpec())
      .withRollup(false)
      .build();

  private static final List<String> USER_VISIT_DIMS = ImmutableList.of("user", "country", "city");

  public static final List<ImmutableMap<String, Object>> RAW_ROWS1 = ImmutableList.of(
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-01")
                  .put("m1", "1.0")
                  .put("m2", "1.0")
                  .put("dim1", "")
                  .put("dim2", ImmutableList.of("a"))
                  .put("dim3", ImmutableList.of("a", "b"))
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-02")
                  .put("m1", "2.0")
                  .put("m2", "2.0")
                  .put("dim1", "10.1")
                  .put("dim2", ImmutableList.of())
                  .put("dim3", ImmutableList.of("b", "c"))
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-03")
                  .put("m1", "3.0")
                  .put("m2", "3.0")
                  .put("dim1", "2")
                  .put("dim2", ImmutableList.of(""))
                  .put("dim3", ImmutableList.of("d"))
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2001-01-01")
                  .put("m1", "4.0")
                  .put("m2", "4.0")
                  .put("dim1", "1")
                  .put("dim2", ImmutableList.of("a"))
                  .put("dim3", ImmutableList.of(""))
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2001-01-02")
                  .put("m1", "5.0")
                  .put("m2", "5.0")
                  .put("dim1", "def")
                  .put("dim2", ImmutableList.of("abc"))
                  .put("dim3", ImmutableList.of())
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2001-01-03")
                  .put("m1", "6.0")
                  .put("m2", "6.0")
                  .put("dim1", "abc")
                  .build()
  );

  public static final List<InputRow> RAW_ROWS1_X = ImmutableList.of(
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("t", "2000-01-01")
                      .put("m1_x", "1.0")
                      .put("m2_x", "1.0")
                      .put("dim1_x", "")
                      .put("dim2_x", ImmutableList.of("a"))
                      .put("dim3_x", ImmutableList.of("a", "b"))
                      .build()
      ),
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("t", "2000-01-02")
                      .put("m1_x", "2.0")
                      .put("m2_x", "2.0")
                      .put("dim1_x", "10.1")
                      .put("dim2_x", ImmutableList.of())
                      .put("dim3_x", ImmutableList.of("b", "c"))
                      .build()
      ),
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("t", "2000-01-03")
                      .put("m1_x", "3.0")
                      .put("m2_x", "3.0")
                      .put("dim1_x", "2")
                      .put("dim2_x", ImmutableList.of(""))
                      .put("dim3_x", ImmutableList.of("d"))
                      .build()
      ),
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("t", "2001-01-01")
                      .put("m1_x", "4.0")
                      .put("m2_x", "4.0")
                      .put("dim1_x", "1")
                      .put("dim2_x", ImmutableList.of("a"))
                      .put("dim3_x", ImmutableList.of(""))
                      .build()
      ),
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("t", "2001-01-02")
                      .put("m1_x", "5.0")
                      .put("m2_x", "5.0")
                      .put("dim1_x", "def")
                      .put("dim2_x", ImmutableList.of("abc"))
                      .put("dim3_x", ImmutableList.of())
                      .build()
      ),
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("t", "2001-01-03")
                      .put("m1_x", "6.0")
                      .put("m2_x", "6.0")
                      .put("dim1_x", "abc")
                      .build()
      )
  );

  public static final List<InputRow> ROWS1 =
      RAW_ROWS1.stream().map(TestDataBuilder::createRow).collect(Collectors.toList());

  public static final List<ImmutableMap<String, Object>> RAW_ROWS1_WITH_NUMERIC_DIMS = ImmutableList.of(
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-01")
                  .put("m1", "1.0")
                  .put("m2", "1.0")
                  .put("dbl1", 1.0)
                  .put("f1", 1.0f)
                  .put("l1", 7L)
                  .put("dim1", "")
                  .put("dim2", ImmutableList.of("a"))
                  .put("dim3", ImmutableList.of("a", "b"))
                  .put("dim4", "a")
                  .put("dim5", "aa")
                  .put("dim6", "1")
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-02")
                  .put("m1", "2.0")
                  .put("m2", "2.0")
                  .put("dbl1", 1.7)
                  .put("dbl2", 1.7)
                  .put("f1", 0.1f)
                  .put("f2", 0.1f)
                  .put("l1", 325323L)
                  .put("l2", 325323L)
                  .put("dim1", "10.1")
                  .put("dim2", ImmutableList.of())
                  .put("dim3", ImmutableList.of("b", "c"))
                  .put("dim4", "a")
                  .put("dim5", "ab")
                  .put("dim6", "2")
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-03")
                  .put("m1", "3.0")
                  .put("m2", "3.0")
                  .put("dbl1", 0.0)
                  .put("dbl2", 0.0)
                  .put("f1", 0.0)
                  .put("f2", 0.0)
                  .put("l1", 0)
                  .put("l2", 0)
                  .put("dim1", "2")
                  .put("dim2", ImmutableList.of(""))
                  .put("dim3", ImmutableList.of("d"))
                  .put("dim4", "a")
                  .put("dim5", "ba")
                  .put("dim6", "3")
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2001-01-01")
                  .put("m1", "4.0")
                  .put("m2", "4.0")
                  .put("dim1", "1")
                  .put("dim2", ImmutableList.of("a"))
                  .put("dim3", ImmutableList.of(""))
                  .put("dim4", "b")
                  .put("dim5", "ad")
                  .put("dim6", "4")
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2001-01-02")
                  .put("m1", "5.0")
                  .put("m2", "5.0")
                  .put("dim1", "def")
                  .put("dim2", ImmutableList.of("abc"))
                  .put("dim3", ImmutableList.of())
                  .put("dim4", "b")
                  .put("dim5", "aa")
                  .put("dim6", "5")
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2001-01-03")
                  .put("m1", "6.0")
                  .put("m2", "6.0")
                  .put("dim1", "abc")
                  .put("dim4", "b")
                  .put("dim5", "ab")
                  .put("dim6", "6")
                  .build()
  );
  public static final List<InputRow> ROWS1_WITH_NUMERIC_DIMS = ImmutableList.copyOf(TestDataSet.NUMFOO.getRows());

  public static final List<ImmutableMap<String, Object>> RAW_ROWS2 = ImmutableList.of(
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-01")
                  .put("dim1", "דרואיד")
                  .put("dim2", "he")
                  .put("dim3", 10L)
                  .put("m1", 1.0)
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-01")
                  .put("dim1", "druid")
                  .put("dim2", "en")
                  .put("dim3", 11L)
                  .put("m1", 1.0)
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-01")
                  .put("dim1", "друид")
                  .put("dim2", "ru")
                  .put("dim3", 12L)
                  .put("m1", 1.0)
                  .build()
  );
  public static final List<InputRow> ROWS2 =
      RAW_ROWS2.stream().map(TestDataBuilder::createRow).collect(Collectors.toList());

  public static final List<ImmutableMap<String, Object>> RAW_ROWS1_WITH_FULL_TIMESTAMP = ImmutableList.of(
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-01T10:51:45.695Z")
                  .put("m1", "1.0")
                  .put("m2", "1.0")
                  .put("dim1", "")
                  .put("dim2", ImmutableList.of("a"))
                  .put("dim3", ImmutableList.of("a", "b"))
                  .build(),
      ImmutableMap.<String, Object>builder()
                  .put("t", "2000-01-18T10:51:45.695Z")
                  .put("m1", "2.0")
                  .put("m2", "2.0")
                  .put("dim1", "10.1")
                  .put("dim2", ImmutableList.of())
                  .put("dim3", ImmutableList.of("b", "c"))
                  .build()
  );
  public static final List<InputRow> ROWS1_WITH_FULL_TIMESTAMP =
      RAW_ROWS1_WITH_FULL_TIMESTAMP.stream().map(TestDataBuilder::createRow).collect(Collectors.toList());


  public static final List<InputRow> FORBIDDEN_ROWS = ImmutableList.of(
      createRow("2000-01-01", "forbidden", "abcd", 9999.0),
      createRow("2000-01-02", "forbidden", "a", 1234.0)
  );

  // Hi, I'm Troy McClure. You may remember these rows from such benchmarks generator schemas as basic and expression
  public static final List<InputRow> ROWS_LOTS_OF_COLUMNS = ImmutableList.of(
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("timestamp", 1576306800000L)
                      .put("metFloatZipf", 147.0)
                      .put("dimMultivalSequentialWithNulls", Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8"))
                      .put("dimMultivalEnumerated2", Arrays.asList(null, "Orange", "Apple"))
                      .put("metLongUniform", 372)
                      .put("metFloatNormal", 5000.0)
                      .put("dimZipf", "27")
                      .put("dimUniform", "74416")
                      .put("dimMultivalEnumerated", Arrays.asList("Baz", "World", "Hello", "Baz"))
                      .put("metLongSequential", 0)
                      .put("dimHyperUnique", "0")
                      .put("dimSequential", "0")
                      .put("dimSequentialHalfNull", "0")
                      .build(),
          LOTS_OF_COLUMNS_SCHEMA
      ),
      createRow(
          ImmutableMap.<String, Object>builder()
                      .put("timestamp", 1576306800000L)
                      .put("metFloatZipf", 25.0)
                      .put("dimMultivalEnumerated2", Arrays.asList("Xylophone", null, "Corundum"))
                      .put("metLongUniform", 252)
                      .put("metFloatNormal", 4999.0)
                      .put("dimZipf", "9")
                      .put("dimUniform", "50515")
                      .put("dimMultivalEnumerated", Arrays.asList("Baz", "World", "ㅑ ㅓ ㅕ ㅗ ㅛ ㅜ ㅠ ㅡ ㅣ"))
                      .put("metLongSequential", 8)
                      .put("dimHyperUnique", "8")
                      .put("dimSequential", "8")
                      .build(),
          LOTS_OF_COLUMNS_SCHEMA
      )
  );

  public static List<InputRow> USER_VISIT_ROWS = ImmutableList.of(
      toRow(
          "2021-01-01T01:00:00Z",
          USER_VISIT_DIMS,
          ImmutableMap.of("user", "alice", "country", "canada", "city", "A")
      ),
      toRow(
          "2021-01-01T02:00:00Z",
          USER_VISIT_DIMS,
          ImmutableMap.of("user", "alice", "country", "canada", "city", "B")
      ),
      toRow("2021-01-01T03:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "bob", "country", "canada", "city", "A")),
      toRow("2021-01-01T04:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "alice", "country", "India", "city", "Y")),
      toRow(
          "2021-01-02T01:00:00Z",
          USER_VISIT_DIMS,
          ImmutableMap.of("user", "alice", "country", "canada", "city", "A")
      ),
      toRow("2021-01-02T02:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "bob", "country", "canada", "city", "A")),
      toRow("2021-01-02T03:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "foo", "country", "canada", "city", "B")),
      toRow("2021-01-02T04:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "bar", "country", "canada", "city", "B")),
      toRow("2021-01-02T05:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "alice", "country", "India", "city", "X")),
      toRow("2021-01-02T06:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "bob", "country", "India", "city", "X")),
      toRow("2021-01-02T07:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "foo", "country", "India", "city", "X")),
      toRow("2021-01-03T01:00:00Z", USER_VISIT_DIMS, ImmutableMap.of("user", "foo", "country", "USA", "city", "M"))
  );

  private static final InlineDataSource JOINABLE_BACKING_DATA = InlineDataSource.fromIterable(
      RAW_ROWS1_WITH_NUMERIC_DIMS.stream().map(x -> new Object[]{
          x.get("dim1"),
          x.get("dim2"),
          x.get("dim3"),
          x.get("dim4"),
          x.get("dim5"),
          x.get("dbl1"),
          x.get("dbl2"),
          x.get("f1"),
          x.get("f2"),
          x.get("l1"),
          x.get("l2")
      }).collect(Collectors.toList()),
      RowSignature.builder()
                  .add("dim1", ColumnType.STRING)
                  .add("dim2", ColumnType.STRING)
                  .add("dim3", ColumnType.STRING)
                  .add("dim4", ColumnType.STRING)
                  .add("dim5", ColumnType.STRING)
                  .add("dbl1", ColumnType.DOUBLE)
                  .add("dbl2", ColumnType.DOUBLE)
                  .add("f1", ColumnType.FLOAT)
                  .add("f2", ColumnType.FLOAT)
                  .add("l1", ColumnType.LONG)
                  .add("l2", ColumnType.LONG)
                  .build()
  );

  private static final Set<String> KEY_COLUMNS = ImmutableSet.of("dim4");

  private static final RowBasedIndexedTable JOINABLE_TABLE = new RowBasedIndexedTable(
      JOINABLE_BACKING_DATA.getRowsAsList(),
      JOINABLE_BACKING_DATA.rowAdapter(),
      JOINABLE_BACKING_DATA.getRowSignature(),
      KEY_COLUMNS,
      DateTimes.nowUtc().toString()
  );

  public static QueryableIndex makeWikipediaIndex(File tmpDir)
  {
    try {
      final File directory = new File(tmpDir, StringUtils.format("wikipedia-index-%s", UUID.randomUUID()));
      final IncrementalIndex index = TestIndex.makeWikipediaIncrementalIndex();
      TestIndex.INDEX_MERGER.persist(index, directory, IndexSpec.DEFAULT, null);
      return TestIndex.INDEX_IO.loadIndex(directory);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static QueryableIndex makeWikipediaIndexWithAggregation(File tmpDir)
  {
    final List<DimensionSchema> dimensions = Arrays.asList(
        new StringDimensionSchema("channel"),
        new StringDimensionSchema("cityName"),
        new StringDimensionSchema("comment"),
        new StringDimensionSchema("countryIsoCode"),
        new StringDimensionSchema("countryName"),
        new StringDimensionSchema("isAnonymous"),
        new StringDimensionSchema("isMinor"),
        new StringDimensionSchema("isNew"),
        new StringDimensionSchema("isRobot"),
        new StringDimensionSchema("isUnpatrolled"),
        new StringDimensionSchema("metroCode"),
        new StringDimensionSchema("namespace"),
        new StringDimensionSchema("page"),
        new StringDimensionSchema("regionIsoCode"),
        new StringDimensionSchema("regionName"),
        new StringDimensionSchema("user")
    );

    return IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "wikipedia1"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(new IncrementalIndexSchema.Builder()
                    .withRollup(true)
                    .withTimestampSpec(new TimestampSpec("time", null, null))
                    .withDimensionsSpec(new DimensionsSpec(dimensions))
                    .withMetrics(
                        new LongLastAggregatorFactory("long_last_added", "added", "__time"),
                        new LongFirstAggregatorFactory("long_first_added", "added", "__time"),
                        new FloatLastAggregatorFactory("float_last_added", "added", "__time"),
                        new FloatLastAggregatorFactory("float_first_added", "added", "__time"),
                        new DoubleLastAggregatorFactory("double_last_added", "added", "__time"),
                        new DoubleFirstAggregatorFactory("double_first_added", "added", "__time"),
                        new StringFirstAggregatorFactory("string_first_added", "comment", "__time", 1000),
                        new StringLastAggregatorFactory("string_last_added", "comment", "__time", 1000)
                    )
                    .build()
        )
        .inputSource(
            ResourceInputSource.of(
                TestIndex.class.getClassLoader(),
                "wikipedia/wikiticker-2015-09-12-sampled.json.gz"
            )
        )
        .inputFormat(DEFAULT_JSON_INPUT_FORMAT)
        .inputTmpDir(new File(tmpDir, "tmpWikipedia1"))
        .buildMMappedIndex();
  }

  public static SpecificSegmentsQuerySegmentWalker createMockWalker(
      final Injector injector,
      final QueryRunnerFactoryConglomerate conglomerate,
      final File tmpDir
  )
  {
    return createMockWalker(
        injector,
        conglomerate,
        tmpDir,
        QueryStackTests.DEFAULT_NOOP_SCHEDULER,
        QueryFrameworkUtils.createDefaultJoinableFactory(injector)
    );
  }

  public static SpecificSegmentsQuerySegmentWalker createMockWalker(
      final Injector injector,
      final QueryRunnerFactoryConglomerate conglomerate,
      final File tmpDir,
      final QueryScheduler scheduler
  )
  {
    return createMockWalker(
        injector,
        conglomerate,
        tmpDir,
        scheduler,
        (JoinableFactory) null
    );
  }

  public static SpecificSegmentsQuerySegmentWalker createMockWalker(
      final Injector injector,
      final QueryRunnerFactoryConglomerate conglomerate,
      final File tmpDir,
      final QueryScheduler scheduler,
      final JoinableFactory joinableFactory
  )
  {
    final JoinableFactory joinableFactoryToUse;
    if (joinableFactory == null) {
      joinableFactoryToUse = QueryStackTests.makeJoinableFactoryForLookup(
          injector.getInstance(LookupExtractorFactoryContainerProvider.class)
      );
    } else {
      joinableFactoryToUse = joinableFactory;
    }
    return createMockWalker(
        injector,
        conglomerate,
        tmpDir,
        scheduler,
        new JoinableFactoryWrapper(joinableFactoryToUse)
    );
  }

  @SuppressWarnings("resource")
  public static SpecificSegmentsQuerySegmentWalker createMockWalker(
      final Injector injector,
      final QueryRunnerFactoryConglomerate conglomerate,
      final File tmpDir,
      final QueryScheduler scheduler,
      final JoinableFactoryWrapper joinableFactoryWrapper)
  {
    SpecificSegmentsQuerySegmentWalker walker = SpecificSegmentsQuerySegmentWalker.createWalker(
        injector,
        conglomerate,
        injector.getInstance(SegmentWrangler.class),
        joinableFactoryWrapper,
        scheduler
    );
    return addDataSetsToWalker(tmpDir, walker);
  }

  @SuppressWarnings("resource")
  public static SpecificSegmentsQuerySegmentWalker addDataSetsToWalker(
      final File tmpDir,
      SpecificSegmentsQuerySegmentWalker walker
  )
  {
    final QueryableIndex index1 = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "1"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA)
        .rows(ROWS1)
        .buildMMappedIndex();

    final QueryableIndex index2 = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "2"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA_DIFFERENT_DIM3_M1_TYPES)
        .rows(ROWS2)
        .buildMMappedIndex();

    final QueryableIndex forbiddenIndex = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "forbidden"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA)
        .rows(FORBIDDEN_ROWS)
        .buildMMappedIndex();

    final QueryableIndex index4 = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "4"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA)
        .rows(ROWS1_WITH_FULL_TIMESTAMP)
        .buildMMappedIndex();

    final QueryableIndex indexLotsOfColumns = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "5"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA_LOTS_O_COLUMNS)
        .rows(ROWS_LOTS_OF_COLUMNS)
        .buildMMappedIndex();

    final QueryableIndex someDatasourceIndex = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "6"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA)
        .rows(ROWS1)
        .buildMMappedIndex();

    final QueryableIndex someXDatasourceIndex = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "7"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA_WITH_X_COLUMNS)
        .rows(RAW_ROWS1_X)
        .buildMMappedIndex();

    final QueryableIndex userVisitIndex = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "8"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(INDEX_SCHEMA)
        .rows(USER_VISIT_ROWS)
        .buildMMappedIndex();

    final QueryableIndex arraysIndex = IndexBuilder
        .create()
        .tmpDir(new File(tmpDir, "9"))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(
            new IncrementalIndexSchema.Builder()
                .withTimestampSpec(NestedDataTestUtils.AUTO_SCHEMA.getTimestampSpec())
                .withDimensionsSpec(NestedDataTestUtils.AUTO_SCHEMA.getDimensionsSpec())
                .withMetrics(
                    new CountAggregatorFactory("cnt")
                )
                .withRollup(false)
                .build()
        )
        .inputSource(
            ResourceInputSource.of(
                NestedDataTestUtils.class.getClassLoader(),
                NestedDataTestUtils.ARRAY_TYPES_DATA_FILE
            )
        )
        .inputFormat(TestDataBuilder.DEFAULT_JSON_INPUT_FORMAT)
        .inputTmpDir(new File(tmpDir, "9-input"))
        .buildMMappedIndex();

    return walker.add(
        DataSegment.builder()
                   .dataSource(CalciteTests.DATASOURCE1)
                   .interval(index1.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        index1
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.DATASOURCE2)
                   .interval(index2.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        index2
    ).add(
       DataSegment.builder()
                  .dataSource(CalciteTests.RESTRICTED_DATASOURCE)
                  .interval(index1.getDataInterval())
                  .version("1")
                  .shardSpec(new LinearShardSpec(0))
                  .size(0)
                  .build(),
       index1
   ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.FORBIDDEN_DATASOURCE)
                   .interval(forbiddenIndex.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        forbiddenIndex
    ).add(
        TestDataSet.NUMFOO,
        new File(tmpDir, "3")
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.DATASOURCE4)
                   .interval(index4.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        index4
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.DATASOURCE5)
                   .interval(indexLotsOfColumns.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        indexLotsOfColumns
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.SOME_DATASOURCE)
                   .interval(indexLotsOfColumns.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        someDatasourceIndex
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.SOMEXDATASOURCE)
                   .interval(indexLotsOfColumns.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        someXDatasourceIndex
    ).add(
        TestDataSet.BROADCAST,
        new File(tmpDir, "3a")
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.USERVISITDATASOURCE)
                   .interval(userVisitIndex.getDataInterval())
                   .version("1")
                   .shardSpec(new LinearShardSpec(0))
                   .size(0)
                   .build(),
        userVisitIndex
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.WIKIPEDIA)
                   .interval(Intervals.of("2015-09-12/2015-09-13"))
                   .version("1")
                   .shardSpec(new NumberedShardSpec(0, 0))
                   .size(0)
                   .build(),
        makeWikipediaIndex(tmpDir)
    ).add(
      DataSegment.builder()
                 .dataSource(CalciteTests.WIKIPEDIA_FIRST_LAST)
                 .interval(Intervals.of("2015-09-12/2015-09-13"))
                 .version("1")
                 .shardSpec(new NumberedShardSpec(0, 0))
                 .size(0)
                 .build(),
      makeWikipediaIndexWithAggregation(tmpDir)
    ).add(
        DataSegment.builder()
                   .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                   .version("1")
                   .interval(arraysIndex.getDataInterval())
                   .shardSpec(new LinearShardSpec(1))
                   .size(0)
                   .build(),
        arraysIndex
    );
  }

  public static void attachIndexesForDrillTestDatasources(SpecificSegmentsQuerySegmentWalker segmentWalker, File tmpDir)
  {
    attachIndexForDrillTestDatasource(segmentWalker, CalciteTests.TBL_WITH_NULLS_PARQUET, tmpDir);
    attachIndexForDrillTestDatasource(segmentWalker, CalciteTests.SML_TBL_PARQUET, tmpDir);
    attachIndexForDrillTestDatasource(segmentWalker, CalciteTests.ALL_TYPES_UNIQ_PARQUET, tmpDir);
    attachIndexForDrillTestDatasource(segmentWalker, CalciteTests.FEW_ROWS_ALL_DATA_PARQUET, tmpDir);
    attachIndexForDrillTestDatasource(segmentWalker, CalciteTests.T_ALL_TYPE_PARQUET, tmpDir);
  }

  public static void attachIndexesForBenchmarkDatasource(SpecificSegmentsQuerySegmentWalker segmentWalker)
  {
    final QueryableIndex queryableIndex = getQueryableIndexForBenchmarkDatasource();

    segmentWalker.add(
        DataSegment.builder()
                   .dataSource(CalciteTests.BENCHMARK_DATASOURCE)
                   .interval(Intervals.ETERNITY)
                   .version("1")
                   .shardSpec(new NumberedShardSpec(0, 0))
                   .size(0)
                   .build(),
        queryableIndex);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void attachIndexForDrillTestDatasource(
      SpecificSegmentsQuerySegmentWalker segmentWalker,
      String dataSource,
      File tmpDir
  )
  {
    final QueryableIndex queryableIndex = getQueryableIndexForDrillDatasource(dataSource, tmpDir);

    segmentWalker.add(
        DataSegment.builder()
                   .dataSource(dataSource)
                   .interval(Intervals.ETERNITY)
                   .version("1")
                   .shardSpec(new NumberedShardSpec(0, 0))
                   .size(0)
                   .build(),
        queryableIndex);
  }

  public static QueryableIndex getQueryableIndexForDrillDatasource(String datasource, File parentTempDir)
  {
    final IncrementalIndexSchema indexSchema = new IncrementalIndexSchema.Builder()
        .withDimensionsSpec(getDimensionSpecForDrillDatasource(datasource))
        .withRollup(false)
        .build();
    Iterable<InputRow> inputRowsForDrillDatasource = getInputRowsForDrillDatasource(datasource);
    return IndexBuilder
        .create()
        .tmpDir(new File(parentTempDir, datasource))
        .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
        .schema(indexSchema)
        .rows(inputRowsForDrillDatasource)
        .buildMMappedIndex();
  }

  public static QueryableIndex getQueryableIndexForBenchmarkDatasource()
  {
    if (QUERYABLE_INDEX_FOR_BENCHMARK_DATASOURCE == null) {
      throw new RuntimeException("Queryable index was not populated for benchmark datasource.");
    }
    return QUERYABLE_INDEX_FOR_BENCHMARK_DATASOURCE;
  }

  public static void makeQueryableIndexForBenchmarkDatasource(Closer closer, int rowsPerSegment)
  {
    if (closer == null) {
      throw new RuntimeException("Closer not supplied for generating segments, exiting.");
    }

    final GeneratorSchemaInfo schemaInfo = GeneratorBasicSchemas.SCHEMA_MAP.get("basic");
    final DataSegment dataSegment = schemaInfo.makeSegmentDescriptor(CalciteTests.BENCHMARK_DATASOURCE);
    final SegmentGenerator segmentGenerator = closer.register(new SegmentGenerator());

    List<DimensionSchema> columnSchemas = schemaInfo.getDimensionsSpec()
                                                    .getDimensions()
                                                    .stream()
                                                    .map(x -> new AutoTypeColumnSchema(x.getName(), null))
                                                    .collect(Collectors.toList());
    QUERYABLE_INDEX_FOR_BENCHMARK_DATASOURCE = segmentGenerator.generate(
        dataSegment,
        schemaInfo,
        DimensionsSpec.builder().setDimensions(columnSchemas).build(),
        TransformSpec.NONE,
        IndexSpec.builder().withStringDictionaryEncoding(new StringEncodingStrategy.Utf8()).build(),
        Granularities.NONE,
        rowsPerSegment
    );
  }

  private static DimensionsSpec getDimensionSpecForDrillDatasource(String datasource)
  {
    switch (datasource) {
      case CalciteTests.TBL_WITH_NULLS_PARQUET: {
        return new DimensionsSpec(
            ImmutableList.of(
                new LongDimensionSchema("c1"),
                new StringDimensionSchema("c2")
            )
        );
      }
      case CalciteTests.SML_TBL_PARQUET: {
        return new DimensionsSpec(
            ImmutableList.of(
                // "col_int": 8122,
                new LongDimensionSchema("col_int"),
                // "col_bgint": 817200,
                new LongDimensionSchema("col_bgint"),
                // "col_char_2": "IN",
                new StringDimensionSchema("col_char_2"),
                // "col_vchar_52":
                // "AXXXXXXXXXXXXXXXXXXXXXXXXXCXXXXXXXXXXXXXXXXXXXXXXXXB",
                new StringDimensionSchema("col_vchar_52"),
                // "col_tmstmp": 1409617682418,
                new LongDimensionSchema("col_tmstmp"),
                // "col_dt": 422717616000000,
                new LongDimensionSchema("col_dt"),
                // "col_booln": false,
                new StringDimensionSchema("col_booln"),
                // "col_dbl": 12900.48,
                new DoubleDimensionSchema("col_dbl"),
                // "col_tm": 33109170
                new LongDimensionSchema("col_tm")
            )
        );
      }
      case CalciteTests.ALL_TYPES_UNIQ_PARQUET: {
        // {"col0":1,"col1":65534,"col2":256.0,"col3":1234.9,"col4":73578580,"col5":1393720082338,"col6":421185052800000,"col7":false,"col8":"CA","col9":"AXXXXXXXXXXXXXXXXXXXXXXXXXCXXXXXXXXXXXXXXXXXXXXXXXXZ"}
        return new DimensionsSpec(
            ImmutableList.of(
                new LongDimensionSchema("col0"),
                new LongDimensionSchema("col1"),
                new DoubleDimensionSchema("col2"),
                new DoubleDimensionSchema("col3"),
                new LongDimensionSchema("col4"),
                new LongDimensionSchema("col5"),
                new LongDimensionSchema("col6"),
                new StringDimensionSchema("col7"),
                new StringDimensionSchema("col8"),
                new StringDimensionSchema("col9")
            )
        );
      }
      case CalciteTests.FEW_ROWS_ALL_DATA_PARQUET: {
        return new DimensionsSpec(
            ImmutableList.of(
                // "col0":12024,
                new LongDimensionSchema("col0"),
                // "col1":307168,
                new LongDimensionSchema("col1"),
                // "col2":"VT",
                new StringDimensionSchema("col2"),
                // "col3":"DXXXXXXXXXXXXXXXXXXXXXXXXXEXXXXXXXXXXXXXXXXXXXXXXXXF",
                new StringDimensionSchema("col3"),
                // "col4":1338596882419,
                new LongDimensionSchema("col4"),
                // "col5":422705433600000,
                new LongDimensionSchema("col5"),
                // "col6":true,
                new StringDimensionSchema("col6"),
                // "col7":3.95110006277E8,
                new DoubleDimensionSchema("col7"),
                // "col8":67465430
                new LongDimensionSchema("col8")
            )
        );
      }
      case CalciteTests.T_ALL_TYPE_PARQUET: {
        return new DimensionsSpec(
            ImmutableList.of(
                // "c1":1,
                new LongDimensionSchema("c1"),
                // "c2":592475043,
                new LongDimensionSchema("c2"),
                // "c3":616080519999272,
                new LongDimensionSchema("c3"),
                // "c4":"ObHeWTDEcbGzssDwPwurfs",
                new StringDimensionSchema("c4"),
                // "c5":"0sZxIfZ CGwTOaLWZ6nWkUNx",
                new StringDimensionSchema("c5"),
                // "c6":1456290852307,
                new LongDimensionSchema("c6"),
                // "c7":421426627200000,
                new LongDimensionSchema("c7"),
                // "c8":true,
                new StringDimensionSchema("c8"),
                // "c9":0.626179100469
                new DoubleDimensionSchema("c9")
            )
        );
      }
      default:
        throw new RuntimeException("Invalid datasource supplied for drill tests");
    }
  }

  private static Iterable<InputRow> getInputRowsForDrillDatasource(String datasource)
  {
    DimensionsSpec dimensionSpecForDrillDatasource = getDimensionSpecForDrillDatasource(datasource);
    return () -> {
      try {
        return Iterators.transform(
              MAPPER.readerFor(Map.class)
                    .readValues(
                        ClassLoader.getSystemResource("drill/window/datasources/" + datasource + ".json")),
              (Function<Map, InputRow>) input -> new MapBasedInputRow(0, dimensionSpecForDrillDatasource.getDimensionNames(), input)
        );
      }
      catch (IOException e) {
        throw new RE(e, "problem reading file");
      }
    };
  }

  private static MapBasedInputRow toRow(String time, List<String> dimensions, Map<String, Object> event)
  {
    return new MapBasedInputRow(DateTimes.ISO_DATE_OPTIONAL_TIME.parse(time), dimensions, event);
  }

  public static InputRow createRow(final Map<String, ?> map)
  {
    return MapInputRowParser.parse(FOO_SCHEMA, (Map<String, Object>) map);
  }

  public static InputRow createRow(final Map<String, ?> map, InputRowSchema inputRowSchema)
  {
    return MapInputRowParser.parse(inputRowSchema, (Map<String, Object>) map);
  }

  public static InputRow createRow(final Object t, final String dim1, final String dim2, final double m1)
  {
    return MapInputRowParser.parse(
        FOO_SCHEMA,
        ImmutableMap.of(
            "t", new DateTime(t, ISOChronology.getInstanceUTC()).getMillis(),
            "dim1", dim1,
            "dim2", dim2,
            "m1", m1
        )
    );
  }
}

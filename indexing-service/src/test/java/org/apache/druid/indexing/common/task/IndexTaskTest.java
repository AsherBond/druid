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

package org.apache.druid.indexing.common.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.impl.CSVParseSpec;
import org.apache.druid.data.input.impl.CsvInputFormat;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.FloatDimensionSchema;
import org.apache.druid.data.input.impl.JSONParseSpec;
import org.apache.druid.data.input.impl.JsonInputFormat;
import org.apache.druid.data.input.impl.LocalInputSource;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexer.granularity.ArbitraryGranularitySpec;
import org.apache.druid.indexer.granularity.GranularitySpec;
import org.apache.druid.indexer.granularity.UniformGranularitySpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.partitions.HashedPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.indexer.report.IngestionStatsAndErrors;
import org.apache.druid.indexer.report.TaskReport;
import org.apache.druid.indexing.common.LockGranularity;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.SegmentAllocateAction;
import org.apache.druid.indexing.common.config.TaskConfig;
import org.apache.druid.indexing.common.task.IndexTask.IndexIOConfig;
import org.apache.druid.indexing.common.task.IndexTask.IndexIngestionSpec;
import org.apache.druid.indexing.common.task.IndexTask.IndexTuningConfig;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.metrics.StubServiceEmitter;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.CursorHolder;
import org.apache.druid.segment.DataSegmentsWithSchemas;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.QueryableIndexCursorFactory;
import org.apache.druid.segment.SegmentSchemaMapping;
import org.apache.druid.segment.TestIndex;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.data.CompressionStrategy;
import org.apache.druid.segment.handoff.NoopSegmentHandoffNotifierFactory;
import org.apache.druid.segment.handoff.SegmentHandoffNotifier;
import org.apache.druid.segment.handoff.SegmentHandoffNotifierFactory;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.loading.LeastBytesUsedStorageLocationSelectorStrategy;
import org.apache.druid.segment.loading.SegmentCacheManager;
import org.apache.druid.segment.loading.SegmentLoaderConfig;
import org.apache.druid.segment.loading.SegmentLocalCacheManager;
import org.apache.druid.segment.loading.StorageLocation;
import org.apache.druid.segment.loading.StorageLocationConfig;
import org.apache.druid.segment.realtime.WindowedCursorFactory;
import org.apache.druid.segment.transform.ExpressionTransform;
import org.apache.druid.segment.transform.TransformSpec;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.HashPartitionFunction;
import org.apache.druid.timeline.partition.NumberedOverwriteShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartitionIds;
import org.apache.druid.timeline.partition.ShardSpec;
import org.easymock.EasyMock;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

@RunWith(Parameterized.class)
public class IndexTaskTest extends IngestionTestBase
{
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final String DATASOURCE = "test";
  private static final TimestampSpec DEFAULT_TIMESTAMP_SPEC = new TimestampSpec("ts", "auto", null);
  private static final DimensionsSpec DEFAULT_DIMENSIONS_SPEC = new DimensionsSpec(
      DimensionsSpec.getDefaultSchemas(Arrays.asList("ts", "dim"))
  );
  private static final ParseSpec DEFAULT_PARSE_SPEC = new CSVParseSpec(
      DEFAULT_TIMESTAMP_SPEC,
      DEFAULT_DIMENSIONS_SPEC,
      null,
      Arrays.asList("ts", "dim", "val"),
      false,
      0
  );
  private static final InputFormat DEFAULT_INPUT_FORMAT = new CsvInputFormat(
      Arrays.asList("ts", "dim", "val"),
      null,
      null,
      false,
      0,
      null
  );

  private static final DataSchema DATA_SCHEMA =
      DataSchema.builder()
                .withDataSource("test-json")
                .withTimestamp(DEFAULT_TIMESTAMP_SPEC)
                .withDimensions(
                    new StringDimensionSchema("ts"),
                    new StringDimensionSchema("dim"),
                    new LongDimensionSchema("valDim")
                )
                .withAggregators(new LongSumAggregatorFactory("valMet", "val"))
                .withGranularity(
                    new UniformGranularitySpec(
                        Granularities.DAY,
                        Granularities.MINUTE,
                        Collections.singletonList(Intervals.of("2014/P1D"))
                    )
                )
                .build();

  @Parameterized.Parameters(name = "{0}, useInputFormatApi={1}")
  public static Iterable<Object[]> constructorFeeder()
  {
    return ImmutableList.of(
        new Object[]{LockGranularity.TIME_CHUNK, false},
        new Object[]{LockGranularity.TIME_CHUNK, true},
        new Object[]{LockGranularity.SEGMENT, true}
    );
  }

  private static final IndexSpec INDEX_SPEC = IndexSpec.DEFAULT;
  private final ObjectMapper jsonMapper;
  private final IndexIO indexIO;
  private final LockGranularity lockGranularity;
  private final boolean useInputFormatApi;

  private SegmentCacheManager segmentCacheManager;
  private TestTaskRunner taskRunner;
  private File tmpDir;

  public IndexTaskTest(LockGranularity lockGranularity, boolean useInputFormatApi)
  {
    this.jsonMapper = getObjectMapper();
    this.indexIO = getIndexIO();
    this.lockGranularity = lockGranularity;
    this.useInputFormatApi = useInputFormatApi;
  }

  @Before
  public void setup() throws IOException
  {
    final File cacheDir = temporaryFolder.newFolder();
    tmpDir = temporaryFolder.newFolder();
    final SegmentLoaderConfig loaderConfig = new SegmentLoaderConfig()
    {
      @Override
      public List<StorageLocationConfig> getLocations()
      {
        return Collections.singletonList(
            new StorageLocationConfig(cacheDir, null, null)
        );
      }
    };
    final List<StorageLocation> storageLocations = loaderConfig.toStorageLocations();
    segmentCacheManager = new SegmentLocalCacheManager(
        storageLocations,
        loaderConfig,
        new LeastBytesUsedStorageLocationSelectorStrategy(storageLocations),
        TestIndex.INDEX_IO,
        jsonMapper
    );
    taskRunner = new TestTaskRunner();
  }

  @Test
  public void testCorrectInputSourceResources()
  {
    IndexTask indexTask = createIndexTask(
        new IndexIngestionSpec(
            DATA_SCHEMA,
            new IndexIOConfig(
                new LocalInputSource(tmpDir, "druid*"),
                DEFAULT_INPUT_FORMAT,
                false,
                false
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true)
        ),
        null
    );

    Assert.assertEquals(
        Collections.singleton(
            new ResourceAction(new Resource(
                LocalInputSource.TYPE_KEY,
                ResourceType.EXTERNAL
            ), Action.READ)),
        indexTask.getInputSourceResources()
    );
  }

  @Test
  public void testIngestNullOnlyColumns() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,,\n");
      writer.write("2014-01-01T01:00:20Z,,\n");
      writer.write("2014-01-01T02:00:30Z,,\n");
    }

    IndexTask indexTask = createIndexTask(
        new IndexIngestionSpec(
            DATA_SCHEMA,
            new IndexIOConfig(
                new LocalInputSource(tmpDir, "druid*"),
                DEFAULT_INPUT_FORMAT,
                false,
                false
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true)
        ),
        null
    );

    Assert.assertFalse(indexTask.supportsQueries());

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    Assert.assertEquals(ImmutableList.of("ts", "dim", "valDim"), segments.get(0).getDimensions());
    Assert.assertEquals(ImmutableList.of("valMet"), segments.get(0).getMetrics());

    verifySchemaAndAggFactory(
        segmentWithSchemas,
        RowSignature.builder()
                    .add("__time", ColumnType.LONG)
                    .add("ts", ColumnType.STRING)
                    .add("dim", ColumnType.STRING)
                    .add("valDim", ColumnType.LONG)
                    .add("valMet", ColumnType.LONG)
                    .build(),
        Collections.singletonMap("valMet", new LongSumAggregatorFactory("valMet", "valMet"))
    );
  }

  @Test
  public void testIngestNullOnlyColumns_storeEmptyColumnsOff_shouldNotStoreEmptyColumns() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,,\n");
      writer.write("2014-01-01T01:00:20Z,,\n");
      writer.write("2014-01-01T02:00:30Z,,\n");
    }

    IndexTask indexTask = createIndexTask(
        new IndexIngestionSpec(
            DATA_SCHEMA,
            new IndexIOConfig(
                new LocalInputSource(tmpDir, "druid*"),
                DEFAULT_INPUT_FORMAT,
                false,
                false
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true)
        ),
        ImmutableMap.of(Tasks.STORE_EMPTY_COLUMNS_KEY, false)
    );

    Assert.assertFalse(indexTask.supportsQueries());

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());
    Assert.assertEquals(1, segments.size());
    // only empty string dimensions are ignored currently
    Assert.assertEquals(ImmutableList.of("ts", "valDim"), segments.get(0).getDimensions());
    Assert.assertEquals(ImmutableList.of("valMet"), segments.get(0).getMetrics());

    verifySchemaAndAggFactory(
        segmentWithSchemas,
        RowSignature.builder()
                    .add("__time", ColumnType.LONG)
                    .add("ts", ColumnType.STRING)
                    .add("valDim", ColumnType.LONG)
                    .add("valMet", ColumnType.LONG)
                    .build(),
        Collections.singletonMap("valMet", new LongSumAggregatorFactory("valMet", "valMet"))
    );
  }

  @Test
  public void testDeterminePartitions() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            null,
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    Assert.assertFalse(indexTask.supportsQueries());

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());
    Assert.assertEquals(2, segments.size());

    Assert.assertEquals(DATASOURCE, segments.get(0).getDataSource());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(0).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(0).getShardSpec().getPartitionNum());
    Assert.assertEquals(2, segments.get(0).getShardSpec().getNumCorePartitions());
    Assert.assertEquals(
        HashPartitionFunction.MURMUR3_32_ABS,
        ((HashBasedNumberedShardSpec) segments.get(0).getShardSpec()).getPartitionFunction()
    );

    Assert.assertEquals(DATASOURCE, segments.get(1).getDataSource());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(1).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(1).getShardSpec().getClass());
    Assert.assertEquals(1, segments.get(1).getShardSpec().getPartitionNum());
    Assert.assertEquals(2, segments.get(1).getShardSpec().getNumCorePartitions());
    Assert.assertEquals(
        HashPartitionFunction.MURMUR3_32_ABS,
        ((HashBasedNumberedShardSpec) segments.get(1).getShardSpec()).getPartitionFunction()
    );

    Assert.assertEquals(2, segmentWithSchemas.getSegmentSchemaMapping().getSegmentIdToMetadataMap().size());
    Assert.assertEquals(1, segmentWithSchemas.getSegmentSchemaMapping().getSchemaFingerprintToPayloadMap().size());
    Assert.assertEquals(
        RowSignature.builder()
                    .add("__time", ColumnType.LONG)
                    .add("ts", ColumnType.STRING)
                    .add("dim", ColumnType.STRING)
                    .add("val", ColumnType.LONG)
                    .build(),
        segmentWithSchemas.getSegmentSchemaMapping()
                        .getSchemaFingerprintToPayloadMap()
                        .values()
                        .stream()
                        .findAny()
                        .get()
                        .getRowSignature()
    );
    Assert.assertEquals(
        Collections.singletonMap("val", new LongSumAggregatorFactory("val", "val")),
        segmentWithSchemas.getSegmentSchemaMapping()
                        .getSchemaFingerprintToPayloadMap()
                        .values()
                        .stream()
                        .findAny()
                        .get()
                        .getAggregatorFactories()
    );
  }

  @Test
  public void testTransformSpec() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,an|array,1|2|3,1\n");
      writer.write("2014-01-01T01:00:20Z,b,another|array,3|4,1\n");
      writer.write("2014-01-01T02:00:30Z,c,and|another,0|1,1\n");
    }

    final DimensionsSpec dimensionsSpec = new DimensionsSpec(
        DimensionsSpec.getDefaultSchemas(
            Arrays.asList(
                "ts",
                "dim",
                "dim_array",
                "dim_num_array",
                "dimt",
                "dimtarray1",
                "dimtarray2",
                "dimtnum_array"
            )
        )
    );
    final List<String> columns = Arrays.asList("ts", "dim", "dim_array", "dim_num_array", "val");
    final String listDelimiter = "|";
    final TransformSpec transformSpec = new TransformSpec(
        new SelectorDimFilter("dim", "b", null),
        ImmutableList.of(
            new ExpressionTransform("dimt", "concat(dim,dim)", ExprMacroTable.nil()),
            new ExpressionTransform("dimtarray1", "array(dim, dim)", ExprMacroTable.nil()),
            new ExpressionTransform(
                "dimtarray2",
                "map(d -> concat(d, 'foo'), dim_array)",
                ExprMacroTable.nil()
            ),
            new ExpressionTransform("dimtnum_array", "map(d -> d + 3, dim_num_array)", ExprMacroTable.nil())
        )
    );
    final IndexTuningConfig tuningConfig = createTuningConfigWithMaxRowsPerSegment(2, false);
    final IndexIngestionSpec indexIngestionSpec;
    if (useInputFormatApi) {
      indexIngestionSpec = createIngestionSpec(
          DEFAULT_TIMESTAMP_SPEC,
          dimensionsSpec,
          new CsvInputFormat(columns, listDelimiter, null, false, 0, null),
          transformSpec,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      indexIngestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(DEFAULT_TIMESTAMP_SPEC, dimensionsSpec, listDelimiter, columns, false, 0),
          transformSpec,
          null,
          tuningConfig,
          false,
          false
      );
    }

    IndexTask indexTask = createIndexTask(indexIngestionSpec, null);

    Assert.assertEquals(indexTask.getId(), indexTask.getGroupId());

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    DataSegment segment = segments.get(0);
    final File segmentFile = segmentCacheManager.getSegmentFiles(segment);

    final WindowedCursorFactory windowed = new WindowedCursorFactory(
        new QueryableIndexCursorFactory(indexIO.loadIndex(segmentFile)),
        segment.getInterval()
    );
    try (final CursorHolder cursorHolder = windowed.getCursorFactory().makeCursorHolder(CursorBuildSpec.FULL_SCAN)) {
      final Cursor cursor = cursorHolder.asCursor();
      final List<Map<String, Object>> transforms = new ArrayList<>();

      final DimensionSelector selector1 = cursor.getColumnSelectorFactory()
                                                .makeDimensionSelector(new DefaultDimensionSpec("dimt", "dimt"));
      final DimensionSelector selector2 = cursor.getColumnSelectorFactory()
                                                .makeDimensionSelector(new DefaultDimensionSpec(
                                                    "dimtarray1",
                                                    "dimtarray1"
                                                ));
      final DimensionSelector selector3 = cursor.getColumnSelectorFactory()
                                                .makeDimensionSelector(new DefaultDimensionSpec(
                                                    "dimtarray2",
                                                    "dimtarray2"
                                                ));
      final DimensionSelector selector4 = cursor.getColumnSelectorFactory()
                                                .makeDimensionSelector(new DefaultDimensionSpec(
                                                    "dimtnum_array",
                                                    "dimtnum_array"
                                                ));


      Map<String, Object> row = new HashMap<>();
      row.put("dimt", selector1.defaultGetObject());
      row.put("dimtarray1", selector2.defaultGetObject());
      row.put("dimtarray2", selector3.defaultGetObject());
      row.put("dimtnum_array", selector4.defaultGetObject());
      transforms.add(row);
      cursor.advance();

      Assert.assertEquals(1, transforms.size());
      Assert.assertEquals("bb", transforms.get(0).get("dimt"));
      Assert.assertEquals(ImmutableList.of("b", "b"), transforms.get(0).get("dimtarray1"));
      Assert.assertEquals(ImmutableList.of("anotherfoo", "arrayfoo"), transforms.get(0).get("dimtarray2"));
      Assert.assertEquals(ImmutableList.of("6.0", "7.0"), transforms.get(0).get("dimtnum_array"));

      Assert.assertEquals(DATASOURCE, segments.get(0).getDataSource());
      Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
      Assert.assertEquals(NumberedShardSpec.class, segments.get(0).getShardSpec().getClass());
      Assert.assertEquals(0, segments.get(0).getShardSpec().getPartitionNum());

      verifySchemaAndAggFactory(
          segmentWithSchemas,
          RowSignature.builder()
                      .add("__time", ColumnType.LONG)
                      .add("ts", ColumnType.STRING)
                      .add("dim", ColumnType.STRING)
                      .add("dim_array", ColumnType.STRING)
                      .add("dim_num_array", ColumnType.STRING)
                      .add("dimt", ColumnType.STRING)
                      .add("dimtarray1", ColumnType.STRING)
                      .add("dimtarray2", ColumnType.STRING)
                      .add("dimtnum_array", ColumnType.STRING)
                      .add("val", ColumnType.LONG)
                      .build(),
          Collections.singletonMap("val", new LongSumAggregatorFactory("val", "val"))
      );
    }
  }

  @Test
  public void testWithArbitraryGranularity() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new ArbitraryGranularitySpec(
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());

    invokeApi(req -> indexTask.getLiveReports(req, null));
    invokeApi(req -> indexTask.getLiveReports(req, "full"));
    invokeApi(req -> indexTask.getRowStats(req, null));
    invokeApi(req -> indexTask.getRowStats(req, "full"));
  }

  @Test
  public void testIntervalBucketing() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T07:59:59.977Z,a,1\n");
      writer.write("2014-01-01T08:00:00.000Z,b,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.HOUR,
                Collections.singletonList(Intervals.of("2014-01-01T08:00:00Z/2014-01-01T09:00:00Z"))
            ),
            createTuningConfigWithMaxRowsPerSegment(50, true),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
  }

  @Test
  public void testNumShardsProvided() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            null,
            createTuningConfigWithPartitionsSpec(new HashedPartitionsSpec(null, 1, null)),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());

    Assert.assertEquals(DATASOURCE, segments.get(0).getDataSource());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(0).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(0).getShardSpec().getPartitionNum());
    Assert.assertEquals(
        HashPartitionFunction.MURMUR3_32_ABS,
        ((HashBasedNumberedShardSpec) segments.get(0).getShardSpec()).getPartitionFunction()
    );
  }

  @Test
  public void testNumShardsAndHashPartitionFunctionProvided() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            null,
            createTuningConfigWithPartitionsSpec(
                new HashedPartitionsSpec(null, 1, null, HashPartitionFunction.MURMUR3_32_ABS)
            ),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());

    Assert.assertEquals(DATASOURCE, segments.get(0).getDataSource());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(0).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(0).getShardSpec().getPartitionNum());
    Assert.assertEquals(
        HashPartitionFunction.MURMUR3_32_ABS,
        ((HashBasedNumberedShardSpec) segments.get(0).getShardSpec()).getPartitionFunction()
    );
  }

  @Test
  public void testNumShardsAndPartitionDimensionsProvided() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    final IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            null,
            createTuningConfigWithPartitionsSpec(new HashedPartitionsSpec(null, 2, ImmutableList.of("dim"))),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(2, segments.size());

    for (DataSegment segment : segments) {
      Assert.assertEquals(DATASOURCE, segment.getDataSource());
      Assert.assertEquals(Intervals.of("2014/P1D"), segment.getInterval());
      Assert.assertEquals(HashBasedNumberedShardSpec.class, segment.getShardSpec().getClass());
      final HashBasedNumberedShardSpec hashBasedNumberedShardSpec = (HashBasedNumberedShardSpec) segment.getShardSpec();
      Assert.assertEquals(HashPartitionFunction.MURMUR3_32_ABS, hashBasedNumberedShardSpec.getPartitionFunction());

      final File segmentFile = segmentCacheManager.getSegmentFiles(segment);

      final WindowedCursorFactory windowed = new WindowedCursorFactory(
          new QueryableIndexCursorFactory(indexIO.loadIndex(segmentFile)),
          segment.getInterval()
      );

      try (final CursorHolder cursorHolder = windowed.getCursorFactory().makeCursorHolder(CursorBuildSpec.FULL_SCAN)) {
        final Cursor cursor = cursorHolder.asCursor();
        final List<Integer> hashes = new ArrayList<>();
        final DimensionSelector selector = cursor.getColumnSelectorFactory()
                                                 .makeDimensionSelector(new DefaultDimensionSpec("dim", "dim"));
        while (!cursor.isDone()) {
          final int hash = HashPartitionFunction.MURMUR3_32_ABS.hash(
              HashBasedNumberedShardSpec.serializeGroupKey(
                  jsonMapper,
                  // list of list because partitioning extractKeys uses InputRow.getDimension which always returns a List<String>
                  Collections.singletonList(Collections.singletonList(selector.getObject()))
              ),
              hashBasedNumberedShardSpec.getNumBuckets()
          );
          hashes.add(hash);
          cursor.advance();
        }

        Assert.assertTrue(hashes.stream().allMatch(h -> h.intValue() == hashes.get(0)));
      }
    }
  }

  @Test
  public void testWriteNewSegmentsWithAppendToExistingWithLinearPartitioningSuccessfullyAppend() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            null,
            createTuningConfigWithMaxRowsPerSegment(2, false),
            true,
            false
        ),
        null
    );

    Assert.assertEquals("index_append_test", indexTask.getGroupId());

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(2, taskRunner.getTaskActionClient().getActionCount(SegmentAllocateAction.class));
    Assert.assertEquals(2, segments.size());

    Assert.assertEquals(DATASOURCE, segments.get(0).getDataSource());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
    Assert.assertEquals(NumberedShardSpec.class, segments.get(0).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(0).getShardSpec().getPartitionNum());

    Assert.assertEquals(DATASOURCE, segments.get(1).getDataSource());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(1).getInterval());
    Assert.assertEquals(NumberedShardSpec.class, segments.get(1).getShardSpec().getClass());
    Assert.assertEquals(1, segments.get(1).getShardSpec().getPartitionNum());
  }

  @Test
  public void testIntervalNotSpecified() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(3, segments.size());

    Assert.assertEquals(DATASOURCE, segments.get(0).getDataSource());
    Assert.assertEquals(Intervals.of("2014-01-01T00/PT1H"), segments.get(0).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(0).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(0).getShardSpec().getPartitionNum());

    Assert.assertEquals(DATASOURCE, segments.get(1).getDataSource());
    Assert.assertEquals(Intervals.of("2014-01-01T01/PT1H"), segments.get(1).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(1).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(1).getShardSpec().getPartitionNum());

    Assert.assertEquals(DATASOURCE, segments.get(2).getDataSource());
    Assert.assertEquals(Intervals.of("2014-01-01T02/PT1H"), segments.get(2).getInterval());
    Assert.assertEquals(HashBasedNumberedShardSpec.class, segments.get(2).getShardSpec().getClass());
    Assert.assertEquals(0, segments.get(2).getShardSpec().getPartitionNum());
  }

  @Test
  public void testIntervalNotSpecifiedWithReplace() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    // Expect exception if reingest with dropExisting and null intervals is attempted
    Exception exception = Assert.assertThrows(
        IAE.class,
        () -> createIndexTask(
            createDefaultIngestionSpec(
                new UniformGranularitySpec(
                    Granularities.HOUR,
                    Granularities.MINUTE,
                    null
                ),
                createTuningConfigWithMaxRowsPerSegment(2, true),
                false,
                true
            ),
            null
        )
    );
    Assert.assertEquals(
        "GranularitySpec's intervals cannot be empty for replace.",
        exception.getMessage()
    );
  }

  @Test
  public void testCSVFileWithHeader() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("time,d,val\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final IndexTuningConfig tuningConfig = createTuningConfigWithMaxRowsPerSegment(2, true);
    final IndexIngestionSpec ingestionSpec;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(timestampSpec, DimensionsSpec.EMPTY, null, null, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          timestampSpec,
          DimensionsSpec.EMPTY,
          new CsvInputFormat(null, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    IndexTask indexTask = createIndexTask(
        ingestionSpec,
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());

    Assert.assertEquals(Collections.singletonList("d"), segments.get(0).getDimensions());
    Assert.assertEquals(Collections.singletonList("val"), segments.get(0).getMetrics());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
  }

  @Test
  public void testCSVFileWithHeaderColumnOverride() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("time,d,val\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final List<String> columns = Arrays.asList("time", "dim", "val");
    final IndexTuningConfig tuningConfig = createTuningConfigWithMaxRowsPerSegment(2, true);
    final IndexIngestionSpec ingestionSpec;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          timestampSpec,
          DimensionsSpec.EMPTY,
          new CsvInputFormat(columns, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(timestampSpec, DimensionsSpec.EMPTY, null, columns, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    IndexTask indexTask = createIndexTask(
        ingestionSpec,
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());

    Assert.assertEquals(Collections.singletonList("d"), segments.get(0).getDimensions());
    Assert.assertEquals(Collections.singletonList("val"), segments.get(0).getMetrics());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
  }

  @Test
  public void testWithSmallMaxTotalRows() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T00:00:10Z,b,2\n");
      writer.write("2014-01-01T00:00:10Z,c,3\n");
      writer.write("2014-01-01T01:00:20Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,2\n");
      writer.write("2014-01-01T01:00:20Z,c,3\n");
      writer.write("2014-01-01T02:00:30Z,a,1\n");
      writer.write("2014-01-01T02:00:30Z,b,2\n");
      writer.write("2014-01-01T02:00:30Z,c,3\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfig(2, 2, 2L, null, false, true),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(6, segments.size());

    for (int i = 0; i < 6; i++) {
      final DataSegment segment = segments.get(i);
      final Interval expectedInterval = Intervals.of(StringUtils.format("2014-01-01T0%d/PT1H", (i / 2)));
      final int expectedPartitionNum = i % 2;

      Assert.assertEquals(DATASOURCE, segment.getDataSource());
      Assert.assertEquals(expectedInterval, segment.getInterval());
      Assert.assertEquals(NumberedShardSpec.class, segment.getShardSpec().getClass());
      Assert.assertEquals(expectedPartitionNum, segment.getShardSpec().getPartitionNum());
    }
  }

  @Test
  public void testPerfectRollup() throws Exception
  {
    populateRollupTestData(createTempFile());

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.DAY,
                Granularities.DAY,
                true,
                null
            ),
            createTuningConfig(3, 2, 2L, null, true, true),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(3, segments.size());

    for (int i = 0; i < 3; i++) {
      final DataSegment segment = segments.get(i);
      final Interval expectedInterval = Intervals.of("2014-01-01T00:00:00.000Z/2014-01-02T00:00:00.000Z");

      Assert.assertEquals(DATASOURCE, segment.getDataSource());
      Assert.assertEquals(expectedInterval, segment.getInterval());
      Assert.assertEquals(segment.getShardSpec().getClass(), HashBasedNumberedShardSpec.class);
      Assert.assertEquals(i, segment.getShardSpec().getPartitionNum());
    }
  }

  @Test
  public void testBestEffortRollup() throws Exception
  {
    populateRollupTestData(createTempFile());

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.DAY,
                Granularities.DAY,
                true,
                null
            ),
            createTuningConfig(3, 2, 2L, null, false, true),
            false,
            false
        ),
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(5, segments.size());

    final Interval expectedInterval = Intervals.of("2014-01-01T00:00:00.000Z/2014-01-02T00:00:00.000Z");
    for (int i = 0; i < 5; i++) {
      final DataSegment segment = segments.get(i);

      Assert.assertEquals(DATASOURCE, segment.getDataSource());
      Assert.assertEquals(expectedInterval, segment.getInterval());
      Assert.assertEquals(NumberedShardSpec.class, segment.getShardSpec().getClass());
      Assert.assertEquals(i, segment.getShardSpec().getPartitionNum());
    }
  }

  @Test
  public void testWaitForSegmentAvailabilityNoSegments()
  {
    TaskToolbox mockToolbox = EasyMock.createMock(TaskToolbox.class);
    List<DataSegment> segmentsToWaitFor = new ArrayList<>();
    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    EasyMock.replay(mockToolbox);
    Assert.assertTrue(indexTask.waitForSegmentAvailability(mockToolbox, segmentsToWaitFor, 1000));
    EasyMock.verify(mockToolbox);
  }

  @Test
  public void testWaitForSegmentAvailabilityInvalidWaitTimeout()
  {
    TaskToolbox mockToolbox = EasyMock.createMock(TaskToolbox.class);
    List<DataSegment> segmentsToWaitFor = new ArrayList<>();
    segmentsToWaitFor.add(EasyMock.createMock(DataSegment.class));
    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    EasyMock.replay(mockToolbox);
    Assert.assertFalse(indexTask.waitForSegmentAvailability(mockToolbox, segmentsToWaitFor, -1));
    EasyMock.verify(mockToolbox);
  }

  @Test
  public void testWaitForSegmentAvailabilityMultipleSegmentsTimeout()
  {
    TaskToolbox mockToolbox = EasyMock.createMock(TaskToolbox.class);
    SegmentHandoffNotifierFactory mockFactory = EasyMock.createMock(SegmentHandoffNotifierFactory.class);
    SegmentHandoffNotifier mockNotifier = EasyMock.createMock(SegmentHandoffNotifier.class);

    DataSegment mockDataSegment1 = EasyMock.createMock(DataSegment.class);
    DataSegment mockDataSegment2 = EasyMock.createMock(DataSegment.class);
    List<DataSegment> segmentsToWaitFor = new ArrayList<>();
    segmentsToWaitFor.add(mockDataSegment1);
    segmentsToWaitFor.add(mockDataSegment2);

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    EasyMock.expect(mockDataSegment1.getInterval()).andReturn(Intervals.of("1970-01-01/2100-01-01")).once();
    EasyMock.expect(mockDataSegment1.getVersion()).andReturn("dummyString").once();
    EasyMock.expect(mockDataSegment1.getShardSpec()).andReturn(EasyMock.createMock(ShardSpec.class)).once();
    EasyMock.expect(mockDataSegment2.getInterval()).andReturn(Intervals.of("1970-01-01/2100-01-01")).once();
    EasyMock.expect(mockDataSegment2.getVersion()).andReturn("dummyString").once();
    EasyMock.expect(mockDataSegment2.getShardSpec()).andReturn(EasyMock.createMock(ShardSpec.class)).once();

    EasyMock.expect(mockToolbox.getSegmentHandoffNotifierFactory()).andReturn(mockFactory).once();
    EasyMock.expect(mockToolbox.getEmitter()).andReturn(new NoopServiceEmitter()).anyTimes();
    EasyMock.expect(mockDataSegment1.getDataSource()).andReturn("MockDataSource").once();
    EasyMock.expect(mockFactory.createSegmentHandoffNotifier("MockDataSource", indexTask.getId())).andReturn(mockNotifier).once();
    mockNotifier.start();
    EasyMock.expectLastCall().once();
    mockNotifier.registerSegmentHandoffCallback(EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject());
    EasyMock.expectLastCall().andReturn(true).times(2);
    mockNotifier.close();
    EasyMock.expectLastCall().once();


    EasyMock.replay(mockToolbox);
    EasyMock.replay(mockDataSegment1, mockDataSegment2);
    EasyMock.replay(mockFactory, mockNotifier);

    Assert.assertFalse(indexTask.waitForSegmentAvailability(mockToolbox, segmentsToWaitFor, 1000));
    EasyMock.verify(mockToolbox);
    EasyMock.verify(mockDataSegment1, mockDataSegment2);
    EasyMock.verify(mockFactory, mockNotifier);
  }

  @Test
  public void testWaitForSegmentAvailabilityMultipleSegmentsSuccess()
  {
    TaskToolbox mockToolbox = EasyMock.createMock(TaskToolbox.class);

    DataSegment mockDataSegment1 = EasyMock.createMock(DataSegment.class);
    DataSegment mockDataSegment2 = EasyMock.createMock(DataSegment.class);
    List<DataSegment> segmentsToWaitFor = new ArrayList<>();
    segmentsToWaitFor.add(mockDataSegment1);
    segmentsToWaitFor.add(mockDataSegment2);

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    EasyMock.expect(mockDataSegment1.getInterval()).andReturn(Intervals.of("1970-01-01/1971-01-01")).once();
    EasyMock.expect(mockDataSegment1.getVersion()).andReturn("dummyString").once();
    EasyMock.expect(mockDataSegment1.getShardSpec()).andReturn(EasyMock.createMock(ShardSpec.class)).once();
    EasyMock.expect(mockDataSegment1.getId()).andReturn(SegmentId.dummy("MockDataSource")).once();
    EasyMock.expect(mockDataSegment2.getInterval()).andReturn(Intervals.of("1971-01-01/1972-01-01")).once();
    EasyMock.expect(mockDataSegment2.getVersion()).andReturn("dummyString").once();
    EasyMock.expect(mockDataSegment2.getShardSpec()).andReturn(EasyMock.createMock(ShardSpec.class)).once();
    EasyMock.expect(mockDataSegment2.getId()).andReturn(SegmentId.dummy("MockDataSource")).once();

    EasyMock.expect(mockToolbox.getSegmentHandoffNotifierFactory())
            .andReturn(new NoopSegmentHandoffNotifierFactory())
            .once();
    EasyMock.expect(mockToolbox.getEmitter()).andReturn(new NoopServiceEmitter()).anyTimes();

    EasyMock.expect(mockDataSegment1.getDataSource()).andReturn("MockDataSource").once();

    EasyMock.replay(mockToolbox);
    EasyMock.replay(mockDataSegment1, mockDataSegment2);

    Assert.assertTrue(indexTask.waitForSegmentAvailability(mockToolbox, segmentsToWaitFor, 30000));
    EasyMock.verify(mockToolbox);
    EasyMock.verify(mockDataSegment1, mockDataSegment2);
  }

  @Test
  public void testWaitForSegmentAvailabilityEmitsExpectedMetric()
  {
    TaskToolbox mockToolbox = EasyMock.createMock(TaskToolbox.class);

    DataSegment mockDataSegment1 = EasyMock.createMock(DataSegment.class);
    DataSegment mockDataSegment2 = EasyMock.createMock(DataSegment.class);
    List<DataSegment> segmentsToWaitFor = new ArrayList<>();
    segmentsToWaitFor.add(mockDataSegment1);
    segmentsToWaitFor.add(mockDataSegment2);

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            createTuningConfigWithMaxRowsPerSegment(2, true),
            false,
            false
        ),
        null
    );

    EasyMock.expect(mockDataSegment1.getInterval()).andReturn(Intervals.of("1970-01-01/1971-01-01")).once();
    EasyMock.expect(mockDataSegment1.getVersion()).andReturn("dummyString").once();
    EasyMock.expect(mockDataSegment1.getShardSpec()).andReturn(EasyMock.createMock(ShardSpec.class)).once();
    EasyMock.expect(mockDataSegment1.getId()).andReturn(SegmentId.dummy("MockDataSource")).once();
    EasyMock.expect(mockDataSegment2.getInterval()).andReturn(Intervals.of("1971-01-01/1972-01-01")).once();
    EasyMock.expect(mockDataSegment2.getVersion()).andReturn("dummyString").once();
    EasyMock.expect(mockDataSegment2.getShardSpec()).andReturn(EasyMock.createMock(ShardSpec.class)).once();
    EasyMock.expect(mockDataSegment2.getId()).andReturn(SegmentId.dummy("MockDataSource")).once();

    EasyMock.expect(mockToolbox.getSegmentHandoffNotifierFactory())
            .andReturn(new NoopSegmentHandoffNotifierFactory())
            .once();
    final StubServiceEmitter emitter = new StubServiceEmitter("IndexTaskTest", "localhost");
    EasyMock.expect(mockToolbox.getEmitter())
            .andReturn(emitter).anyTimes();

    EasyMock.expect(mockDataSegment1.getDataSource()).andReturn("MockDataSource").once();

    EasyMock.replay(mockToolbox);
    EasyMock.replay(mockDataSegment1, mockDataSegment2);

    Assert.assertTrue(indexTask.waitForSegmentAvailability(mockToolbox, segmentsToWaitFor, 30000));
    emitter.verifyEmitted("task/segmentAvailability/wait/time", 1);
    EasyMock.verify(mockToolbox);
    EasyMock.verify(mockDataSegment1, mockDataSegment2);
  }

  private static void populateRollupTestData(File tmpFile) throws IOException
  {
    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,a,1\n");
      writer.write("2014-01-01T00:00:10Z,b,2\n");
      writer.write("2014-01-01T00:00:10Z,c,3\n");
      writer.write("2014-01-01T01:00:20Z,b,2\n");
      writer.write("2014-01-01T02:00:30Z,a,1\n");
      writer.write("2014-01-01T02:00:30Z,b,2\n");
      writer.write("2014-01-01T01:00:20Z,c,3\n");
      writer.write("2014-01-01T02:00:30Z,c,3\n");
    }
  }

  private File createTempFile() throws IOException
  {
    return File.createTempFile("druid", "index", tmpDir);
  }

  @Test
  public void testIgnoreParseException() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("time,d,val\n");
      writer.write("unparseable,a,1\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final List<String> columns = Arrays.asList("time", "dim", "val");
    // ignore parse exception
    final IndexTuningConfig tuningConfig = createTuningConfig(2, null, null, null, false, false);

    // GranularitySpec.intervals and numShards must be null to verify reportParseException=false is respected both in
    // IndexTask.determineShardSpecs() and IndexTask.generateAndPublishSegments()
    final IndexIngestionSpec parseExceptionIgnoreSpec;
    if (useInputFormatApi) {
      parseExceptionIgnoreSpec = createIngestionSpec(
          timestampSpec,
          DimensionsSpec.EMPTY,
          new CsvInputFormat(columns, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      parseExceptionIgnoreSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(timestampSpec, DimensionsSpec.EMPTY, null, columns, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    IndexTask indexTask = createIndexTask(parseExceptionIgnoreSpec, null);

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(Collections.singletonList("d"), segments.get(0).getDimensions());
    Assert.assertEquals(Collections.singletonList("val"), segments.get(0).getMetrics());
    Assert.assertEquals(Intervals.of("2014/P1D"), segments.get(0).getInterval());
  }

  @Test
  public void testReportParseException() throws Exception
  {
    final File tmpFile = createTempFile();
    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("time,d,val\n");
      writer.write("unparseable,a,1\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final List<String> columns = Arrays.asList("time", "dim", "val");
    // report parse exception
    final IndexTuningConfig tuningConfig = createTuningConfig(2, null, null, null, false, true);
    final IndexIngestionSpec indexIngestionSpec;
    List<String> expectedMessages;
    if (useInputFormatApi) {
      indexIngestionSpec = createIngestionSpec(
          timestampSpec,
          DimensionsSpec.EMPTY,
          new CsvInputFormat(columns, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      indexIngestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(timestampSpec, DimensionsSpec.EMPTY, null, columns, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    expectedMessages = ImmutableList.of(
        StringUtils.format(
            "Timestamp[unparseable] is unparseable! Event: {time=unparseable, d=a, val=1} (Path: %s, Record: 1, Line: 2)",
            tmpFile.toURI()
        )
    );
    IndexTask indexTask = createIndexTask(indexIngestionSpec, null);

    TaskStatus status = runTask(indexTask).lhs;
    Assert.assertEquals(TaskState.FAILED, status.getStatusCode());
    checkTaskStatusErrorMsgForParseExceptionsExceeded(status);

    IngestionStatsAndErrors reportData = getTaskReportData();

    ParseExceptionReport parseExceptionReport =
        ParseExceptionReport.forPhase(reportData, RowIngestionMeters.BUILD_SEGMENTS);
    Assert.assertEquals(expectedMessages, parseExceptionReport.getErrorMessages());

    List<String> expectedInputs = ImmutableList.of("{time=unparseable, d=a, val=1}");
    Assert.assertEquals(expectedInputs, parseExceptionReport.getInputs());
  }

  @Test
  public void testMultipleParseExceptionsSuccess() throws Exception
  {
    final File tmpFile = createTempFile();
    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("{\"time\":\"unparseable\",\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}\n"); // unparseable time
      writer.write("{\"time\":\"2014-01-01T00:00:10Z\",\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}\n"); // valid row
      writer.write(
          "{\"time\":\"2014-01-01T00:00:10Z\",\"dim\":\"b\",\"dimLong\":\"notnumber\",\"dimFloat\":3.0,\"val\":1}\n"); // row with invalid long dimension
      writer.write(
          "{\"time\":\"2014-01-01T00:00:10Z\",\"dim\":\"b\",\"dimLong\":2,\"dimFloat\":\"notnumber\",\"val\":1}\n"); // row with invalid float dimension
      writer.write(
          "{\"time\":\"2014-01-01T00:00:10Z\",\"dim\":\"b\",\"dimLong\":2,\"dimFloat\":4.0,\"val\":\"notnumber\"}\n"); // row with invalid metric
      writer.write("{\"time\":9.0x,\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}\n"); // invalid JSON
      writer.write(
          "{\"time\":\"3014-03-01T00:00:10Z\",\"dim\":\"outsideofinterval\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}\n"); // thrown away
      writer.write("{\"time\":\"99999999999-01-01T00:00:10Z\",\"dim\":\"b\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}\n"); // unparseable time
      writer.write("this is not JSON\n"); // invalid JSON
    }

    final IndexTuningConfig tuningConfig = TuningConfigBuilder
        .forIndexTask()
        .withPartitionsSpec(new HashedPartitionsSpec(2, null, null))
        .withIndexSpec(INDEX_SPEC)
        .withForceGuaranteedRollup(true)
        .withReportParseExceptions(false)
        .withLogParseExceptions(true)
        .withMaxParseExceptions(7)
        .withMaxSavedParseExceptions(7)
        .build();

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(
        Arrays.asList(
            new StringDimensionSchema("dim"),
            new LongDimensionSchema("dimLong"),
            new FloatDimensionSchema("dimFloat")
        )
    );
    final IndexIngestionSpec ingestionSpec;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          timestampSpec,
          dimensionsSpec,
          new JsonInputFormat(null, null, null, null, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new JSONParseSpec(timestampSpec, dimensionsSpec, null, null, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    IndexTask indexTask = createIndexTask(ingestionSpec, null);

    TaskStatus status = runTask(indexTask).lhs;
    Assert.assertEquals(TaskState.SUCCESS, status.getStatusCode());
    Assert.assertNull(status.getErrorMsg());

    IngestionStatsAndErrors reportData = getTaskReportData();

    Map<String, Object> expectedMetrics = ImmutableMap.of(
        RowIngestionMeters.DETERMINE_PARTITIONS,
        ImmutableMap.of(
            RowIngestionMeters.PROCESSED_WITH_ERROR, 0,
            RowIngestionMeters.PROCESSED, 4,
            RowIngestionMeters.PROCESSED_BYTES, 657,
            RowIngestionMeters.UNPARSEABLE, 4,
            RowIngestionMeters.THROWN_AWAY, 1
        ),
        RowIngestionMeters.BUILD_SEGMENTS,
        ImmutableMap.of(
            RowIngestionMeters.PROCESSED_WITH_ERROR, 3,
            RowIngestionMeters.PROCESSED, 1,
            RowIngestionMeters.PROCESSED_BYTES, 657,
            RowIngestionMeters.UNPARSEABLE, 4,
            RowIngestionMeters.THROWN_AWAY, 1
        )
    );
    Assert.assertEquals(expectedMetrics, reportData.getRowStats());

    ParseExceptionReport parseExceptionReport =
        ParseExceptionReport.forPhase(reportData, RowIngestionMeters.BUILD_SEGMENTS);

    List<String> expectedMessages;
    expectedMessages = Arrays.asList(
        StringUtils.format("Unable to parse row [this is not JSON] (Path: %s, Record: 6, Line: 9)", tmpFile.toURI()),
        StringUtils.format(
            "Timestamp[99999999999-01-01T00:00:10Z] is unparseable! Event: {time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 6, Line: 8)",
            tmpFile.toURI()
        ),
        StringUtils.format(
            "Unable to parse row [{\"time\":9.0x,\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}] (Path: %s, Record: 5, Line: 6)",
            tmpFile.toURI()
        ),
        "Unable to parse value[notnumber] for field[val]",
        "Could not convert value [notnumber] to float for dimension [dimFloat].",
        "Could not convert value [notnumber] to long for dimension [dimLong].",
        StringUtils.format(
            "Timestamp[unparseable] is unparseable! Event: {time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 1, Line: 1)",
            tmpFile.toURI()
        )
    );

    Assert.assertEquals(expectedMessages, parseExceptionReport.getErrorMessages());

    List<String> expectedInputs = Arrays.asList(
        "this is not JSON",
        "{time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1}",
        "{\"time\":9.0x,\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}",
        "{time=2014-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=4.0, val=notnumber}",
        "{time=2014-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=notnumber, val=1}",
        "{time=2014-01-01T00:00:10Z, dim=b, dimLong=notnumber, dimFloat=3.0, val=1}",
        "{time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1}"
    );
    Assert.assertEquals(expectedInputs, parseExceptionReport.getInputs());

    parseExceptionReport =
        ParseExceptionReport.forPhase(reportData, RowIngestionMeters.DETERMINE_PARTITIONS);

    expectedMessages = Arrays.asList(
        StringUtils.format("Unable to parse row [this is not JSON] (Path: %s, Record: 6, Line: 9)", tmpFile.toURI()),
        StringUtils.format(
            "Timestamp[99999999999-01-01T00:00:10Z] is unparseable! Event: {time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 6, Line: 8)",
            tmpFile.toURI()
        ),
        StringUtils.format(
            "Unable to parse row [{\"time\":9.0x,\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}] (Path: %s, Record: 5, Line: 6)",
            tmpFile.toURI()
        ),
        StringUtils.format(
            "Timestamp[unparseable] is unparseable! Event: {time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 1, Line: 1)",
            tmpFile.toURI()
        )
    );

    Assert.assertEquals(expectedMessages, parseExceptionReport.getErrorMessages());

    expectedInputs = Arrays.asList(
        "this is not JSON",
        "{time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1}",
        "{\"time\":9.0x,\"dim\":\"a\",\"dimLong\":2,\"dimFloat\":3.0,\"val\":1}",
        "{time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1}"
    );
    Assert.assertEquals(expectedInputs, parseExceptionReport.getInputs());
  }

  @Test
  public void testMultipleParseExceptionsFailure() throws Exception
  {
    final File tmpFile = createTempFile();
    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("time,dim,dimLong,dimFloat,val\n");
      writer.write("unparseable,a,2,3.0,1\n"); // unparseable
      writer.write("2014-01-01T00:00:10Z,a,2,3.0,1\n"); // valid row
      writer.write("9.0,a,2,3.0,1\n"); // unparseable
      writer.write("3014-03-01T00:00:10Z,outsideofinterval,2,3.0,1\n"); // thrown away
      writer.write("99999999999-01-01T00:00:10Z,b,2,3.0,1\n"); // unparseable
    }

    // Allow up to 3 parse exceptions, and save up to 2 parse exceptions
    final IndexTuningConfig tuningConfig = TuningConfigBuilder
        .forIndexTask()
        .withPartitionsSpec(new DynamicPartitionsSpec(2, null))
        .withIndexSpec(INDEX_SPEC)
        .withForceGuaranteedRollup(false)
        .withReportParseExceptions(false)
        .withLogParseExceptions(true)
        .withMaxParseExceptions(2)
        .withMaxSavedParseExceptions(5)
        .build();

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(
        Arrays.asList(
            new StringDimensionSchema("dim"),
            new LongDimensionSchema("dimLong"),
            new FloatDimensionSchema("dimFloat")
        )
    );
    final List<String> columns = Arrays.asList("time", "dim", "dimLong", "dimFloat", "val");
    final IndexIngestionSpec ingestionSpec;

    List<String> expectedMessages;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          timestampSpec,
          dimensionsSpec,
          new CsvInputFormat(columns, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(timestampSpec, dimensionsSpec, null, columns, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    expectedMessages = Arrays.asList(
        StringUtils.format(
            "Timestamp[99999999999-01-01T00:00:10Z] is unparseable! Event: {time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 3, Line: 6)",
            tmpFile.toURI()
        ),
        StringUtils.format(
            "Timestamp[9.0] is unparseable! Event: {time=9.0, dim=a, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 2, Line: 4)",
            tmpFile.toURI()
        ),
        StringUtils.format(
            "Timestamp[unparseable] is unparseable! Event: {time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 1, Line: 2)",
            tmpFile.toURI()
        )
    );
    IndexTask indexTask = createIndexTask(
        ingestionSpec,
        null
    );

    TaskStatus status = runTask(indexTask).lhs;
    Assert.assertEquals(TaskState.FAILED, status.getStatusCode());
    checkTaskStatusErrorMsgForParseExceptionsExceeded(status);

    IngestionStatsAndErrors reportData = getTaskReportData();

    Map<String, Object> expectedMetrics = ImmutableMap.of(
        RowIngestionMeters.DETERMINE_PARTITIONS,
        ImmutableMap.of(
            RowIngestionMeters.PROCESSED_WITH_ERROR, 0,
            RowIngestionMeters.PROCESSED, 0,
            RowIngestionMeters.PROCESSED_BYTES, 0,
            RowIngestionMeters.UNPARSEABLE, 0,
            RowIngestionMeters.THROWN_AWAY, 0
        ),
        RowIngestionMeters.BUILD_SEGMENTS,
        ImmutableMap.of(
            RowIngestionMeters.PROCESSED_WITH_ERROR, 0,
            RowIngestionMeters.PROCESSED, 1,
            RowIngestionMeters.PROCESSED_BYTES, 182,
            RowIngestionMeters.UNPARSEABLE, 3,
            RowIngestionMeters.THROWN_AWAY, 1
        )
    );

    Assert.assertEquals(expectedMetrics, reportData.getRowStats());

    ParseExceptionReport parseExceptionReport =
        ParseExceptionReport.forPhase(reportData, RowIngestionMeters.BUILD_SEGMENTS);
    Assert.assertEquals(expectedMessages, parseExceptionReport.getErrorMessages());

    List<String> expectedInputs = Arrays.asList(
        "{time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1}",
        "{time=9.0, dim=a, dimLong=2, dimFloat=3.0, val=1}",
        "{time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1}"
    );
    Assert.assertEquals(expectedInputs, parseExceptionReport.getInputs());
  }

  @Test
  public void testMultipleParseExceptionsFailureAtDeterminePartitions() throws Exception
  {
    final File tmpFile = createTempFile();
    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("time,dim,dimLong,dimFloat,val\n");
      writer.write("unparseable,a,2,3.0,1\n"); // unparseable
      writer.write("2014-01-01T00:00:10Z,a,2,3.0,1\n"); // valid row
      writer.write("9.0,a,2,3.0,1\n"); // unparseable
      writer.write("3014-03-01T00:00:10Z,outsideofinterval,2,3.0,1\n"); // thrown away
      writer.write("99999999999-01-01T00:00:10Z,b,2,3.0,1\n"); // unparseable
    }

    // Allow up to 3 parse exceptions, and save up to 2 parse exceptions
    final IndexTuningConfig tuningConfig = TuningConfigBuilder
        .forIndexTask()
        .withPartitionsSpec(new HashedPartitionsSpec(2, null, null))
        .withIndexSpec(INDEX_SPEC)
        .withForceGuaranteedRollup(true)
        .withReportParseExceptions(false)
        .withLogParseExceptions(true)
        .withMaxParseExceptions(2)
        .withMaxSavedParseExceptions(5)
        .build();

    final TimestampSpec timestampSpec = new TimestampSpec("time", "auto", null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(
        Arrays.asList(
            new StringDimensionSchema("dim"),
            new LongDimensionSchema("dimLong"),
            new FloatDimensionSchema("dimFloat")
        )
    );
    final List<String> columns = Arrays.asList("time", "dim", "dimLong", "dimFloat", "val");
    final IndexIngestionSpec ingestionSpec;

    List<String> expectedMessages;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          timestampSpec,
          dimensionsSpec,
          new CsvInputFormat(columns, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(timestampSpec, dimensionsSpec, null, columns, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    expectedMessages = Arrays.asList(
        StringUtils.format("Timestamp[99999999999-01-01T00:00:10Z] is unparseable! Event: {time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 3, Line: 6)", tmpFile.toURI()),
        StringUtils.format("Timestamp[9.0] is unparseable! Event: {time=9.0, dim=a, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 2, Line: 4)", tmpFile.toURI()),
        StringUtils.format("Timestamp[unparseable] is unparseable! Event: {time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1} (Path: %s, Record: 1, Line: 2)", tmpFile.toURI())
    );
    IndexTask indexTask = createIndexTask(
        ingestionSpec,
        null
    );

    TaskStatus status = runTask(indexTask).lhs;
    Assert.assertEquals(TaskState.FAILED, status.getStatusCode());
    checkTaskStatusErrorMsgForParseExceptionsExceeded(status);

    IngestionStatsAndErrors reportData = getTaskReportData();

    Map<String, Object> expectedMetrics = ImmutableMap.of(
        RowIngestionMeters.DETERMINE_PARTITIONS,
        ImmutableMap.of(
            RowIngestionMeters.PROCESSED_WITH_ERROR, 0,
            RowIngestionMeters.PROCESSED, 1,
            RowIngestionMeters.PROCESSED_BYTES, 182,
            RowIngestionMeters.UNPARSEABLE, 3,
            RowIngestionMeters.THROWN_AWAY, 1
        ),
        RowIngestionMeters.BUILD_SEGMENTS,
        ImmutableMap.of(
            RowIngestionMeters.PROCESSED_WITH_ERROR, 0,
            RowIngestionMeters.PROCESSED, 0,
            RowIngestionMeters.PROCESSED_BYTES, 0,
            RowIngestionMeters.UNPARSEABLE, 0,
            RowIngestionMeters.THROWN_AWAY, 0
        )
    );

    Assert.assertEquals(expectedMetrics, reportData.getRowStats());

    ParseExceptionReport parseExceptionReport =
        ParseExceptionReport.forPhase(reportData, RowIngestionMeters.DETERMINE_PARTITIONS);
    Assert.assertEquals(expectedMessages, parseExceptionReport.getErrorMessages());

    List<String> expectedInputs = Arrays.asList(
        "{time=99999999999-01-01T00:00:10Z, dim=b, dimLong=2, dimFloat=3.0, val=1}",
        "{time=9.0, dim=a, dimLong=2, dimFloat=3.0, val=1}",
        "{time=unparseable, dim=a, dimLong=2, dimFloat=3.0, val=1}"
    );
    Assert.assertEquals(expectedInputs, parseExceptionReport.getInputs());
  }

  @Test
  public void testCsvWithHeaderOfEmptyColumns() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("ts,,\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("ts,dim,\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("ts,,val\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    // report parse exception
    final IndexTuningConfig tuningConfig = createTuningConfig(2, 1, null, null, true, true);
    final IndexIngestionSpec ingestionSpec;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          DEFAULT_TIMESTAMP_SPEC,
          DimensionsSpec.EMPTY,
          new CsvInputFormat(null, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(DEFAULT_TIMESTAMP_SPEC, DimensionsSpec.EMPTY, null, null, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    IndexTask indexTask = createIndexTask(
        ingestionSpec,
        null
    );

    final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    // the order of result segments can be changed because hash shardSpec is used.
    // the below loop is to make this test deterministic.
    Assert.assertEquals(2, segments.size());
    Assert.assertNotEquals(segments.get(0), segments.get(1));

    for (DataSegment segment : segments) {
      System.out.println(segment.getDimensions());
    }

    for (int i = 0; i < 2; i++) {
      final DataSegment segment = segments.get(i);
      final Set<String> dimensions = new HashSet<>(segment.getDimensions());

      Assert.assertTrue(
          StringUtils.format("Actual dimensions: %s", dimensions),
          dimensions.equals(Sets.newHashSet("column_2")) ||
          dimensions.equals(Sets.newHashSet("dim", "column_2", "column_3"))
      );

      Assert.assertEquals(Collections.singletonList("val"), segment.getMetrics());
      Assert.assertEquals(Intervals.of("2014/P1D"), segment.getInterval());
    }
  }

  @Test
  public void testCsvWithHeaderOfEmptyTimestamp() throws Exception
  {
    final File tmpFile = createTempFile();
    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write(",,\n");
      writer.write("2014-01-01T00:00:10Z,a,1\n");
    }

    final List<String> columns = Arrays.asList("ts", "", "");
    // report parse exception
    final IndexTuningConfig tuningConfig = createTuningConfig(2, null, null, null, false, true);
    final IndexIngestionSpec ingestionSpec;
    List<String> expectedMessages;
    if (useInputFormatApi) {
      ingestionSpec = createIngestionSpec(
          DEFAULT_TIMESTAMP_SPEC,
          DimensionsSpec.EMPTY,
          new CsvInputFormat(columns, null, null, true, 0, null),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    } else {
      ingestionSpec = createIngestionSpec(
          jsonMapper,
          tmpDir,
          new CSVParseSpec(DEFAULT_TIMESTAMP_SPEC, DimensionsSpec.EMPTY, null, columns, true, 0),
          null,
          null,
          tuningConfig,
          false,
          false
      );
    }

    expectedMessages = ImmutableList.of(
        StringUtils.format(
            "Timestamp[null] is unparseable! Event: {column_1=2014-01-01T00:00:10Z, column_2=a, column_3=1} (Path: %s, Record: 1, Line: 2)",
            tmpFile.toURI()
        )
    );
    IndexTask indexTask = createIndexTask(
        ingestionSpec,
        null
    );

    TaskStatus status = runTask(indexTask).lhs;
    Assert.assertEquals(TaskState.FAILED, status.getStatusCode());

    checkTaskStatusErrorMsgForParseExceptionsExceeded(status);

    IngestionStatsAndErrors reportData = getTaskReportData();

    ParseExceptionReport parseExceptionReport =
        ParseExceptionReport.forPhase(reportData, RowIngestionMeters.BUILD_SEGMENTS);
    Assert.assertEquals(expectedMessages, parseExceptionReport.getErrorMessages());

    List<String> expectedInputs = ImmutableList.of(
        "{column_1=2014-01-01T00:00:10Z, column_2=a, column_3=1}"
    );
    Assert.assertEquals(expectedInputs, parseExceptionReport.getInputs());
  }

  @Test
  public void testOverwriteWithSameSegmentGranularity() throws Exception
  {
    populateRollupTestData(createTempFile());

    for (int i = 0; i < 2; i++) {
      final IndexTask indexTask = createIndexTask(
          createDefaultIngestionSpec(
              new UniformGranularitySpec(
                  Granularities.DAY,
                  Granularities.DAY,
                  true,
                  null
              ),
              createTuningConfig(3, 2, 2L, null, false, true),
              false,
              false
          ),
          null
      );

      final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
      final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

      Assert.assertEquals(5, segments.size());

      final Interval expectedInterval = Intervals.of("2014-01-01T00:00:00.000Z/2014-01-02T00:00:00.000Z");
      for (int j = 0; j < 5; j++) {
        final DataSegment segment = segments.get(j);
        Assert.assertEquals(DATASOURCE, segment.getDataSource());
        Assert.assertEquals(expectedInterval, segment.getInterval());
        if (i == 0) {
          Assert.assertEquals(NumberedShardSpec.class, segment.getShardSpec().getClass());
          Assert.assertEquals(j, segment.getShardSpec().getPartitionNum());
        } else {
          if (lockGranularity == LockGranularity.SEGMENT) {
            Assert.assertEquals(NumberedOverwriteShardSpec.class, segment.getShardSpec().getClass());
            final NumberedOverwriteShardSpec numberedOverwriteShardSpec =
                (NumberedOverwriteShardSpec) segment.getShardSpec();
            Assert.assertEquals(
                j + PartitionIds.NON_ROOT_GEN_START_PARTITION_ID,
                numberedOverwriteShardSpec.getPartitionNum()
            );
            Assert.assertEquals(1, numberedOverwriteShardSpec.getMinorVersion());
            Assert.assertEquals(5, numberedOverwriteShardSpec.getAtomicUpdateGroupSize());
            Assert.assertEquals(0, numberedOverwriteShardSpec.getStartRootPartitionId());
            Assert.assertEquals(5, numberedOverwriteShardSpec.getEndRootPartitionId());
          } else {
            Assert.assertEquals(NumberedShardSpec.class, segment.getShardSpec().getClass());
            final NumberedShardSpec numberedShardSpec = (NumberedShardSpec) segment.getShardSpec();
            Assert.assertEquals(j, numberedShardSpec.getPartitionNum());
          }
        }
      }
    }
  }

  @Test
  public void testOverwriteWithDifferentSegmentGranularity() throws Exception
  {
    populateRollupTestData(createTempFile());

    for (int i = 0; i < 2; i++) {
      final Granularity segmentGranularity = i == 0 ? Granularities.DAY : Granularities.MONTH;
      final IndexTask indexTask = createIndexTask(
          createDefaultIngestionSpec(
              new UniformGranularitySpec(
                  segmentGranularity,
                  Granularities.DAY,
                  true,
                  null
              ),
              createTuningConfig(3, 2, 2L, null, false, true),
              false,
              false
          ),
          null
      );

      final DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
      final List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

      Assert.assertEquals(5, segments.size());

      final Interval expectedInterval = i == 0
                                        ? Intervals.of("2014-01-01/2014-01-02")
                                        : Intervals.of("2014-01-01/2014-02-01");
      for (int j = 0; j < 5; j++) {
        final DataSegment segment = segments.get(j);
        Assert.assertEquals(DATASOURCE, segment.getDataSource());
        Assert.assertEquals(expectedInterval, segment.getInterval());
        Assert.assertEquals(NumberedShardSpec.class, segment.getShardSpec().getClass());
        Assert.assertEquals(j, segment.getShardSpec().getPartitionNum());
      }
    }
  }

  @Test
  public void testIndexTaskWithSingleDimPartitionsSpecThrowingException()
  {
    final IndexTask task = createIndexTask(
        createDefaultIngestionSpec(
            null,
            createTuningConfigWithPartitionsSpec(new SingleDimensionPartitionsSpec(null, 1, null, false)),
            false,
            false
        ),
        null
    );
    Exception exception = Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> task.isReady(createActionClient(task))
    );
    Assert.assertEquals(
        "partitionsSpec[org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec] is not supported",
        exception.getMessage()
    );
  }

  @Test
  public void testOldSegmentNotReplacedWhenDropFlagFalse() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.YEAR,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null
    );

    // Ingest data with YEAR segment granularity
    DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    Set<DataSegment> usedSegmentsBeforeOverwrite = getAllUsedSegments();
    Assert.assertEquals(1, usedSegmentsBeforeOverwrite.size());
    for (DataSegment segment : usedSegmentsBeforeOverwrite) {
      Assert.assertTrue(Granularities.YEAR.isAligned(segment.getInterval()));
    }

    indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.MINUTE,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null
    );

    // Ingest data with overwrite and MINUTE segment granularity
    segmentWithSchemas = runSuccessfulTask(indexTask);
    segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(3, segments.size());
    Set<DataSegment> usedSegmentsBeforeAfterOverwrite = getAllUsedSegments();
    Assert.assertEquals(4, usedSegmentsBeforeAfterOverwrite.size());
    int yearSegmentFound = 0;
    int minuteSegmentFound = 0;
    for (DataSegment segment : usedSegmentsBeforeAfterOverwrite) {
      // Used segments after overwrite will contain 1 old segment with YEAR segmentGranularity (from first ingestion)
      // and 3 new segments with MINUTE segmentGranularity (from second ingestion)
      if (usedSegmentsBeforeOverwrite.contains(segment)) {
        Assert.assertTrue(Granularities.YEAR.isAligned(segment.getInterval()));
        yearSegmentFound++;
      } else {
        Assert.assertTrue(Granularities.MINUTE.isAligned(segment.getInterval()));
        minuteSegmentFound++;
      }
    }
    Assert.assertEquals(1, yearSegmentFound);
    Assert.assertEquals(3, minuteSegmentFound);
  }

  @Test
  public void testOldSegmentNotCoveredByTombstonesWhenDropFlagTrueSinceIngestionIntervalDoesNotContainsOldSegment()
      throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T01:00:10Z,a,1\n");
      writer.write("2014-01-01T01:10:20Z,b,1\n");
      writer.write("2014-01-01T01:20:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.DAY,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01T01:00:00Z/2014-01-01T02:00:00Z"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null
    );

    // Ingest data with DAY segment granularity
    DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    Set<DataSegment> usedSegmentsBeforeOverwrite = getAllUsedSegments();
    Assert.assertEquals(1, usedSegmentsBeforeOverwrite.size());
    for (DataSegment segment : usedSegmentsBeforeOverwrite) {
      Assert.assertTrue(Granularities.DAY.isAligned(segment.getInterval()));
    }

    indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01T01:10:00Z/2014-01-01T02:00:00Z"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            true
        ),
        null
    );

    // Ingest data with overwrite and HOUR segment granularity
    segmentWithSchemas = runSuccessfulTask(indexTask);
    segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    Set<DataSegment> usedSegmentsBeforeAfterOverwrite = getAllUsedSegments();
    Assert.assertEquals(2, usedSegmentsBeforeAfterOverwrite.size());
    int segmentFound = 0;
    int tombstonesFound = 0;
    int hourSegmentFound = 0;
    int daySegmentFound = 0;
    for (DataSegment segment : usedSegmentsBeforeAfterOverwrite) {
      if (segment.isTombstone()) {
        tombstonesFound++;
      } else {
        segmentFound++;
      }
      if (usedSegmentsBeforeOverwrite.contains(segment)) {
        Assert.assertTrue(Granularities.DAY.isAligned(segment.getInterval()));
        daySegmentFound++;
      } else {
        Assert.assertTrue(Granularities.HOUR.isAligned(segment.getInterval()));
        hourSegmentFound++;
      }

    }
    Assert.assertEquals(1, daySegmentFound);
    Assert.assertEquals(1, hourSegmentFound);
    Assert.assertEquals(2, segmentFound);
    Assert.assertEquals(0, tombstonesFound);
  }

  @Test
  public void testOldSegmentCoveredByTombstonesWhenDropFlagTrueSinceIngestionIntervalContainsOldSegment()
      throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-01-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.DAY,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01T01:00:00Z/2014-01-01T02:00:00Z"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null
    );

    // Ingest data with DAY segment granularity
    DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    Set<DataSegment> usedSegmentsBeforeOverwrite = getAllUsedSegments();
    Assert.assertEquals(1, usedSegmentsBeforeOverwrite.size());
    for (DataSegment segment : usedSegmentsBeforeOverwrite) {
      Assert.assertTrue(Granularities.DAY.isAligned(segment.getInterval()));
    }

    indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            true
        ),
        null
    );

    // Ingest data with overwrite and HOUR segment granularity
    segmentWithSchemas = runSuccessfulTask(indexTask);
    segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(24, segments.size());
    Set<DataSegment> usedSegmentsBeforeAfterOverwrite = getAllUsedSegments();
    Assert.assertEquals(24, usedSegmentsBeforeAfterOverwrite.size());
    for (DataSegment segment : usedSegmentsBeforeAfterOverwrite) {
      // Used segments after overwrite and drop will contain only the
      // 24 new segments with HOUR segmentGranularity (from second ingestion)
      if (usedSegmentsBeforeOverwrite.contains(segment)) {
        Assert.fail();
      } else {
        Assert.assertTrue(Granularities.HOUR.isAligned(segment.getInterval()));
      }
    }
  }

  @Test
  public void verifyPublishingOnlyTombstones() throws Exception
  {
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-03-01T00:00:10Z,a,1\n");
      writer.write("2014-03-01T01:00:20Z,b,1\n");
      writer.write("2014-03-01T02:00:30Z,c,1\n");
    }

    IndexTask indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.DAY,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-03/2014-04-01"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null
    );

    // Ingest data with DAY segment granularity
    DataSegmentsWithSchemas segmentWithSchemas = runSuccessfulTask(indexTask);
    List<DataSegment> segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size());
    Set<DataSegment> usedSegmentsBeforeOverwrite = getAllUsedSegments();
    Assert.assertEquals(1, usedSegmentsBeforeOverwrite.size());
    for (DataSegment segment : usedSegmentsBeforeOverwrite) {
      Assert.assertTrue(Granularities.DAY.isAligned(segment.getInterval()));
    }

    // create new data but with an ingestion interval appropriate to filter it all out so that only tombstones
    // are created:
    tmpDir = temporaryFolder.newFolder();
    try (BufferedWriter writer = Files.newWriter(createTempFile(), StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,1\n");
      writer.write("2014-12-01T02:00:30Z,c,1\n");
    }

    indexTask = createIndexTask(
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.DAY,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-03-01/2014-04-01")) // filter out all data
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            true
        ),
        null
    );

    // Ingest data with overwrite and same segment granularity
    segmentWithSchemas = runSuccessfulTask(indexTask);
    segments = new ArrayList<>(segmentWithSchemas.getSegments());

    Assert.assertEquals(1, segments.size()); // one tombstone
    Assert.assertTrue(segments.get(0).isTombstone());
  }


  @Test
  public void testErrorWhenDropFlagTrueAndOverwriteFalse()
  {
    Exception exception = Assert.assertThrows(
        IAE.class,
        () -> createIndexTask(
            createDefaultIngestionSpec(
                new UniformGranularitySpec(
                    Granularities.MINUTE,
                    Granularities.MINUTE,
                    Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
                ),
                createTuningConfigWithMaxRowsPerSegment(10, true),
                true,
                true
            ),
            null
        )
    );
    Assert.assertEquals(
        "Cannot simultaneously replace and append to existing segments."
        + " Either dropExisting or appendToExisting should be set to false",
        exception.getMessage()
    );
  }

  @Test
  public void testCleanupIsNoopIfNotStandaloneTask() throws Exception
  {
    new IndexTask(
        null,
        null,
        null,
        "dataSource",
        null,
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.MINUTE,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null,
        0,
        false
    ).cleanUp(null, null);
  }

  @Test
  public void testCleanupIsDoneIfStandaloneTask() throws Exception
  {
    TaskToolbox toolbox = EasyMock.createMock(TaskToolbox.class);
    TaskConfig taskConfig = EasyMock.createMock(TaskConfig.class);
    EasyMock.expect(toolbox.getConfig()).andReturn(taskConfig);
    EasyMock.expect(taskConfig.isEncapsulatedTask()).andReturn(false);
    EasyMock.replay(toolbox, taskConfig);
    new IndexTask(
        null,
        null,
        null,
        "dataSource",
        null,
        createDefaultIngestionSpec(
            new UniformGranularitySpec(
                Granularities.MINUTE,
                Granularities.MINUTE,
                Collections.singletonList(Intervals.of("2014-01-01/2014-01-02"))
            ),
            createTuningConfigWithMaxRowsPerSegment(10, true),
            false,
            false
        ),
        null,
        0,
        true
    ).cleanUp(toolbox, null);
    EasyMock.verify(toolbox, taskConfig);
  }

  public static void checkTaskStatusErrorMsgForParseExceptionsExceeded(TaskStatus status)
  {
    // full stacktrace will be too long and make tests brittle (e.g. if line # changes), just match the main message
    MatcherAssert.assertThat(
        status.getErrorMsg(),
        CoreMatchers.containsString("Max parse exceptions")
    );
  }

  private DataSegmentsWithSchemas runSuccessfulTask(IndexTask task) throws Exception
  {
    Pair<TaskStatus, DataSegmentsWithSchemas> pair = runTask(task);
    Assert.assertEquals(pair.lhs.toString(), TaskState.SUCCESS, pair.lhs.getStatusCode());
    return pair.rhs;
  }

  private Pair<TaskStatus, DataSegmentsWithSchemas> runTask(IndexTask task) throws Exception
  {
    task.addToContext(Tasks.FORCE_TIME_CHUNK_LOCK_KEY, lockGranularity == LockGranularity.TIME_CHUNK);
    final TaskStatus status = taskRunner.run(task).get();

    final Set<DataSegment> segments = new TreeSet<>(taskRunner.getPublishedSegments());
    final SegmentSchemaMapping segmentSchemaMapping = taskRunner.getSegmentSchemas();
    return Pair.of(status, new DataSegmentsWithSchemas(segments, segmentSchemaMapping));
  }

  private static IndexTuningConfig createTuningConfigWithMaxRowsPerSegment(
      int maxRowsPerSegment,
      boolean forceGuaranteedRollup
  )
  {
    return createTuningConfig(
        maxRowsPerSegment,
        1,
        null,
        null,
        forceGuaranteedRollup,
        true
    );
  }

  private static IndexTuningConfig createTuningConfigWithPartitionsSpec(
      PartitionsSpec partitionsSpec
  )
  {
    return createTuningConfig(
        null,
        1,
        null,
        partitionsSpec,
        true,
        true
    );
  }

  static IndexTuningConfig createTuningConfig(
      @Nullable Integer maxRowsPerSegment,
      @Nullable Integer maxRowsInMemory,
      @Nullable Long maxTotalRows,
      @Nullable PartitionsSpec partitionsSpec,
      boolean forceGuaranteedRollup,
      boolean reportParseException
  )
  {
    return TuningConfigBuilder
        .forIndexTask()
        .withMaxRowsPerSegment(maxRowsPerSegment)
        .withMaxRowsInMemory(maxRowsInMemory)
        .withMaxTotalRows(maxTotalRows)
        .withPartitionsSpec(partitionsSpec)
        .withIndexSpec(INDEX_SPEC)
        .withForceGuaranteedRollup(forceGuaranteedRollup)
        .withReportParseExceptions(reportParseException)
        .withMaxSavedParseExceptions(1)
        .build();
  }

  @SuppressWarnings("unchecked")
  private <T> T invokeApi(Function<HttpServletRequest, Response> api)
  {
    final HttpServletRequest request = EasyMock.mock(HttpServletRequest.class);
    EasyMock.expect(request.getMethod()).andReturn("GET").anyTimes();
    EasyMock.expect(request.getAttribute(EasyMock.anyString()))
            .andReturn("allow-all");
    EasyMock.replay(request);
    return (T) api.apply(request).getEntity();
  }

  private Set<DataSegment> getAllUsedSegments()
  {
    return Sets.newHashSet(
        getSegmentsMetadataManager()
            .forceUpdateDataSourcesSnapshot()
            .getAllUsedNonOvershadowedSegments(DATASOURCE, Intervals.ETERNITY)
    );
  }

  private IngestionStatsAndErrors getTaskReportData() throws IOException
  {
    TaskReport.ReportMap taskReports = jsonMapper.readValue(
        taskRunner.getTaskReportsFile(),
        new TypeReference<>() {}
    );
    return IngestionStatsAndErrors.getPayloadFromTaskReports(taskReports);
  }

  private IndexTask createIndexTask(
      IndexIngestionSpec ingestionSchema,
      Map<String, Object> context
  )
  {
    return new IndexTask(null, null, ingestionSchema, context);
  }

  private IndexIngestionSpec createDefaultIngestionSpec(
      @Nullable GranularitySpec granularitySpec,
      IndexTuningConfig tuningConfig,
      boolean appendToExisting,
      Boolean dropExisting
  )
  {
    if (useInputFormatApi) {
      return createIngestionSpec(
          DEFAULT_TIMESTAMP_SPEC,
          DEFAULT_DIMENSIONS_SPEC,
          DEFAULT_INPUT_FORMAT,
          null,
          granularitySpec,
          tuningConfig,
          appendToExisting,
          dropExisting
      );
    } else {
      return createIngestionSpec(
          jsonMapper,
          tmpDir,
          DEFAULT_PARSE_SPEC,
          null,
          granularitySpec,
          tuningConfig,
          appendToExisting,
          dropExisting
      );
    }
  }

  static IndexIngestionSpec createIngestionSpec(
      ObjectMapper objectMapper,
      File baseDir,
      @Nullable ParseSpec parseSpec,
      @Nullable TransformSpec transformSpec,
      @Nullable GranularitySpec granularitySpec,
      IndexTuningConfig tuningConfig,
      boolean appendToExisting,
      Boolean dropExisting
  )
  {
    return createIngestionSpec(
        objectMapper,
        baseDir,
        parseSpec,
        null,
        null,
        null,
        transformSpec,
        granularitySpec,
        tuningConfig,
        appendToExisting,
        dropExisting
    );
  }

  private IndexIngestionSpec createIngestionSpec(
      TimestampSpec timestampSpec,
      DimensionsSpec dimensionsSpec,
      InputFormat inputFormat,
      @Nullable TransformSpec transformSpec,
      @Nullable GranularitySpec granularitySpec,
      IndexTuningConfig tuningConfig,
      boolean appendToExisting,
      Boolean dropExisting
  )
  {
    return createIngestionSpec(
        jsonMapper,
        tmpDir,
        null,
        timestampSpec,
        dimensionsSpec,
        inputFormat,
        transformSpec,
        granularitySpec,
        tuningConfig,
        appendToExisting,
        dropExisting
    );
  }

  private static IndexIngestionSpec createIngestionSpec(
      ObjectMapper objectMapper,
      File baseDir,
      @Nullable ParseSpec parseSpec,
      @Nullable TimestampSpec timestampSpec,
      @Nullable DimensionsSpec dimensionsSpec,
      @Nullable InputFormat inputFormat,
      @Nullable TransformSpec transformSpec,
      @Nullable GranularitySpec granularitySpec,
      IndexTuningConfig tuningConfig,
      boolean appendToExisting,
      Boolean dropExisting
  )
  {
    if (inputFormat != null) {
      Preconditions.checkArgument(parseSpec == null, "Can't use parseSpec");
      return new IndexIngestionSpec(
          DataSchema.builder()
                    .withDataSource(DATASOURCE)
                    .withTimestamp(Preconditions.checkNotNull(timestampSpec, "timestampSpec"))
                    .withDimensions(Preconditions.checkNotNull(dimensionsSpec, "dimensionsSpec"))
                    .withAggregators(new LongSumAggregatorFactory("val", "val"))
                    .withGranularity(
                        granularitySpec != null ? granularitySpec : new UniformGranularitySpec(
                            Granularities.DAY,
                            Granularities.MINUTE,
                            Collections.singletonList(Intervals.of("2014/2015"))
                        )
                    )
                    .withTransform(transformSpec)
                    .build(),
          new IndexIOConfig(
              new LocalInputSource(baseDir, "druid*"),
              inputFormat,
              appendToExisting,
              dropExisting
          ),
          tuningConfig
      );
    } else {
      parseSpec = parseSpec != null ? parseSpec : DEFAULT_PARSE_SPEC;
      return new IndexIngestionSpec(
          DataSchema.builder()
                    .withDataSource(DATASOURCE)
                    .withTimestamp(parseSpec.getTimestampSpec())
                    .withDimensions(parseSpec.getDimensionsSpec())
                    .withAggregators(new LongSumAggregatorFactory("val", "val"))
                    .withGranularity(
                        granularitySpec != null ? granularitySpec : new UniformGranularitySpec(
                            Granularities.DAY,
                            Granularities.MINUTE,
                            Collections.singletonList(Intervals.of("2014/2015"))
                        )
                    )
                    .withTransform(transformSpec)
                    .withObjectMapper(objectMapper)
                    .build(),
          new IndexIOConfig(
              new LocalInputSource(baseDir, "druid*"),
              createInputFormatFromParseSpec(parseSpec),
              appendToExisting,
              dropExisting
          ),
          tuningConfig
      );
    }
  }

  @Test
  public void testEqualsAndHashCode()
  {
    EqualsVerifier.forClass(IndexTuningConfig.class)
                  .withPrefabValues(
                      IndexSpec.class,
                      IndexSpec.DEFAULT,
                      IndexSpec.builder().withDimensionCompression(CompressionStrategy.ZSTD).build()
                  )
                  .usingGetClass()
                  .verify();
  }

  private void verifySchemaAndAggFactory(
      DataSegmentsWithSchemas segmentWithSchemas,
      RowSignature actualRowSignature,
      Map<String, AggregatorFactory> aggregatorFactoryMap
  )
  {
    Assert.assertEquals(segmentWithSchemas.getSegments().size(), segmentWithSchemas.getSegmentSchemaMapping().getSegmentIdToMetadataMap().size());
    Assert.assertEquals(1, segmentWithSchemas.getSegmentSchemaMapping().getSchemaFingerprintToPayloadMap().size());
    Assert.assertEquals(
        actualRowSignature,
        segmentWithSchemas.getSegmentSchemaMapping()
                          .getSchemaFingerprintToPayloadMap()
                          .values()
                          .stream()
                          .findAny()
                          .get()
                          .getRowSignature()
    );
    Assert.assertEquals(
        aggregatorFactoryMap,
        segmentWithSchemas.getSegmentSchemaMapping()
                          .getSchemaFingerprintToPayloadMap()
                          .values()
                          .stream()
                          .findAny()
                          .get()
                          .getAggregatorFactories()
    );
  }
}

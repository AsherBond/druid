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

package org.apache.druid.server.coordinator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.apache.druid.indexer.CompactionEngine;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.transform.CompactionTransformSpec;
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class DataSourceCompactionConfig
{
  /** Must be synced with Tasks.DEFAULT_MERGE_TASK_PRIORITY */
  public static final int DEFAULT_COMPACTION_TASK_PRIORITY = 25;
  // Approx. 100TB. Chosen instead of Long.MAX_VALUE to avoid overflow on web-console and other clients
  private static final long DEFAULT_INPUT_SEGMENT_SIZE_BYTES = 100_000_000_000_000L;
  private static final Period DEFAULT_SKIP_OFFSET_FROM_LATEST = new Period("P1D");

  private final String dataSource;
  private final int taskPriority;
  private final long inputSegmentSizeBytes;

  public static Builder builder()
  {
    return new Builder();
  }

  /**
   * The number of input segments is limited because the byte size of a serialized task spec is limited by
   * org.apache.druid.indexing.overlord.config.RemoteTaskRunnerConfig.maxZnodeBytes.
   */
  @Nullable
  private final Integer maxRowsPerSegment;
  private final Period skipOffsetFromLatest;
  private final UserCompactionTaskQueryTuningConfig tuningConfig;
  private final UserCompactionTaskGranularityConfig granularitySpec;
  private final UserCompactionTaskDimensionsConfig dimensionsSpec;
  private final AggregatorFactory[] metricsSpec;
  private final CompactionTransformSpec transformSpec;
  private final UserCompactionTaskIOConfig ioConfig;
  private final Map<String, Object> taskContext;
  private final CompactionEngine engine;

  @JsonCreator
  public DataSourceCompactionConfig(
      @JsonProperty("dataSource") String dataSource,
      @JsonProperty("taskPriority") @Nullable Integer taskPriority,
      @JsonProperty("inputSegmentSizeBytes") @Nullable Long inputSegmentSizeBytes,
      @JsonProperty("maxRowsPerSegment") @Deprecated @Nullable Integer maxRowsPerSegment,
      @JsonProperty("skipOffsetFromLatest") @Nullable Period skipOffsetFromLatest,
      @JsonProperty("tuningConfig") @Nullable UserCompactionTaskQueryTuningConfig tuningConfig,
      @JsonProperty("granularitySpec") @Nullable UserCompactionTaskGranularityConfig granularitySpec,
      @JsonProperty("dimensionsSpec") @Nullable UserCompactionTaskDimensionsConfig dimensionsSpec,
      @JsonProperty("metricsSpec") @Nullable AggregatorFactory[] metricsSpec,
      @JsonProperty("transformSpec") @Nullable CompactionTransformSpec transformSpec,
      @JsonProperty("ioConfig") @Nullable UserCompactionTaskIOConfig ioConfig,
      @JsonProperty("engine") @Nullable CompactionEngine engine,
      @JsonProperty("taskContext") @Nullable Map<String, Object> taskContext
  )
  {
    this.dataSource = Preconditions.checkNotNull(dataSource, "dataSource");
    this.taskPriority = taskPriority == null
                        ? DEFAULT_COMPACTION_TASK_PRIORITY
                        : taskPriority;
    this.inputSegmentSizeBytes = inputSegmentSizeBytes == null
                                 ? DEFAULT_INPUT_SEGMENT_SIZE_BYTES
                                 : inputSegmentSizeBytes;
    this.maxRowsPerSegment = maxRowsPerSegment;
    this.skipOffsetFromLatest = skipOffsetFromLatest == null ? DEFAULT_SKIP_OFFSET_FROM_LATEST : skipOffsetFromLatest;
    this.tuningConfig = tuningConfig;
    this.ioConfig = ioConfig;
    this.granularitySpec = granularitySpec;
    this.metricsSpec = metricsSpec;
    this.dimensionsSpec = dimensionsSpec;
    this.transformSpec = transformSpec;
    this.taskContext = taskContext;
    this.engine = engine;
  }

  @JsonProperty
  public String getDataSource()
  {
    return dataSource;
  }

  @JsonProperty
  public int getTaskPriority()
  {
    return taskPriority;
  }

  @JsonProperty
  public long getInputSegmentSizeBytes()
  {
    return inputSegmentSizeBytes;
  }

  @Deprecated
  @JsonProperty
  @Nullable
  public Integer getMaxRowsPerSegment()
  {
    return maxRowsPerSegment;
  }

  @JsonProperty
  public Period getSkipOffsetFromLatest()
  {
    return skipOffsetFromLatest;
  }

  @JsonProperty
  @Nullable
  public UserCompactionTaskQueryTuningConfig getTuningConfig()
  {
    return tuningConfig;
  }

  @JsonProperty
  @Nullable
  public UserCompactionTaskIOConfig getIoConfig()
  {
    return ioConfig;
  }

  @JsonProperty
  @Nullable
  public UserCompactionTaskGranularityConfig getGranularitySpec()
  {
    return granularitySpec;
  }

  @JsonProperty
  @Nullable
  public UserCompactionTaskDimensionsConfig getDimensionsSpec()
  {
    return dimensionsSpec;
  }

  @JsonProperty
  @Nullable
  public CompactionTransformSpec getTransformSpec()
  {
    return transformSpec;
  }

  @JsonProperty
  @Nullable
  public AggregatorFactory[] getMetricsSpec()
  {
    return metricsSpec;
  }

  @JsonProperty
  @Nullable
  public Map<String, Object> getTaskContext()
  {
    return taskContext;
  }

  @JsonProperty
  @Nullable
  public CompactionEngine getEngine()
  {
    return engine;
  }

  @Nullable
  @JsonIgnore
  public Granularity getSegmentGranularity()
  {
    return granularitySpec == null ? null : granularitySpec.getSegmentGranularity();
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DataSourceCompactionConfig that = (DataSourceCompactionConfig) o;
    return taskPriority == that.taskPriority &&
           inputSegmentSizeBytes == that.inputSegmentSizeBytes &&
           Objects.equals(dataSource, that.dataSource) &&
           Objects.equals(maxRowsPerSegment, that.maxRowsPerSegment) &&
           Objects.equals(skipOffsetFromLatest, that.skipOffsetFromLatest) &&
           Objects.equals(tuningConfig, that.tuningConfig) &&
           Objects.equals(granularitySpec, that.granularitySpec) &&
           Objects.equals(dimensionsSpec, that.dimensionsSpec) &&
           Arrays.equals(metricsSpec, that.metricsSpec) &&
           Objects.equals(transformSpec, that.transformSpec) &&
           Objects.equals(ioConfig, that.ioConfig) &&
           this.engine == that.engine &&
           Objects.equals(taskContext, that.taskContext);
  }

  @Override
  public int hashCode()
  {
    int result = Objects.hash(
        dataSource,
        taskPriority,
        inputSegmentSizeBytes,
        maxRowsPerSegment,
        skipOffsetFromLatest,
        tuningConfig,
        granularitySpec,
        dimensionsSpec,
        transformSpec,
        ioConfig,
        taskContext,
        engine
    );
    result = 31 * result + Arrays.hashCode(metricsSpec);
    return result;
  }

  public static class Builder
  {
    private String dataSource;
    private Integer taskPriority;
    private Long inputSegmentSizeBytes;
    private Integer maxRowsPerSegment;
    private Period skipOffsetFromLatest;
    private UserCompactionTaskQueryTuningConfig tuningConfig;
    private UserCompactionTaskGranularityConfig granularitySpec;
    private UserCompactionTaskDimensionsConfig dimensionsSpec;
    private AggregatorFactory[] metricsSpec;
    private CompactionTransformSpec transformSpec;
    private UserCompactionTaskIOConfig ioConfig;
    private CompactionEngine engine;
    private Map<String, Object> taskContext;

    public DataSourceCompactionConfig build()
    {
      return new DataSourceCompactionConfig(
          dataSource,
          taskPriority,
          inputSegmentSizeBytes,
          maxRowsPerSegment,
          skipOffsetFromLatest,
          tuningConfig,
          granularitySpec,
          dimensionsSpec,
          metricsSpec,
          transformSpec,
          ioConfig,
          engine,
          taskContext
      );
    }

    public Builder forDataSource(String dataSource)
    {
      this.dataSource = dataSource;
      return this;
    }

    public Builder withTaskPriority(Integer taskPriority)
    {
      this.taskPriority = taskPriority;
      return this;
    }

    public Builder withInputSegmentSizeBytes(Long inputSegmentSizeBytes)
    {
      this.inputSegmentSizeBytes = inputSegmentSizeBytes;
      return this;
    }

    @Deprecated
    public Builder withMaxRowsPerSegment(Integer maxRowsPerSegment)
    {
      this.maxRowsPerSegment = maxRowsPerSegment;
      return this;
    }

    public Builder withSkipOffsetFromLatest(Period skipOffsetFromLatest)
    {
      this.skipOffsetFromLatest = skipOffsetFromLatest;
      return this;
    }

    public Builder withTuningConfig(
        UserCompactionTaskQueryTuningConfig tuningConfig
    )
    {
      this.tuningConfig = tuningConfig;
      return this;
    }

    public Builder withGranularitySpec(
        UserCompactionTaskGranularityConfig granularitySpec
    )
    {
      this.granularitySpec = granularitySpec;
      return this;
    }

    public Builder withDimensionsSpec(
        UserCompactionTaskDimensionsConfig dimensionsSpec
    )
    {
      this.dimensionsSpec = dimensionsSpec;
      return this;
    }

    public Builder withMetricsSpec(AggregatorFactory[] metricsSpec)
    {
      this.metricsSpec = metricsSpec;
      return this;
    }

    public Builder withTransformSpec(
        CompactionTransformSpec transformSpec
    )
    {
      this.transformSpec = transformSpec;
      return this;
    }

    public Builder withIoConfig(UserCompactionTaskIOConfig ioConfig)
    {
      this.ioConfig = ioConfig;
      return this;
    }

    public Builder withEngine(CompactionEngine engine)
    {
      this.engine = engine;
      return this;
    }

    public Builder withTaskContext(Map<String, Object> taskContext)
    {
      this.taskContext = taskContext;
      return this;
    }
  }
}

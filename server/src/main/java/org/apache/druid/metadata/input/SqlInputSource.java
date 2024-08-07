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

package org.apache.druid.metadata.input;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import org.apache.druid.data.input.AbstractInputSource;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.InputSourceReader;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.SplitHintSpec;
import org.apache.druid.data.input.impl.InputEntityIteratingReader;
import org.apache.druid.data.input.impl.SplittableInputSource;
import org.apache.druid.data.input.impl.systemfield.SystemFieldDecoratorFactory;
import org.apache.druid.guice.annotations.Smile;
import org.apache.druid.java.util.common.CloseableIterators;
import org.apache.druid.metadata.SQLInputSourceDatabaseConnector;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class SqlInputSource extends AbstractInputSource implements SplittableInputSource<String>
{
  static final String TYPE_KEY = "sql";
  private final List<String> sqls;
  private final SQLInputSourceDatabaseConnector sqlInputSourceDatabaseConnector;
  private final ObjectMapper objectMapper;
  private final boolean foldCase;

  @JsonCreator
  public SqlInputSource(
      @JsonProperty("sqls") List<String> sqls,
      @JsonProperty("foldCase") boolean foldCase,
      @JsonProperty("database") SQLInputSourceDatabaseConnector sqlInputSourceDatabaseConnector,
      @JacksonInject @Smile ObjectMapper objectMapper
  )
  {
    Preconditions.checkArgument(sqls.size() > 0, "No SQL queries provided");

    this.sqls = sqls;
    this.foldCase = foldCase;
    this.sqlInputSourceDatabaseConnector = Preconditions.checkNotNull(
        sqlInputSourceDatabaseConnector,
        "SQL Metadata Connector not configured!"
    );
    this.objectMapper = objectMapper;
  }

  @JsonIgnore
  @Nonnull
  @Override
  public Set<String> getTypes()
  {
    return Collections.singleton(TYPE_KEY);
  }

  @JsonProperty
  public List<String> getSqls()
  {
    return sqls;
  }

  @JsonProperty
  public boolean isFoldCase()
  {
    return foldCase;
  }

  @JsonProperty("database")
  public SQLInputSourceDatabaseConnector getSQLInputSourceDatabaseConnector()
  {
    return sqlInputSourceDatabaseConnector;
  }

  @Override
  public Stream<InputSplit<String>> createSplits(InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec)
  {
    return sqls.stream().map(InputSplit::new);
  }

  @Override
  public int estimateNumSplits(InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec)
  {
    return sqls.size();
  }

  @Override
  public SplittableInputSource<String> withSplit(InputSplit<String> split)
  {
    return new SqlInputSource(
        Collections.singletonList(split.get()),
        foldCase,
        sqlInputSourceDatabaseConnector,
        objectMapper
    );
  }

  @Override
  protected InputSourceReader fixedFormatReader(InputRowSchema inputRowSchema, @Nullable File temporaryDirectory)
  {
    final SqlInputFormat inputFormat = new SqlInputFormat(objectMapper);
    return new InputEntityIteratingReader(
        inputRowSchema,
        inputFormat,
        CloseableIterators.withEmptyBaggage(createSplits(inputFormat, null)
                                                .map(split -> new SqlEntity(split.get(),
                                                                            sqlInputSourceDatabaseConnector, foldCase, objectMapper)).iterator()),
        SystemFieldDecoratorFactory.NONE,
        temporaryDirectory
    );
  }

  @Override
  public boolean needsFormat()
  {
    return false;
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
    SqlInputSource that = (SqlInputSource) o;
    return foldCase == that.foldCase &&
           sqls.equals(that.sqls) &&
           sqlInputSourceDatabaseConnector.equals(that.sqlInputSourceDatabaseConnector);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(sqls, sqlInputSourceDatabaseConnector, foldCase);
  }
}

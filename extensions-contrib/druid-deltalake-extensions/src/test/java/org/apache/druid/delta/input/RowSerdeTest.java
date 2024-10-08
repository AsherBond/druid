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

package org.apache.druid.delta.input;

import io.delta.kernel.Scan;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.exceptions.TableNotFoundException;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collection;

public class RowSerdeTest
{
  public static Collection<Object[]> data()
  {
    Object[][] data = new Object[][]{
        {NonPartitionedDeltaTable.DELTA_TABLE_PATH},
        {PartitionedDeltaTable.DELTA_TABLE_PATH},
        {ComplexTypesDeltaTable.DELTA_TABLE_PATH},
        {SnapshotDeltaTable.DELTA_TABLE_PATH}
    };
    return Arrays.asList(data);
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testSerializeDeserializeRoundtrip(final String tablePath) throws TableNotFoundException
  {
    final DefaultEngine engine = DefaultEngine.create(new Configuration());
    final Scan scan = DeltaTestUtils.getScan(engine, tablePath);
    final Row scanState = scan.getScanState(engine);

    final String rowJson = RowSerde.serializeRowToJson(scanState);
    final Row row = RowSerde.deserializeRowFromJson(engine, rowJson);

    Assertions.assertEquals(scanState.getSchema(), row.getSchema());
  }
}

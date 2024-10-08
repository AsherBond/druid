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

package org.apache.druid.frame.write.columnar;

import org.apache.datasketches.memory.WritableMemory;
import org.apache.druid.frame.allocation.MemoryAllocator;
import org.apache.druid.segment.ColumnValueSelector;

/**
 * Columnar frame writer for {@link org.apache.druid.segment.column.ColumnType#LONG_ARRAY} columns
 */
public class LongArrayFrameColumnWriter extends NumericArrayFrameColumnWriter
{
  public LongArrayFrameColumnWriter(
      ColumnValueSelector selector,
      MemoryAllocator allocator
  )
  {
    super(selector, allocator, FrameColumnWriters.TYPE_LONG_ARRAY);
  }

  @Override
  int elementSizeBytes()
  {
    return Long.BYTES;
  }

  @Override
  void putNull(WritableMemory memory, long offset)
  {
    memory.putLong(offset, 0L);
  }

  @Override
  void putArrayElement(WritableMemory memory, long offset, Number element)
  {
    memory.putLong(offset, element.longValue());
  }
}

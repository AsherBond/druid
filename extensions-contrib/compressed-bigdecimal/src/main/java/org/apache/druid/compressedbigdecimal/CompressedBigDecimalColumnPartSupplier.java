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

package org.apache.druid.compressedbigdecimal;


import com.google.common.base.Supplier;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.column.ComplexColumn;
import org.apache.druid.segment.data.CompressedVSizeColumnarIntsSupplier;
import org.apache.druid.segment.data.V3CompressedVSizeColumnarMultiIntsSupplier;

import java.nio.ByteBuffer;

/**
 * Complex column supplier that understands {@link CompressedBigDecimal} values.
 */
public class CompressedBigDecimalColumnPartSupplier implements Supplier<ComplexColumn>
{
  public static final int VERSION = 0x1;

  /**
   * Compressed.
   *
   * @param buffer Byte buffer
   * @param smooshMapper mapper for secondary files, in case of large columns
   * @return new instance of CompressedBigDecimalColumnPartSupplier
   */
  public static CompressedBigDecimalColumnPartSupplier fromByteBuffer(
      ByteBuffer buffer,
      SmooshedFileMapper smooshMapper
  )
  {
    byte versionFromBuffer = buffer.get();

    if (versionFromBuffer == VERSION) {
      int positionStart = buffer.position();

      CompressedVSizeColumnarIntsSupplier scaleSupplier = CompressedVSizeColumnarIntsSupplier.fromByteBuffer(
          buffer,
          IndexIO.BYTE_ORDER,
          smooshMapper
      );

      V3CompressedVSizeColumnarMultiIntsSupplier magnitudeSupplier =
          V3CompressedVSizeColumnarMultiIntsSupplier.fromByteBuffer(buffer, IndexIO.BYTE_ORDER, smooshMapper);

      return new CompressedBigDecimalColumnPartSupplier(
          buffer.position() - positionStart,
          scaleSupplier,
          magnitudeSupplier
      );
    } else {
      throw new IAE("Unknown version[%s]", versionFromBuffer);
    }
  }

  private final int byteSize;
  private final CompressedVSizeColumnarIntsSupplier scaleSupplier;
  private final V3CompressedVSizeColumnarMultiIntsSupplier magnitudeSupplier;

  /**
   * Constructor.
   *
   * @param scaleSupplier     scale supplier
   * @param magnitudeSupplier supplied of results
   */
  public CompressedBigDecimalColumnPartSupplier(
      int byteSize,
      CompressedVSizeColumnarIntsSupplier scaleSupplier,
      V3CompressedVSizeColumnarMultiIntsSupplier magnitudeSupplier
  )
  {
    this.byteSize = byteSize;
    this.scaleSupplier = scaleSupplier;
    this.magnitudeSupplier = magnitudeSupplier;
  }

  @Override
  public ComplexColumn get()
  {
    return new CompressedBigDecimalColumn(byteSize, scaleSupplier.get(), magnitudeSupplier.get());
  }
}

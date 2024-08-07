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

package org.apache.druid.segment.data;

import com.fasterxml.jackson.annotation.JsonTypeName;
import org.apache.druid.collections.bitmap.BitmapFactory;
import org.apache.druid.collections.bitmap.ConciseBitmapFactory;
import org.apache.druid.collections.bitmap.RoaringBitmapFactory;
import org.apache.druid.error.DruidException;

public class BitmapSerde
{

  // default bitmap indices for Druid
  // concise was default from 0.7+, roaring is default 0.18+
  // annotation required so Jackson doesn't get confused
  @JsonTypeName("roaring")
  public static class DefaultBitmapSerdeFactory extends RoaringBitmapSerdeFactory
  {
    public DefaultBitmapSerdeFactory()
    {
      super(RoaringBitmapFactory.INSTANCE);
    }
  }

  // default bitmap indices in Druid <= 0.6.x
  @JsonTypeName("concise")
  // annotation required so Jackson doesn't get confused by subclassing
  public static class LegacyBitmapSerdeFactory extends ConciseBitmapSerdeFactory
  {
  }

  public static BitmapSerdeFactory createLegacyFactory()
  {
    return new LegacyBitmapSerdeFactory();
  }

  public static BitmapSerdeFactory forBitmapFactory(BitmapFactory factory)
  {
    if (factory instanceof RoaringBitmapFactory) {
      return new DefaultBitmapSerdeFactory();
    } else if (factory instanceof ConciseBitmapFactory) {
      return new ConciseBitmapSerdeFactory();
    }
    throw DruidException.defensive("Unknown type of bitmapFactory [%s]", factory.getClass());
  }
}

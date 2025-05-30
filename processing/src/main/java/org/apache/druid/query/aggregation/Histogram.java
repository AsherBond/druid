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

package org.apache.druid.query.aggregation;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Preconditions;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class Histogram
{
  protected float[] breaks;
  protected long[] bins;
  protected transient long count;
  protected float min;
  protected float max;

  public Histogram(float[] breaks)
  {
    Preconditions.checkArgument(breaks != null, "Histogram breaks must not be null");

    this.breaks = breaks;
    this.bins = new long[this.breaks.length + 1];
    this.count = 0;
    this.min = Float.POSITIVE_INFINITY;
    this.max = Float.NEGATIVE_INFINITY;
  }

  public Histogram(float[] breaks, long[] bins, float min, float max)
  {
    this.breaks = breaks;
    this.bins = bins;
    this.min = min;
    this.max = max;
    for (long k : bins) {
      this.count += k;
    }
  }

  public Histogram(Histogram other)
  {
    this.breaks = other.breaks;
    this.bins = other.bins.clone();
    this.min = other.min;
    this.max = other.max;
    this.count = other.count;
  }

  public void copyFrom(Histogram other)
  {
    this.breaks = other.breaks;
    if (this.bins.length == other.bins.length) {
      System.arraycopy(other.bins, 0, this.bins, 0, this.bins.length);
    } else {
      this.bins = other.bins.clone();
    }
    this.min = other.min;
    this.max = other.max;
    this.count = other.count;
  }

  public void offer(float d)
  {
    if (d > max) {
      max = d;
    }
    if (d < min) {
      min = d;
    }

    int index = Arrays.binarySearch(breaks, d);
    int pos = (index >= 0) ? index : -(index + 1);
    bins[pos]++;
    count++;
  }

  public Histogram fold(Histogram h)
  {
    Preconditions.checkArgument(Arrays.equals(breaks, h.breaks), "Cannot fold histograms with different breaks");

    if (h.min < min) {
      min = h.min;
    }
    if (h.max > max) {
      max = h.max;
    }

    count += h.count;
    for (int i = 0; i < bins.length; ++i) {
      bins[i] += h.bins[i];
    }
    return this;
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

    Histogram histogram = (Histogram) o;

    if (count != histogram.count) {
      return false;
    }
    if (Float.compare(histogram.max, max) != 0) {
      return false;
    }
    if (Float.compare(histogram.min, min) != 0) {
      return false;
    }
    if (!Arrays.equals(bins, histogram.bins)) {
      return false;
    }
    if (!Arrays.equals(breaks, histogram.breaks)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode()
  {
    int result = Arrays.hashCode(breaks);
    result = 31 * result + Arrays.hashCode(bins);
    result = 31 * result + (min != +0.0f ? Float.floatToIntBits(min) : 0);
    result = 31 * result + (max != +0.0f ? Float.floatToIntBits(max) : 0);
    return result;
  }

  @JsonValue
  public byte[] toBytes()
  {
    ByteBuffer buf = ByteBuffer.allocate(
        Integer.BYTES + Float.BYTES * breaks.length + Long.BYTES * bins.length + Float.BYTES * 2
    );

    buf.putInt(breaks.length);
    for (float b : breaks) {
      buf.putFloat(b);
    }
    for (long c : bins) {
      buf.putLong(c);
    }
    buf.putFloat(min);
    buf.putFloat(max);

    return buf.array();
  }

  /**
   * Returns a visual representation of a histogram object.
   * Initially returns an array of just the min. and max. values
   * but can also support the addition of quantiles.
   *
   * @return a visual representation of this histogram
   */
  public HistogramVisual asVisual()
  {
    float[] visualCounts = new float[bins.length - 2];
    for (int i = 0; i < visualCounts.length; ++i) {
      visualCounts[i] = (float) bins[i + 1];
    }
    return new HistogramVisual(breaks, visualCounts, new float[]{min, max});
  }

  public static Histogram fromBytes(byte[] bytes)
  {
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    return fromBytes(buf);
  }

  public static Histogram fromBytes(ByteBuffer buf)
  {
    int n = buf.getInt();
    float[] breaks = new float[n];
    long[] bins = new long[n + 1];

    for (int i = 0; i < breaks.length; ++i) {
      breaks[i] = buf.getFloat();
    }
    for (int i = 0; i < bins.length; ++i) {
      bins[i] = buf.getLong();
    }

    float min = buf.getFloat();
    float max = buf.getFloat();

    return new Histogram(breaks, bins, min, max);
  }

  @Override
  public String toString()
  {
    return "Histogram{" +
           "bins=" + Arrays.toString(bins) +
           ", count=" + count +
           ", breaks=" + Arrays.toString(breaks) +
           '}';
  }
}

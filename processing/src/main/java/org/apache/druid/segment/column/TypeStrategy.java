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

package org.apache.druid.segment.column;

import it.unimi.dsi.fastutil.Hash;
import org.apache.druid.error.DruidException;

import java.nio.ByteBuffer;
import java.util.Comparator;

/**
 * TypeStrategy provides value comparison and binary serialization for Druid types. This can be obtained for ANY Druid
 * type via {@link TypeSignature#getStrategy()}.
 *
 * IMPORTANT!!! DO NOT USE THIS FOR WRITING COLUMNS, THERE ARE VERY LIKELY FAR BETTER WAYS TO DO THIS. However, if you
 * need to store a single value or small number of values, continue reading.
 *
 * ALSO IMPORTANT!!! This is primarily intended for writing ephemeral values within a single process, and is not
 * especially well suited (by itself) for persistent storage of data or cross process transfer. The support typically
 * necessary for such more persistent storage, such as tracking version of a format or endianness of the values, should
 * be handled externally to support these use cases.
 *
 * All implementations of this mechanism support reading and writing ONLY non-null values. To handle nulls inline with
 * your values, consider {@link NullableTypeStrategy}, which might be acceptable to use if you need to read and write
 * nullable values, AND, you have enough memory to burn a full byte for every value you want to store. It will store
 * values with a leading byte containing either {@link TypeStrategies#IS_NULL_BYTE} or
 * {@link TypeStrategies#IS_NOT_NULL_BYTE} as appropriate. If you have a lot of values to write and a lot of nulls,
 * consider alternative approaches to tracking your nulls instead.
 *
 * This mechanism allows using the natural {@link ByteBuffer#position()} and modify the underlying position as they
 * operate, and also random access reads are specific offets, which do not modify the underlying position. If a method
 * accepts an offset parameter, it does not modify the position, if not, it does.
 *
 * The only methods implementors are required to provide are {@link #read(ByteBuffer)},
 * {@link #write(ByteBuffer, Object, int)} and {@link #estimateSizeBytes(Object)}, default implementations are provided
 * to set and reset buffer positions as appropriate for the offset based methods, but may be overridden if a more
 * optimized implementation is needed.
 *
 * Implementations of this interface should be thread safe, but may not use {@link ByteBuffer} in a thread safe manner,
 * potentially modifying positions and limits, either temporarily or permanently depending on which set of methods is
 * called.
 *
 * This interface extends {@code Comparator<Object>} instead of {@code Comparator<T>} because trying to specialize the
 * type of the comparison method can run into issues for comparators of objects that can sometimes be of a different
 * java class type.  For example, {@code Comparator<Long>} cannot accept Integer objects in its comparison method
 * and there is no easy way for this interface definition to allow {@code TypeStrategy<Long>} to actually be a
 * {@code Comparator<Number>}.  So, we fall back to effectively erasing the generic type and having them all be
 * {@code Comparator<Object>}.
 */
public interface TypeStrategy<T> extends Comparator<Object>, Hash.Strategy<T>
{
  /**
   * Estimate the size in bytes that writing this value to memory would require. This method is not required to be
   * exactly correct, but many implementations might be. Implementations should err on the side of over-estimating if
   * exact sizing is not efficient.
   *
   * Example usage of this method is estimating heap memory usage for an aggregator or the amount of buffer which
   * might need allocated to then {@link #write} a value
   */
  int estimateSizeBytes(T value);

  /**
   * Read a non-null value from the {@link ByteBuffer} at the current {@link ByteBuffer#position()}. This will move
   * the underlying position by the size of the value read.
   *
   * The value returned from this method may retain a reference to the provided {@link ByteBuffer}. If it does, then
   * {@link #readRetainsBufferReference()} returns true.
   */
  T read(ByteBuffer buffer);

  /**
   * Whether the {@link #read} methods return an object that may retain a reference to the underlying memory of the
   * provided {@link ByteBuffer}. If a reference is sometimes retained, this method returns true. It returns false if,
   * and only if, a reference is *never* retained.
   * <p>
   * If this method returns true, and the caller does not control the lifecycle of the underlying memory or cannot
   * ensure that it will not change over the lifetime of the returned object, callers should copy the memory to a new
   * location that they do control the lifecycle of and will be available for the duration of the returned object.
   */
  boolean readRetainsBufferReference();

  /**
   * Write a non-null value to the {@link ByteBuffer} at position {@link ByteBuffer#position()}. This will move the
   * underlying position by the size of the value written.
   *
   * This method returns the number of bytes written. If writing the value would take more than 'maxSizeBytes', this
   * method will return a negative value indicating the number of additional bytes that would be required to fully
   * write the value. Partial results may be written to the buffer when in this state, and the position may be left
   * at whatever point the implementation ran out of space while writing the value. Callers should save the starting
   * position if it is necessary to 'rewind' after a partial write.
   *
   * Callers MUST check that the return value is positive which indicates a successful write, while a negative response
   * a partial write.
   *
   * @return number of bytes written
   */
  int write(ByteBuffer buffer, T value, int maxSizeBytes);

  /**
   * Read a non-null value from the {@link ByteBuffer} at the requested position. This will not permanently move the
   * underlying {@link ByteBuffer#position()}, but may temporarily modify the buffer position during reading so cannot
   * be considered thread safe usage of the buffer.
   *
   * The contract of this method is that any value returned from this method MUST be completely detached from the
   * underlying {@link ByteBuffer}, since it might outlive the memory location being allocated to hold the object.
   * In other words, if an object is memory mapped, it must be copied on heap, or relocated to another memory location
   * that is owned by the caller with {@link #write}.
   */
  default T read(ByteBuffer buffer, int offset)
  {
    final int oldPosition = buffer.position();
    try {
      buffer.position(offset);
      return read(buffer);
    }
    finally {
      buffer.position(oldPosition);
    }
  }

  /**
   * Write a non-null value to the {@link ByteBuffer} at the requested position. This will not permanently move the
   * underlying {@link ByteBuffer#position()}, but may temporarily modify the buffer position during reading so cannot
   * be considered thread safe usage of the buffer.
   *
   * This method returns the number of bytes written. If writing the value would take more than 'maxSizeBytes', this
   * method will return a negative value indicating the number of additional bytes that would be required to fully
   * write the value. Partial results may be written to the buffer when in this state, but the underlying buffer
   * position will be unaffected regardless of whether a write operation was successful or not.
   *
   * Callers MUST check that the return value is positive which indicates a successful write, while a negative response
   * a partial write.
   *
   * @return number of bytes written
   */
  default int write(ByteBuffer buffer, int offset, T value, int maxSizeBytes)
  {
    final int oldPosition = buffer.position();
    try {
      buffer.position(offset);
      return write(buffer, value, maxSizeBytes);
    }
    finally {
      buffer.position(oldPosition);
    }
  }

  /**
   * Translate raw byte array into a value. This is primarily useful for transforming self contained values that are
   * serialized into byte arrays, such as happens with 'COMPLEX' types which serialize to base64 strings in JSON
   * responses.
   *
   * 'COMPLEX' types should implement this method to participate in the expression systems built-in function
   * to deserialize base64 encoded values,
   * {@link org.apache.druid.math.expr.BuiltInExprMacros.ComplexDecodeBase64ExprMacro}.
   */
  default T fromBytes(byte[] value)
  {
    throw new IllegalStateException("Not supported");
  }

  /**
   * Whether the type is groupable or not. This is always true for all the primitive types, arrays, and nested arrays
   * therefore the SQL and the native layer might ignore this flag for those types. For complex types, this flag can be
   * true or false, depending on whether the semantics and implementation of the type naturally leads to groupability
   * or not. For example, it makes sense for JSON columns to be groupable, however there is little sense in grouping
   * sketches (before finalizing).
   * <p>
   * If a type is groupable, following statements MUST hold:
   * <p>
   * a. {@link #equals(Object, Object)} must be implemented. It should return true if and only if two objects are equal
   *    and can be grouped together.
   * <p>
   * b. {@link #hashCode(Object)} must be implemented, and must be consistent with equals. It should return a hashCode
   *    for the given object. For two objects that are equal, it must return the same hash value. For two objects that are
   *    not equal, it can return the same hash value (or not). A conscious effort must be made to minimise collisions between
   *    the hash values of two non-equal objects for faster grouping.
   * <p>
   * c. {@link #compare(Object, Object)} must be consistent with equals. Apart from abiding by the definition of
   *    {@link Comparator#compare}, it must not return 0 for two objects that are not equals, and converse must also hold,
   *    i.e. if the value returned by compare is not zero, then the arguments must not be equal.
   * <p>
   * d. {@link #getClazz()} should return the Java class for the dimension represented by the type. This will be used by the
   *    mapper to deserialize the object during tasks like broker-historical interaction and spilling to the disk.
   */
  default boolean groupable()
  {
    return false;
  }

  /**
   * @see #groupable()
   */
  @Override
  default int hashCode(T o)
  {
    throw DruidException.defensive("Not implemented. Check groupable() first");
  }

  /**
   * @see #groupable()
   */
  @Override
  default boolean equals(T a, T b)
  {
    throw DruidException.defensive("Not implemented. Check groupable() first");
  }

  /**
   * @see #groupable()
   */
  default Class<?> getClazz()
  {
    throw DruidException.defensive("Not implemented. Check groupable() first");
  }
}

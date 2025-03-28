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

package org.apache.druid.math.expr;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.druid.java.util.common.UOE;
import org.apache.druid.math.expr.vector.ExprVectorProcessor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Base interface describing the mechanism used to evaluate an {@link ApplyFunctionExpr}, which 'applies' a
 * {@link LambdaExpr} to one or more array {@link Expr}.  All {@link ApplyFunction} implementations are immutable.
 */
public interface ApplyFunction extends NamedFunction
{
  /**
   * Check if an apply function can be 'vectorized', for a given {@link LambdaExpr} and set of {@link Expr} inputs.
   * If this method returns true, {@link #asVectorProcessor} is expected to produce a {@link ExprVectorProcessor} which
   * can evaluate values in batches to use with vectorized query engines.
   *
   * @see Expr#canVectorize(Expr.InputBindingInspector)
   * @see Function#canVectorize(Expr.InputBindingInspector, List)
   */
  default boolean canVectorize(Expr.InputBindingInspector inspector, Expr lambda, List<Expr> args)
  {
    return false;
  }

  /**
   * Builds a 'vectorized' function expression processor, that can build vectorized processors for its input values
   * using {@link Expr#asVectorProcessor}, for use in vectorized query engines.
   *
   * @see Expr#asVectorProcessor(Expr.VectorInputBindingInspector)
   * @see Function#asVectorProcessor(Expr.VectorInputBindingInspector, List)
   */
  default <T> ExprVectorProcessor<T> asVectorProcessor(
      Expr.VectorInputBindingInspector inspector,
      Expr lambda,
      List<Expr> args
  )
  {
    throw new UOE("%s is not vectorized", name());
  }

  /**
   * Apply {@link LambdaExpr} to argument list of {@link Expr} given a set of outer {@link Expr.ObjectBinding}. These
   * outer bindings will be used to form the scope for the bindings used to evaluate the {@link LambdaExpr}, which use
   * the array inputs to supply scalar values to use as bindings for {@link IdentifierExpr} in the lambda body.
   */
  ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings);

  /**
   * Get list of input arguments which must evaluate to an array {@link ExprType}
   */
  Set<Expr> getArrayInputs(List<Expr> args);

  /**
   * Returns true if apply function produces an array output. All {@link ApplyFunction} implementations are expected to
   * exclusively produce either scalar or array values.
   */
  default boolean hasArrayOutput(LambdaExpr lambdaExpr)
  {
    return false;
  }

  /**
   * Validate function arguments. This method is called whenever a {@link ApplyFunctionExpr} is created, and should
   * validate everything that is feasible up front. Note that input type information is typically unavailable at the
   * time {@link Expr} are parsed, and so this method is incapable of performing complete validation.
   */
  void validateArguments(LambdaExpr lambdaExpr, List<Expr> args);

  /**
   * Compute the output type of this function for a given lambda and the argument expressions which will be applied as
   * its inputs.
   *
   * @see Expr#getOutputType
   */
  @Nullable
  ExpressionType getOutputType(Expr.InputBindingInspector inspector, LambdaExpr expr, List<Expr> args);

  /**
   * Base class for "map" functions, which are a class of {@link ApplyFunction} which take a lambda function that is
   * mapped to the values of an {@link IndexableMapLambdaObjectBinding} which is created from the outer
   * {@link Expr.ObjectBinding} and the values of the array {@link Expr} argument(s)
   */
  abstract class BaseMapFunction implements ApplyFunction
  {
    @Override
    public boolean hasArrayOutput(LambdaExpr lambdaExpr)
    {
      return true;
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, LambdaExpr expr, List<Expr> args)
    {
      return ExpressionType.asArrayType(expr.getOutputType(new LambdaInputBindingInspector(inspector, expr, args)));
    }

    /**
     * Evaluate {@link LambdaExpr} against every index position of an {@link IndexableMapLambdaObjectBinding}
     */
    ExprEval applyMap(@Nullable ExpressionType arrayType, LambdaExpr expr, IndexableMapLambdaObjectBinding bindings)
    {
      final int length = bindings.getLength();
      Object[] out = new Object[length];
      final boolean computeArrayType = arrayType == null;
      ExpressionType arrayElementType = arrayType != null
                                        ? (ExpressionType) arrayType.getElementType()
                                        : null;
      final ExprEval<?>[] outEval = computeArrayType ? new ExprEval[length] : null;
      for (int i = 0; i < length; i++) {
        final ExprEval<?> eval = expr.eval(bindings.withIndex(i));
        if (computeArrayType && outEval[i].value() != null) {
          arrayElementType = ExpressionTypeConversion.leastRestrictiveType(arrayElementType, eval.type());
          outEval[i] = eval;
        } else {
          out[i] = eval.castTo(arrayElementType).value();
        }
      }
      if (arrayElementType == null) {
        arrayElementType = ExpressionType.LONG;
      }
      if (computeArrayType) {
        arrayType = ExpressionTypeFactory.getInstance().ofArray(arrayElementType);
        for (int i = 0; i < length; i++) {
          out[i] = outEval[i].castTo(arrayElementType).value();
        }
      }
      return ExprEval.ofArray(arrayType, out);
    }
  }

  /**
   * Map the scalar values of a single array input {@link Expr} to a single argument {@link LambdaExpr}
   */
  class MapFunction extends BaseMapFunction
  {
    static final String NAME = "map";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings)
    {
      Expr arrayExpr = argsExpr.get(0);
      ExprEval arrayEval = arrayExpr.eval(bindings);

      Object[] array = arrayEval.asArray();
      if (array == null) {
        return ExprEval.of(null);
      }
      if (array.length == 0) {
        return arrayEval;
      }

      MapLambdaBinding lambdaBinding = new MapLambdaBinding(arrayEval.elementType(), array, lambdaExpr, bindings);
      ExpressionType lambdaType = lambdaExpr.getOutputType(lambdaBinding);
      return applyMap(lambdaType == null ? null : ExpressionTypeFactory.getInstance().ofArray(lambdaType), lambdaExpr, lambdaBinding);
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      if (args.size() == 1) {
        return ImmutableSet.of(args.get(0));
      }
      return Collections.emptySet();
    }

    @Override
    public void validateArguments(LambdaExpr lambdaExpr, List<Expr> args)
    {
      validationHelperCheckArgumentCount(lambdaExpr, args, 1);

    }
  }

  /**
   * Map the cartesian product of 'n' array input arguments to an 'n' argument {@link LambdaExpr}
   */
  class CartesianMapFunction extends BaseMapFunction
  {
    static final String NAME = "cartesian_map";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings)
    {
      List<List<Object>> arrayInputs = new ArrayList<>();
      boolean hadNull = false;
      boolean hadEmpty = false;
      ExpressionType elementType = null;
      for (Expr expr : argsExpr) {
        ExprEval arrayEval = expr.eval(bindings);
        Object[] array = arrayEval.asArray();
        if (array == null) {
          hadNull = true;
          continue;
        }
        elementType = arrayEval.elementType();
        if (array.length == 0) {
          hadEmpty = true;
          continue;
        }
        arrayInputs.add(Arrays.asList(array));
      }
      if (hadNull) {
        return ExprEval.of(null);
      }
      if (hadEmpty) {
        return ExprEval.ofStringArray(new String[0]);
      }

      List<List<Object>> product = CartesianList.create(arrayInputs);
      CartesianMapLambdaBinding lambdaBinding = new CartesianMapLambdaBinding(elementType, product, lambdaExpr, bindings);
      ExpressionType lambdaType = lambdaExpr.getOutputType(lambdaBinding);
      return applyMap(lambdaType == null ? null : ExpressionTypeFactory.getInstance().ofArray(lambdaType), lambdaExpr, lambdaBinding);
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }

    @Override
    public void validateArguments(LambdaExpr lambdaExpr, List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(lambdaExpr, args, 1);
    }
  }

  /**
   * Base class for family of {@link ApplyFunction} which aggregate a scalar or array value given one or more array
   * input {@link Expr} arguments and an array or scalar "accumulator" argument with an initial value
   */
  abstract class BaseFoldFunction implements ApplyFunction
  {
    /**
     * Accumulate a value by evaluating a {@link LambdaExpr} for each index position of an
     * {@link IndexableFoldLambdaBinding}
     */
    ExprEval applyFold(LambdaExpr lambdaExpr, Object accumulator, IndexableFoldLambdaBinding bindings)
    {
      for (int i = 0; i < bindings.getLength(); i++) {
        ExprEval evaluated = lambdaExpr.eval(bindings.accumulateWithIndex(i, accumulator));
        accumulator = evaluated.value();
      }
      if (accumulator instanceof Boolean) {
        return ExprEval.ofLongBoolean((boolean) accumulator);
      }
      return ExprEval.ofType(bindings.getAccumulatorType(), accumulator);
    }

    @Override
    public boolean hasArrayOutput(LambdaExpr lambdaExpr)
    {
      Expr.BindingAnalysis lambdaBindingAnalysis = lambdaExpr.analyzeInputs();
      return lambdaBindingAnalysis.isOutputArray();
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, LambdaExpr expr, List<Expr> args)
    {
      // output type is accumulator type, which is last argument
      return args.get(args.size() - 1).getOutputType(inspector);
    }
  }

  /**
   * Accumulate a value for a single array input with a 2 argument {@link LambdaExpr}. The 'array' input expression is
   * the first argument, the initial value for the accumulator expression is the 2nd argument.
   */
  class FoldFunction extends BaseFoldFunction
  {
    static final String NAME = "fold";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings)
    {
      Expr arrayExpr = argsExpr.get(0);
      Expr accExpr = argsExpr.get(1);

      ExprEval arrayEval = arrayExpr.eval(bindings);
      ExprEval accEval = accExpr.eval(bindings);

      Object[] array = arrayEval.asArray();
      if (array == null) {
        return ExprEval.of(null);
      }
      Object accumulator = accEval.value();

      FoldLambdaBinding lambdaBinding = new FoldLambdaBinding(
          arrayEval.elementType(),
          array,
          accEval.type(),
          accumulator,
          lambdaExpr,
          bindings
      );
      return applyFold(lambdaExpr, accumulator, lambdaBinding);
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      // accumulator argument cannot currently be inferred, so ignore it until we think of something better to do
      return ImmutableSet.of(args.get(0));
    }

    @Override
    public void validateArguments(LambdaExpr lambdaExpr, List<Expr> args)
    {
      validationHelperCheckArgumentCount(lambdaExpr, args, 2);
    }
  }

  /**
   * Accumulate a value for the cartesian product of 'n' array inputs arguments with an 'n + 1' argument
   * {@link LambdaExpr}. The 'array' input expressions are the first 'n' arguments, the initial value for the
   * accumulator expression is the final argument.
   */
  class CartesianFoldFunction extends BaseFoldFunction
  {
    static final String NAME = "cartesian_fold";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings)
    {
      List<List<Object>> arrayInputs = new ArrayList<>();
      boolean hadNull = false;
      boolean hadEmpty = false;
      ExpressionType arrayElementType = null;
      for (int i = 0; i < argsExpr.size() - 1; i++) {
        Expr expr = argsExpr.get(i);
        ExprEval arrayEval = expr.eval(bindings);
        Object[] array = arrayEval.asArray();
        if (array == null) {
          hadNull = true;
          continue;
        }
        arrayElementType = arrayEval.elementType();
        if (array.length == 0) {
          hadEmpty = true;
          continue;
        }
        arrayInputs.add(Arrays.asList(array));
      }
      if (hadNull) {
        return ExprEval.of(null);
      }
      if (hadEmpty) {
        return ExprEval.ofStringArray(new Object[0]);
      }
      Expr accExpr = argsExpr.get(argsExpr.size() - 1);

      List<List<Object>> product = CartesianList.create(arrayInputs);

      ExprEval accEval = accExpr.eval(bindings);

      Object accumulator = accEval.value();

      CartesianFoldLambdaBinding lambdaBindings =
          new CartesianFoldLambdaBinding(arrayElementType, product, accEval.type(), accumulator, lambdaExpr, bindings);
      return applyFold(lambdaExpr, accumulator, lambdaBindings);
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      // accumulator argument cannot be inferred, so ignore it until we think of something better to do
      return ImmutableSet.copyOf(args.subList(0, args.size() - 1));
    }

    @Override
    public void validateArguments(LambdaExpr lambdaExpr, List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(lambdaExpr, args, 1);
    }
  }

  /**
   * Filter an array to all elements that evaluate to a 'truthy' value for a {@link LambdaExpr}
   */
  class FilterFunction implements ApplyFunction
  {
    static final String NAME = "filter";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public boolean hasArrayOutput(LambdaExpr lambdaExpr)
    {
      return true;
    }

    @Override
    public ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings)
    {
      Expr arrayExpr = argsExpr.get(0);
      ExprEval arrayEval = arrayExpr.eval(bindings);

      Object[] array = arrayEval.asArray();
      if (array == null) {
        return ExprEval.ofArray(arrayEval.asArrayType(), null);
      }

      SettableLambdaBinding lambdaBinding = new SettableLambdaBinding(arrayEval.elementType(), lambdaExpr, bindings);
      Object[] filtered = filter(arrayEval.asArray(), lambdaExpr, lambdaBinding).toArray();
      // return null array expr if nothing is left in filtered
      if (filtered.length == 0) {
        return ExprEval.ofArray(arrayEval.asArrayType(), null);
      }
      return ExprEval.ofArray(arrayEval.asArrayType(), filtered);
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.of(args.get(0));
    }

    @Override
    public void validateArguments(LambdaExpr lambdaExpr, List<Expr> args)
    {
      validationHelperCheckArgumentCount(lambdaExpr, args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, LambdaExpr expr, List<Expr> args)
    {
      // output type is input array type
      return args.get(0).getOutputType(inspector);
    }

    private <T> Stream<T> filter(T[] array, LambdaExpr expr, SettableLambdaBinding binding)
    {
      return Arrays.stream(array).filter(s -> expr.eval(binding.withBinding(expr.getIdentifier(), s)).asBoolean());
    }
  }

  /**
   * Base class for family of {@link ApplyFunction} which evaluate elements elements of a single array input against
   * a {@link LambdaExpr} to evaluate to a final 'truthy' value
   */
  abstract class MatchFunction implements ApplyFunction
  {
    @Override
    public ExprEval apply(LambdaExpr lambdaExpr, List<Expr> argsExpr, Expr.ObjectBinding bindings)
    {
      Expr arrayExpr = argsExpr.get(0);
      ExprEval arrayEval = arrayExpr.eval(bindings);

      final Object[] array = arrayEval.asArray();
      if (array == null) {
        return ExprEval.ofLongBoolean(false);
      }

      SettableLambdaBinding lambdaBinding = new SettableLambdaBinding(arrayEval.elementType(), lambdaExpr, bindings);
      return match(array, lambdaExpr, lambdaBinding);
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.of(args.get(0));
    }

    @Override
    public void validateArguments(LambdaExpr lambdaExpr, List<Expr> args)
    {
      validationHelperCheckArgumentCount(lambdaExpr, args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, LambdaExpr expr, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    public abstract ExprEval match(Object[] values, LambdaExpr expr, SettableLambdaBinding bindings);
  }

  /**
   * Evaluates to true if any element of the array input {@link Expr} causes the {@link LambdaExpr} to evaluate to a
   * 'truthy' value
   */
  class AnyMatchFunction extends MatchFunction
  {
    static final String NAME = "any";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval match(Object[] values, LambdaExpr expr, SettableLambdaBinding bindings)
    {
      for (Object o : values) {
        if (expr.eval(bindings.withBinding(expr.getIdentifier(), o)).asBoolean()) {
          return ExprEval.ofLongBoolean(true);
        }
      }
      return ExprEval.ofLongBoolean(false);
    }
  }

  /**
   * Evaluates to true if all element of the array input {@link Expr} causes the {@link LambdaExpr} to evaluate to a
   * 'truthy' value
   */
  class AllMatchFunction extends MatchFunction
  {
    static final String NAME = "all";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval match(Object[] values, LambdaExpr expr, SettableLambdaBinding bindings)
    {
      for (Object o : values) {
        if (!expr.eval(bindings.withBinding(expr.getIdentifier(), o)).asBoolean()) {
          return ExprEval.ofLongBoolean(false);
        }
      }
      return ExprEval.ofLongBoolean(true);
    }
  }

  /**
   * Simple, mutable, {@link Expr.ObjectBinding} for a {@link LambdaExpr} which provides a {@link Map} for storing
   * arbitrary values to use as values for {@link IdentifierExpr} in the body of the lambda that are arguments to the
   * lambda
   */
  class SettableLambdaBinding implements Expr.ObjectBinding
  {
    private final Expr.ObjectBinding bindings;
    private final Map<String, Object> lambdaBindings;
    private final ExpressionType elementType;

    SettableLambdaBinding(ExpressionType elementType, LambdaExpr expr, Expr.ObjectBinding bindings)
    {
      this.elementType = elementType;
      this.lambdaBindings = new HashMap<>();
      for (String lambdaIdentifier : expr.getIdentifiers()) {
        lambdaBindings.put(lambdaIdentifier, null);
      }
      this.bindings = bindings != null ? bindings : InputBindings.nilBindings();
    }

    @Nullable
    @Override
    public Object get(String name)
    {
      if (lambdaBindings.containsKey(name)) {
        return lambdaBindings.get(name);
      }
      return bindings.get(name);
    }

    SettableLambdaBinding withBinding(String key, Object value)
    {
      this.lambdaBindings.put(key, value);
      return this;
    }

    @Nullable
    @Override
    public ExpressionType getType(String name)
    {
      if (lambdaBindings.containsKey(name)) {
        return elementType;
      }
      return bindings.getType(name);
    }
  }

  /**
   * {@link Expr.ObjectBinding} which can be iterated by an integer index position for {@link BaseMapFunction}.
   * Evaluating an {@link IdentifierExpr} against these bindings will return the value(s) of the array at the current
   * index for any lambda identifiers, and fall through to the base {@link Expr.ObjectBinding} for all bindings provided
   * by an outer scope.
   */
  interface IndexableMapLambdaObjectBinding extends Expr.ObjectBinding
  {
    /**
     * Total number of bindings in this binding
     */
    int getLength();

    /**
     * Update index position
     */
    IndexableMapLambdaObjectBinding withIndex(int index);
  }

  /**
   * {@link IndexableMapLambdaObjectBinding} for a {@link MapFunction}. Lambda argument binding is stored in an object
   * array, retrieving binding values for the lambda identifier returns the value at the current index.
   */
  class MapLambdaBinding implements IndexableMapLambdaObjectBinding
  {
    private final Expr.ObjectBinding bindings;
    private final ExpressionType arrayElementType;
    @Nullable
    private final String lambdaIdentifier;
    private final Object[] arrayValues;
    private int index = 0;
    private final boolean scoped;

    MapLambdaBinding(ExpressionType elementType, Object[] arrayValues, LambdaExpr expr, Expr.ObjectBinding bindings)
    {
      this.lambdaIdentifier = expr.getIdentifier();
      this.arrayElementType = elementType;
      this.arrayValues = arrayValues;
      this.bindings = bindings != null ? bindings : InputBindings.nilBindings();
      this.scoped = lambdaIdentifier != null;
    }

    @Nullable
    @Override
    public Object get(String name)
    {
      if (scoped && name.equals(lambdaIdentifier)) {
        return arrayValues[index];
      }
      return bindings.get(name);
    }

    @Override
    public int getLength()
    {
      return arrayValues.length;
    }

    @Override
    public MapLambdaBinding withIndex(int index)
    {
      this.index = index;
      return this;
    }

    @Nullable
    @Override
    public ExpressionType getType(String name)
    {
      if (scoped && name.equals(lambdaIdentifier)) {
        return arrayElementType;
      }
      return bindings.getType(name);
    }
  }

  /**
   * {@link IndexableMapLambdaObjectBinding} for a {@link CartesianMapFunction}. Lambda argument bindings stored as a
   * cartesian product in the form of a list of lists of objects, where the inner list is the in order list of values
   * for each {@link LambdaExpr} argument
   */
  class CartesianMapLambdaBinding implements IndexableMapLambdaObjectBinding
  {
    private final Expr.ObjectBinding bindings;
    private final ExpressionType arrayElementType;
    private final Object2IntMap<String> lambdaIdentifiers;
    private final List<List<Object>> lambdaInputs;
    private final boolean scoped;
    private int index = 0;

    CartesianMapLambdaBinding(ExpressionType arrayElementType, List<List<Object>> inputs, LambdaExpr expr, Expr.ObjectBinding bindings)
    {
      this.lambdaInputs = inputs;
      this.arrayElementType = arrayElementType;
      List<String> ids = expr.getIdentifiers();
      this.scoped = ids.size() > 0;
      this.lambdaIdentifiers = new Object2IntArrayMap<>(ids.size());
      for (int i = 0; i < ids.size(); i++) {
        lambdaIdentifiers.put(ids.get(i), i);
      }

      this.bindings = bindings != null ? bindings : InputBindings.nilBindings();
    }

    @Nullable
    @Override
    public Object get(String name)
    {
      if (scoped && lambdaIdentifiers.containsKey(name)) {
        return lambdaInputs.get(index).get(lambdaIdentifiers.getInt(name));
      }
      return bindings.get(name);
    }

    @Override
    public int getLength()
    {
      return lambdaInputs.size();
    }

    @Override
    public CartesianMapLambdaBinding withIndex(int index)
    {
      this.index = index;
      return this;
    }

    @Nullable
    @Override
    public ExpressionType getType(String name)
    {
      if (scoped && lambdaIdentifiers.containsKey(name)) {
        return arrayElementType;
      }
      return bindings.getType(name);
    }
  }

  /**
   * {@link Expr.ObjectBinding} which can be iterated by an integer index position for {@link BaseFoldFunction}.
   * Evaluating an {@link IdentifierExpr} against these bindings will return the value(s) of the array at the current
   * index for any lambda array identifiers, the value of the 'accumulator' for the lambda accumulator identifier,
   * and fall through to the base {@link Expr.ObjectBinding} for all bindings provided by an outer scope.
   */
  interface IndexableFoldLambdaBinding extends Expr.ObjectBinding
  {
    ExpressionType getAccumulatorType();

    /**
     * Total number of bindings in this binding
     */
    int getLength();

    /**
     * Update the index and accumulator value
     */
    IndexableFoldLambdaBinding accumulateWithIndex(int index, Object accumulator);
  }

  /**
   * {@link IndexableFoldLambdaBinding} for a {@link FoldFunction}. Like {@link MapLambdaBinding}
   * but with additional information to track and provide binding values for an accumulator.
   */
  class FoldLambdaBinding implements IndexableFoldLambdaBinding
  {
    private final Expr.ObjectBinding bindings;
    private final ExpressionType arrayElementType;
    private final ExpressionType accumulatorType;
    private final String elementIdentifier;
    private final Object[] arrayValues;
    private final String accumulatorIdentifier;
    private Object accumulatorValue;
    private int index;

    FoldLambdaBinding(
        ExpressionType arrayElementType,
        Object[] arrayValues,
        ExpressionType accumulatorType,
        Object initialAccumulator,
        LambdaExpr expr,
        Expr.ObjectBinding bindings
    )
    {
      List<String> ids = expr.getIdentifiers();
      this.elementIdentifier = ids.get(0);
      this.arrayElementType = arrayElementType;
      this.accumulatorType = accumulatorType;
      this.accumulatorIdentifier = ids.get(1);
      this.arrayValues = arrayValues;
      this.accumulatorValue = initialAccumulator;
      this.bindings = bindings != null ? bindings : InputBindings.nilBindings();
    }

    @Nullable
    @Override
    public Object get(String name)
    {
      if (name.equals(elementIdentifier)) {
        return arrayValues[index];
      } else if (name.equals(accumulatorIdentifier)) {
        return accumulatorValue;
      }
      return bindings.get(name);
    }

    @Override
    public ExpressionType getAccumulatorType()
    {
      return accumulatorType;
    }

    @Override
    public int getLength()
    {
      return arrayValues.length;
    }

    @Override
    public FoldLambdaBinding accumulateWithIndex(int index, Object acc)
    {
      this.index = index;
      this.accumulatorValue = acc;
      return this;
    }

    @Nullable
    @Override
    public ExpressionType getType(String name)
    {
      if (name.equals(elementIdentifier)) {
        return arrayElementType;
      } else if (name.equals(accumulatorIdentifier)) {
        return accumulatorType;
      }
      return bindings.getType(name);
    }
  }

  /**
   * {@link IndexableFoldLambdaBinding} for a {@link CartesianFoldFunction}. Like {@link CartesianMapLambdaBinding}
   * but with additional information to track and provide binding values for an accumulator.
   */
  class CartesianFoldLambdaBinding implements IndexableFoldLambdaBinding
  {
    private final Expr.ObjectBinding bindings;
    private final ExpressionType arrayElementType;
    private final ExpressionType accumulatorType;
    private final Object2IntMap<String> lambdaIdentifiers;
    private final List<List<Object>> lambdaInputs;
    private final String accumulatorIdentifier;
    private Object accumulatorValue;
    private int index = 0;

    CartesianFoldLambdaBinding(
        @Nullable ExpressionType arrayElementType,
        List<List<Object>> inputs,
        ExpressionType accumulatorType,
        Object accumulatorValue,
        LambdaExpr expr,
        Expr.ObjectBinding bindings
    )
    {
      this.arrayElementType = arrayElementType;
      this.accumulatorType = accumulatorType;
      this.lambdaInputs = inputs;
      List<String> ids = expr.getIdentifiers();
      this.lambdaIdentifiers = new Object2IntArrayMap<>(ids.size());
      for (int i = 0; i < ids.size() - 1; i++) {
        lambdaIdentifiers.put(ids.get(i), i);
      }
      this.accumulatorIdentifier = ids.get(ids.size() - 1);
      this.bindings = bindings != null ? bindings : InputBindings.nilBindings();
      this.accumulatorValue = accumulatorValue;
    }

    @Nullable
    @Override
    public Object get(String name)
    {
      if (lambdaIdentifiers.containsKey(name)) {
        return lambdaInputs.get(index).get(lambdaIdentifiers.getInt(name));
      } else if (accumulatorIdentifier.equals(name)) {
        return accumulatorValue;
      }
      return bindings.get(name);
    }

    @Override
    public ExpressionType getAccumulatorType()
    {
      return accumulatorType;
    }

    @Override
    public int getLength()
    {
      return lambdaInputs.size();
    }

    @Override
    public CartesianFoldLambdaBinding accumulateWithIndex(int index, Object acc)
    {
      this.index = index;
      this.accumulatorValue = acc;
      return this;
    }

    @Nullable
    @Override
    public ExpressionType getType(String name)
    {
      if (lambdaIdentifiers.containsKey(name)) {
        return arrayElementType;
      } else if (accumulatorIdentifier.equals(name)) {
        return accumulatorType;
      }
      return bindings.getType(name);
    }
  }

  /**
   * Helper that can wrap another {@link Expr.InputBindingInspector} to use to supply the type information of a
   * {@link LambdaExpr} when evaluating {@link ApplyFunctionExpr#getOutputType}. Lambda identifiers do not exist
   * in the underlying {@link Expr.InputBindingInspector}, but can be created by mapping the lambda identifiers to the
   * arguments that will be applied to them, to map the type information.
   */
  class LambdaInputBindingInspector implements Expr.InputBindingInspector
  {
    private final Object2IntMap<String> lambdaIdentifiers;
    private final Expr.InputBindingInspector inspector;
    private final List<Expr> args;

    public LambdaInputBindingInspector(Expr.InputBindingInspector inspector, LambdaExpr expr, List<Expr> args)
    {
      this.inspector = inspector;
      this.args = args;
      List<String> identifiers = expr.getIdentifiers();
      this.lambdaIdentifiers = new Object2IntOpenHashMap<>(args.size());
      for (int i = 0; i < args.size(); i++) {
        lambdaIdentifiers.put(identifiers.get(i), i);
      }
    }

    @Nullable
    @Override
    public ExpressionType getType(String name)
    {
      if (lambdaIdentifiers.containsKey(name)) {
        return ExpressionType.elementType(args.get(lambdaIdentifiers.getInt(name)).getOutputType(inspector));
      }
      return inspector.getType(name);
    }
  }
}

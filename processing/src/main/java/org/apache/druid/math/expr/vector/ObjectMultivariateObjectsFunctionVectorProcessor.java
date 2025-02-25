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

package org.apache.druid.math.expr.vector;

import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExpressionType;

/**
 * many objects enter, one object leaves...
 */
public abstract class ObjectMultivariateObjectsFunctionVectorProcessor implements ExprVectorProcessor<Object[]>
{
  final ExprVectorProcessor<Object[]>[] inputs;
  final Object[] outValues;

  final ExpressionType expressionType;

  protected ObjectMultivariateObjectsFunctionVectorProcessor(
      ExprVectorProcessor<Object[]>[] inputs,
      ExpressionType objectType
  )
  {
    this.inputs = inputs;
    this.outValues = new Object[inputs[0].maxVectorSize()];
    this.expressionType = objectType;
  }

  @Override
  public ExpressionType getOutputType()
  {
    return expressionType;
  }

  @Override
  public ExprEvalVector<Object[]> evalVector(Expr.VectorInputBinding bindings)
  {
    final int currentSize = bindings.getCurrentVectorSize();
    final Object[][] in = new Object[inputs.length][];
    for (int i = 0; i < inputs.length; i++) {
      in[i] = inputs[i].evalVector(bindings).values();
    }

    for (int i = 0; i < currentSize; i++) {
      processIndex(in, i);
    }
    return new ExprEvalObjectVector(outValues, expressionType);
  }

  abstract void processIndex(Object[][] in, int i);

  @Override
  public int maxVectorSize()
  {
    return inputs[0].maxVectorSize();
  }
}

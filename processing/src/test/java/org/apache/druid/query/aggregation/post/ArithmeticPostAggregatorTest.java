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

package org.apache.druid.query.aggregation.post;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.Druids;
import org.apache.druid.query.aggregation.CountAggregator;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.expression.TestExprMacroTable;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesQueryQueryToolChest;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.Assert;
import org.junit.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArithmeticPostAggregatorTest extends InitializedNullHandlingTest
{
  @Test
  public void testCompute()
  {
    final String aggName = "rows";
    ArithmeticPostAggregator arithmeticPostAggregator;
    ExpressionPostAggregator expressionPostAggregator;
    CountAggregator agg = new CountAggregator();
    agg.aggregate();
    agg.aggregate();
    agg.aggregate();
    Map<String, Object> metricValues = new HashMap<>();
    metricValues.put(aggName, agg.get());

    List<PostAggregator> postAggregatorList =
        Lists.newArrayList(
            new ConstantPostAggregator(
                "roku",
                6D
            ),
            new FieldAccessPostAggregator(
                "rows",
                "rows"
            )
        );

    for (PostAggregator postAggregator : postAggregatorList) {
      metricValues.put(postAggregator.getName(), postAggregator.compute(metricValues));
    }

    arithmeticPostAggregator = new ArithmeticPostAggregator("add", "+", postAggregatorList);
    expressionPostAggregator = new ExpressionPostAggregator("add", "roku + rows", null, null, TestExprMacroTable.INSTANCE);
    Assert.assertEquals(9.0, arithmeticPostAggregator.compute(metricValues));
    Assert.assertEquals(9.0, expressionPostAggregator.compute(metricValues));

    arithmeticPostAggregator = new ArithmeticPostAggregator("subtract", "-", postAggregatorList);
    expressionPostAggregator = new ExpressionPostAggregator("add", "roku - rows", null, null, TestExprMacroTable.INSTANCE);
    Assert.assertEquals(3.0, arithmeticPostAggregator.compute(metricValues));
    Assert.assertEquals(3.0, expressionPostAggregator.compute(metricValues));

    arithmeticPostAggregator = new ArithmeticPostAggregator("multiply", "*", postAggregatorList);
    expressionPostAggregator = new ExpressionPostAggregator("add", "roku * rows", null, null, TestExprMacroTable.INSTANCE);
    Assert.assertEquals(18.0, arithmeticPostAggregator.compute(metricValues));
    Assert.assertEquals(18.0, expressionPostAggregator.compute(metricValues));

    arithmeticPostAggregator = new ArithmeticPostAggregator("divide", "/", postAggregatorList);
    expressionPostAggregator = new ExpressionPostAggregator("add", "roku / rows", null, null, TestExprMacroTable.INSTANCE);
    Assert.assertEquals(2.0, arithmeticPostAggregator.compute(metricValues));
    Assert.assertEquals(2.0, expressionPostAggregator.compute(metricValues));
  }

  @Test
  public void testComparator()
  {
    final String aggName = "rows";
    ArithmeticPostAggregator arithmeticPostAggregator;
    CountAggregator agg = new CountAggregator();
    Map<String, Object> metricValues = new HashMap<>();
    metricValues.put(aggName, agg.get());

    List<PostAggregator> postAggregatorList =
        Lists.newArrayList(
            new ConstantPostAggregator(
                "roku",
                6D
            ),
            new FieldAccessPostAggregator(
                "rows",
                "rows"
            )
        );

    arithmeticPostAggregator = new ArithmeticPostAggregator("add", "+", postAggregatorList);
    Comparator comp = arithmeticPostAggregator.getComparator();
    Object before = arithmeticPostAggregator.compute(metricValues);
    agg.aggregate();
    agg.aggregate();
    agg.aggregate();
    metricValues.put(aggName, agg.get());
    Object after = arithmeticPostAggregator.compute(metricValues);

    Assert.assertEquals(-1, comp.compare(before, after));
    Assert.assertEquals(0, comp.compare(before, before));
    Assert.assertEquals(0, comp.compare(after, after));
    Assert.assertEquals(1, comp.compare(after, before));
  }

  @Test
  public void testComparatorNulls()
  {
    final String aggName = "doubleWithNulls";
    ArithmeticPostAggregator arithmeticPostAggregator;
    Map<String, Object> metricValues = new HashMap<>();

    List<PostAggregator> postAggregatorList =
        Lists.newArrayList(
            new ConstantPostAggregator(
                "roku",
                6D
            ),
            new FieldAccessPostAggregator(
                aggName,
                aggName
            )
        );

    arithmeticPostAggregator = new ArithmeticPostAggregator("add", "+", postAggregatorList);
    Comparator comp = arithmeticPostAggregator.getComparator();
    metricValues.put(aggName, null);
    Object before = arithmeticPostAggregator.compute(metricValues);

    metricValues.put(aggName, 1.0);
    Object after = arithmeticPostAggregator.compute(metricValues);

    Assert.assertEquals(-1, comp.compare(before, after));
    Assert.assertEquals(0, comp.compare(before, before));
    Assert.assertEquals(0, comp.compare(after, after));
    Assert.assertEquals(1, comp.compare(after, before));
  }

  @Test
  public void testQuotient()
  {
    ArithmeticPostAggregator agg = new ArithmeticPostAggregator(
        null,
        "quotient",
        ImmutableList.of(
            new FieldAccessPostAggregator("numerator", "value"),
            new ConstantPostAggregator("zero", 0)
        ),
        "numericFirst"
    );


    Assert.assertEquals(Double.NaN, agg.compute(ImmutableMap.of("value", 0)));
    Assert.assertEquals(Double.NaN, agg.compute(ImmutableMap.of("value", Double.NaN)));
    Assert.assertEquals(Double.POSITIVE_INFINITY, agg.compute(ImmutableMap.of("value", 1)));
    Assert.assertEquals(Double.NEGATIVE_INFINITY, agg.compute(ImmutableMap.of("value", -1)));
  }
  @Test
  public void testPow()
  {
    ArithmeticPostAggregator agg = new ArithmeticPostAggregator(
            null,
            "pow",
            ImmutableList.of(
                    new ConstantPostAggregator("value", 4),
                    new ConstantPostAggregator("power", .5)
            ),
            "numericFirst"
    );
    Assert.assertEquals(2.0, agg.compute(ImmutableMap.of("value", 0)));

    agg = new ArithmeticPostAggregator(
            null,
            "pow",
            ImmutableList.of(
                    new FieldAccessPostAggregator("base", "value"),
                    new ConstantPostAggregator("zero", 0)
            ),
            "numericFirst"
    );

    Assert.assertEquals(1.0, agg.compute(ImmutableMap.of("value", 0)));
    Assert.assertEquals(1.0, agg.compute(ImmutableMap.of("value", Double.NaN)));
    Assert.assertEquals(1.0, agg.compute(ImmutableMap.of("value", 1)));
    Assert.assertEquals(1.0, agg.compute(ImmutableMap.of("value", -1)));
    Assert.assertEquals(1.0, agg.compute(ImmutableMap.of("value", .5)));
  }
  @Test
  public void testDiv()
  {
    ArithmeticPostAggregator agg = new ArithmeticPostAggregator(
        null,
        "/",
        ImmutableList.of(
            new FieldAccessPostAggregator("numerator", "value"),
            new ConstantPostAggregator("denomiator", 0)
        )
    );

    Assert.assertEquals(0.0, agg.compute(ImmutableMap.of("value", 0)));
    Assert.assertEquals(0.0, agg.compute(ImmutableMap.of("value", Double.NaN)));
    Assert.assertEquals(0.0, agg.compute(ImmutableMap.of("value", 1)));
    Assert.assertEquals(0.0, agg.compute(ImmutableMap.of("value", -1)));
  }

  @Test
  public void testNumericFirstOrdering()
  {
    ArithmeticPostAggregator agg = new ArithmeticPostAggregator(
        null,
        "quotient",
        ImmutableList.of(
            new ConstantPostAggregator("zero", 0),
            new ConstantPostAggregator("zero", 0)
        ),
        "numericFirst"
    );
    final Comparator numericFirst = agg.getComparator();
    Assert.assertTrue(numericFirst.compare(Double.NaN, 0.0) < 0);
    Assert.assertTrue(numericFirst.compare(Double.POSITIVE_INFINITY, 0.0) < 0);
    Assert.assertTrue(numericFirst.compare(Double.NEGATIVE_INFINITY, 0.0) < 0);
    Assert.assertTrue(numericFirst.compare(0.0, Double.NaN) > 0);
    Assert.assertTrue(numericFirst.compare(0.0, Double.POSITIVE_INFINITY) > 0);
    Assert.assertTrue(numericFirst.compare(0.0, Double.NEGATIVE_INFINITY) > 0);

    Assert.assertTrue(numericFirst.compare(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) < 0);
    Assert.assertTrue(numericFirst.compare(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY) > 0);
    Assert.assertTrue(numericFirst.compare(Double.NaN, Double.POSITIVE_INFINITY) > 0);
    Assert.assertTrue(numericFirst.compare(Double.NaN, Double.NEGATIVE_INFINITY) > 0);
    Assert.assertTrue(numericFirst.compare(Double.POSITIVE_INFINITY, Double.NaN) < 0);
    Assert.assertTrue(numericFirst.compare(Double.NEGATIVE_INFINITY, Double.NaN) < 0);
  }

  @Test
  public void testResultArraySignature()
  {
    final TimeseriesQuery query =
        Druids.newTimeseriesQueryBuilder()
              .dataSource("dummy")
              .intervals("2000/3000")
              .granularity(Granularities.HOUR)
              .aggregators(
                  new LongSumAggregatorFactory("sum", "col"),
                  new CountAggregatorFactory("count")
              )
              .postAggregators(
                  new ArithmeticPostAggregator(
                      "avg",
                      "/",
                      ImmutableList.of(
                          new FieldAccessPostAggregator("_count", "count"),
                          new FieldAccessPostAggregator("_sum", "sum")
                      )
                  )
              )
              .build();

    Assert.assertEquals(
        RowSignature.builder()
                    .addTimeColumn()
                    .add("sum", ColumnType.LONG)
                    .add("count", ColumnType.LONG)
                    .add("avg", ColumnType.DOUBLE)
                    .build(),
        new TimeseriesQueryQueryToolChest().resultArraySignature(query)
    );
  }
}

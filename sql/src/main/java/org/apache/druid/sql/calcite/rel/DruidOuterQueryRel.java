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

package org.apache.druid.sql.calcite.rel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.Druids;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.table.RowSignatures;

import java.util.List;
import java.util.Set;

/**
 * DruidRel that uses a {@link QueryDataSource}.
 */
public class DruidOuterQueryRel extends DruidRel<DruidOuterQueryRel>
{
  static final TableDataSource DUMMY_DATA_SOURCE = new TableDataSource("__subquery__")
  {
    @Override
    public boolean isProcessable()
    {
      return false;
    }
  };

  private static final QueryDataSource DUMMY_QUERY_DATA_SOURCE = new QueryDataSource(
      Druids.newScanQueryBuilder().dataSource("__subquery__").eternityInterval().build()
  );

  private final PartialDruidQuery partialQuery;
  private RelNode sourceRel;

  private DruidOuterQueryRel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode sourceRel,
      PartialDruidQuery partialQuery,
      PlannerContext plannerContext
  )
  {
    super(cluster, traitSet, plannerContext);
    this.sourceRel = sourceRel;
    this.partialQuery = partialQuery;
  }

  public static DruidOuterQueryRel create(
      final DruidRel sourceRel,
      final PartialDruidQuery partialQuery
  )
  {
    return new DruidOuterQueryRel(
        sourceRel.getCluster(),
        partialQuery.getTraitSet(sourceRel.getConvention(), sourceRel.getPlannerContext()),
        sourceRel,
        partialQuery,
        sourceRel.getPlannerContext()
    );
  }

  @Override
  public PartialDruidQuery getPartialDruidQuery()
  {
    return partialQuery;
  }

  @Override
  public DruidOuterQueryRel withPartialQuery(final PartialDruidQuery newQueryBuilder)
  {
    return new DruidOuterQueryRel(
        getCluster(),
        newQueryBuilder.getTraitSet(getConvention(), getPlannerContext()),
        sourceRel,
        newQueryBuilder,
        getPlannerContext()
    );
  }

  @Override
  public DruidQuery toDruidQuery(final boolean finalizeAggregations)
  {
    // Must finalize aggregations on subqueries.
    final DruidQuery subQuery = ((DruidRel) sourceRel).toDruidQuery(true);
    final RowSignature sourceRowSignature = subQuery.getOutputRowSignature();
    return partialQuery.build(
        new QueryDataSource(subQuery.getQuery()),
        sourceRowSignature,
        getPlannerContext(),
        getCluster().getRexBuilder(),
        finalizeAggregations,
        true
    );
  }

  @Override
  public DruidQuery toDruidQueryForExplaining()
  {
    return partialQuery.build(
        partialQuery.getWindow() == null ? DUMMY_DATA_SOURCE : DUMMY_QUERY_DATA_SOURCE,
        RowSignatures.fromRelDataType(
            sourceRel.getRowType().getFieldNames(),
            sourceRel.getRowType()
        ),
        getPlannerContext(),
        getCluster().getRexBuilder(),
        false,
        false
    );
  }

  @Override
  public DruidOuterQueryRel asDruidConvention()
  {
    return new DruidOuterQueryRel(
        getCluster(),
        getTraitSet().plus(DruidConvention.instance()),
        RelOptRule.convert(sourceRel, DruidConvention.instance()),
        partialQuery,
        getPlannerContext()
    );
  }

  @Override
  public List<RelNode> getInputs()
  {
    return ImmutableList.of(sourceRel);
  }

  @Override
  public void replaceInput(int ordinalInParent, RelNode p)
  {
    if (ordinalInParent != 0) {
      throw new IndexOutOfBoundsException(StringUtils.format("Invalid ordinalInParent[%s]", ordinalInParent));
    }
    this.sourceRel = p;
  }

  @Override
  public RelNode copy(final RelTraitSet traitSet, final List<RelNode> inputs)
  {
    return new DruidOuterQueryRel(
        getCluster(),
        traitSet,
        Iterables.getOnlyElement(inputs),
        getPartialDruidQuery(),
        getPlannerContext()
    );
  }

  @Override
  public Set<String> getDataSourceNames()
  {
    return ((DruidRel<?>) sourceRel).getDataSourceNames();
  }

  @Override
  public RelWriter explainTerms(RelWriter pw)
  {
    final String queryString;
    final DruidQuery druidQuery = toDruidQueryForExplaining();

    try {
      queryString = getPlannerContext().getJsonMapper().writeValueAsString(druidQuery.getQuery());
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    return pw.input("innerQuery", sourceRel)
             .item("query", queryString)
             .item("signature", druidQuery.getOutputRowSignature());
  }

  @Override
  protected RelDataType deriveRowType()
  {
    return partialQuery.getRowType();
  }

  @Override
  public RelOptCost computeSelfCost(final RelOptPlanner planner, final RelMetadataQuery mq)
  {
    return planner.getCostFactory()
                  .makeCost(partialQuery.estimateCost(), 0, 0)
                  .multiplyBy(CostEstimates.MULTIPLIER_OUTER_QUERY)
                  .plus(planner.getCostFactory().makeCost(CostEstimates.COST_SUBQUERY, 0, 0));
  }
}

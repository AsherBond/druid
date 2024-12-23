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

package org.apache.druid.guice;

import com.google.inject.Binder;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import org.apache.druid.java.util.metrics.Monitor;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryLogic;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.segment.SegmentWrangler;
import org.apache.druid.segment.join.JoinableFactory;
import org.apache.druid.server.DruidNode;

public class DruidBinders
{
  public static MapBinder<Class<? extends Query>, QueryRunnerFactory> queryRunnerFactoryBinder(Binder binder)
  {
    return MapBinder.newMapBinder(
        binder,
        new TypeLiteral<>() {},
        TypeLiteral.get(QueryRunnerFactory.class)
    );
  }

  public static MapBinder<Class<? extends Query>, QueryToolChest> queryToolChestBinder(Binder binder)
  {
    return MapBinder.newMapBinder(
        binder,
        new TypeLiteral<>() {},
        new TypeLiteral<>() {}
    );
  }

  public static MapBinder<Class<? extends Query>, QueryLogic> queryLogicBinderType(Binder binder)
  {
    return MapBinder.newMapBinder(
        binder,
        new TypeLiteral<>() {},
        new TypeLiteral<>() {}
    );
  }

  public static QueryLogicBinder queryLogicBinder(Binder binder)
  {
    return new QueryLogicBinder(binder);
  }

  public static class QueryLogicBinder
  {
    private MapBinder<Class<? extends Query>, QueryLogic> queryLogicMapBinder;
    private Binder binder;

    public QueryLogicBinder(Binder binder)
    {
      this.binder = binder;
      queryLogicMapBinder = DruidBinders.queryLogicBinderType(binder);
    }

    QueryLogicBinder bindQueryLogic(
        Class<? extends Query> queryTypeClazz,
        Class<? extends QueryLogic> queryLogicClazz)
    {
      queryLogicMapBinder.addBinding(queryTypeClazz).to(queryLogicClazz);
      binder.bind(queryLogicClazz).in(LazySingleton.class);
      return this;
    }
  }


  public static Multibinder<KeyHolder<DruidNode>> discoveryAnnouncementBinder(Binder binder)
  {
    return Multibinder.newSetBinder(binder, new TypeLiteral<>() {});
  }

  public static Multibinder<Class<? extends Monitor>> metricMonitorBinder(Binder binder)
  {
    return Multibinder.newSetBinder(binder, new TypeLiteral<>() {});
  }

  public static MapBinder<Class<? extends DataSource>, SegmentWrangler> segmentWranglerBinder(Binder binder)
  {
    return MapBinder.newMapBinder(
        binder,
        new TypeLiteral<>() {},
        new TypeLiteral<>() {}
    );
  }

  public static Multibinder<JoinableFactory> joinableFactoryMultiBinder(Binder binder)
  {
    return Multibinder.newSetBinder(
        binder,
        new TypeLiteral<>() {}
    );
  }

  public static MapBinder<Class<? extends JoinableFactory>, Class<? extends DataSource>> joinableMappingBinder(Binder binder)
  {
    return MapBinder.newMapBinder(
        binder,
        new TypeLiteral<>() {},
        new TypeLiteral<>() {}
    );
  }
}

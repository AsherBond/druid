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

package org.apache.druid.testing.embedded;

import com.google.inject.Inject;
import org.apache.druid.client.broker.BrokerClient;
import org.apache.druid.client.coordinator.CoordinatorClient;
import org.apache.druid.guice.annotations.Self;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.rpc.indexing.OverlordClient;
import org.apache.druid.server.DruidNode;
import org.apache.druid.server.metrics.LatchableEmitter;

import java.util.Objects;

/**
 * Holds references to various objects used by an {@link EmbeddedDruidServer} in
 * embedded cluster tests. The references are for read-only purposes and MUST NOT
 * be mutated in any way.
 */
public final class ServerReferenceHolder implements ServerReferencesProvider
{
  @Inject
  private CoordinatorClient coordinator;

  @Inject
  private OverlordClient overlord;

  @Inject
  private BrokerClient broker;

  @Inject(optional = true)
  private LatchableEmitter serviceEmitter;

  @Inject(optional = true)
  private IndexerMetadataStorageCoordinator segmentsMetadataStorage;

  @Self
  @Inject
  private DruidNode selfNode;

  @Override
  public DruidNode selfNode()
  {
    return selfNode;
  }

  @Override
  public CoordinatorClient leaderCoordinator()
  {
    return coordinator;
  }

  @Override
  public OverlordClient leaderOverlord()
  {
    return overlord;
  }

  @Override
  public BrokerClient anyBroker()
  {
    return broker;
  }

  @Override
  public LatchableEmitter latchableEmitter()
  {
    return Objects.requireNonNull(serviceEmitter, "LatchableEmitter is not bound");
  }

  @Override
  public IndexerMetadataStorageCoordinator segmentsMetadataStorage()
  {
    return Objects.requireNonNull(segmentsMetadataStorage, "Segment metadata storage is not bound");
  }
}

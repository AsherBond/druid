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

package org.apache.druid.indexing.overlord;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.common.config.Configs;
import org.apache.druid.guice.annotations.PublicApi;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.common.task.batch.parallel.ParallelIndexSupervisorTask;
import org.apache.druid.indexing.worker.TaskAnnouncement;
import org.apache.druid.indexing.worker.Worker;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A snapshot of a Worker and its current state i.e tasks assigned to that worker.
 */
@PublicApi
public class ImmutableWorkerInfo
{
  private final Worker worker;
  private final int currCapacityUsed;
  private final int currParallelIndexCapacityUsed;
  private final Map<String, Integer> currCapacityUsedByTaskType;
  private final ImmutableSet<String> availabilityGroups;
  private final ImmutableSet<String> runningTasks;
  private final DateTime lastCompletedTaskTime;

  @Nullable
  private final DateTime blacklistedUntil;

  @JsonCreator
  public ImmutableWorkerInfo(
      @JsonProperty("worker") Worker worker,
      @JsonProperty("currCapacityUsed") int currCapacityUsed,
      @JsonProperty("currParallelIndexCapacityUsed") int currParallelIndexCapacityUsed,
      @JsonProperty("currCapacityUsedByTaskType") Map<String, Integer> currCapacityUsedByTaskType,
      @JsonProperty("availabilityGroups") Set<String> availabilityGroups,
      @JsonProperty("runningTasks") Collection<String> runningTasks,
      @JsonProperty("lastCompletedTaskTime") DateTime lastCompletedTaskTime,
      @JsonProperty("blacklistedUntil") @Nullable DateTime blacklistedUntil
  )
  {
    this.worker = worker;
    this.currCapacityUsed = currCapacityUsed;
    this.currParallelIndexCapacityUsed = currParallelIndexCapacityUsed;
    this.currCapacityUsedByTaskType = Configs.valueOrDefault(currCapacityUsedByTaskType, Collections.emptyMap());
    this.availabilityGroups = ImmutableSet.copyOf(availabilityGroups);
    this.runningTasks = ImmutableSet.copyOf(runningTasks);
    this.lastCompletedTaskTime = lastCompletedTaskTime;
    this.blacklistedUntil = blacklistedUntil;
  }

  public ImmutableWorkerInfo(
      Worker worker,
      int currCapacityUsed,
      int currParallelIndexCapacityUsed,
      Map<String, Integer> currCapacityUsedByTaskType,
      Set<String> availabilityGroups,
      Collection<String> runningTasks,
      DateTime lastCompletedTaskTime
  )
  {
    this(worker, currCapacityUsed, currParallelIndexCapacityUsed, currCapacityUsedByTaskType, availabilityGroups,
         runningTasks, lastCompletedTaskTime, null
    );
  }

  public ImmutableWorkerInfo(
      Worker worker,
      int currCapacityUsed,
      Set<String> availabilityGroups,
      Collection<String> runningTasks,
      DateTime lastCompletedTaskTime
  )
  {
    this(worker, currCapacityUsed, 0, Collections.emptyMap(), availabilityGroups, runningTasks, lastCompletedTaskTime, null);
  }

  /**
   * Helper used by {@link ZkWorker} and {@link org.apache.druid.indexing.overlord.hrtr.WorkerHolder}.
   */
  public static ImmutableWorkerInfo fromWorkerAnnouncements(
      final Worker worker,
      final Map<String, TaskAnnouncement> announcements,
      final DateTime lastCompletedTaskTime,
      @Nullable final DateTime blacklistedUntil
  )
  {
    int currCapacityUsed = 0;
    int currParallelIndexCapacityUsed = 0;
    ImmutableSet.Builder<String> taskIds = ImmutableSet.builder();
    ImmutableSet.Builder<String> availabilityGroups = ImmutableSet.builder();
    Map<String, Integer> currCapacityUsedByTaskType = new HashMap<>();


    for (final Map.Entry<String, TaskAnnouncement> entry : announcements.entrySet()) {
      final TaskAnnouncement announcement = entry.getValue();

      if (announcement.getStatus().isRunnable()) {
        final String taskId = entry.getKey();
        final TaskResource taskResource = announcement.getTaskResource();
        final int requiredCapacity = taskResource.getRequiredCapacity();

        currCapacityUsed += requiredCapacity;

        if (ParallelIndexSupervisorTask.TYPE.equals(announcement.getTaskType())) {
          currParallelIndexCapacityUsed += requiredCapacity;
        }

        currCapacityUsedByTaskType.merge(announcement.getTaskType(), 1, Integer::sum);

        taskIds.add(taskId);
        availabilityGroups.add(taskResource.getAvailabilityGroup());
      }
    }

    return new ImmutableWorkerInfo(
        worker,
        currCapacityUsed,
        currParallelIndexCapacityUsed,
        currCapacityUsedByTaskType,
        availabilityGroups.build(),
        taskIds.build(),
        lastCompletedTaskTime,
        blacklistedUntil
    );
  }

  @JsonProperty("worker")
  public Worker getWorker()
  {
    return worker;
  }

  @JsonProperty("currCapacityUsed")
  public int getCurrCapacityUsed()
  {
    return currCapacityUsed;
  }

  @JsonProperty("currParallelIndexCapacityUsed")
  public int getCurrParallelIndexCapacityUsed()
  {
    return currParallelIndexCapacityUsed;
  }

  @JsonProperty("currCapacityUsedByTaskType")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public Map<String, Integer> getCurrCapacityUsedByTaskType()
  {
    return currCapacityUsedByTaskType;
  }

  @JsonProperty("availabilityGroups")
  public Set<String> getAvailabilityGroups()
  {
    return availabilityGroups;
  }

  public int getAvailableCapacity()
  {
    return getWorker().getCapacity() - getCurrCapacityUsed();
  }

  @JsonProperty("runningTasks")
  public Set<String> getRunningTasks()
  {
    return runningTasks;
  }

  @JsonProperty("lastCompletedTaskTime")
  public DateTime getLastCompletedTaskTime()
  {
    return lastCompletedTaskTime;
  }

  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public DateTime getBlacklistedUntil()
  {
    return blacklistedUntil;
  }

  public boolean isValidVersion(String minVersion)
  {
    return worker.getVersion().compareTo(minVersion) >= 0;
  }

  public boolean canRunTask(Task task, double parallelIndexTaskSlotRatio)
  {
    return (worker.getCapacity() - getCurrCapacityUsed() >= task.getTaskResource().getRequiredCapacity()
            && canRunParallelIndexTask(task, parallelIndexTaskSlotRatio)
            && !getAvailabilityGroups().contains(task.getTaskResource().getAvailabilityGroup()));
  }

  private boolean canRunParallelIndexTask(Task task, double parallelIndexTaskSlotRatio)
  {
    if (!task.getType().equals(ParallelIndexSupervisorTask.TYPE)) {
      return true;
    }
    return getWorkerParallelIndexCapacity(parallelIndexTaskSlotRatio) - getCurrParallelIndexCapacityUsed()
           >= task.getTaskResource().getRequiredCapacity();

  }

  private int getWorkerParallelIndexCapacity(double parallelIndexTaskSlotRatio)
  {
    int totalCapacity = worker.getCapacity();
    int workerParallelIndexCapacity = (int) Math.floor(parallelIndexTaskSlotRatio * totalCapacity);
    if (workerParallelIndexCapacity < 1) {
      workerParallelIndexCapacity = 1;
    }
    if (workerParallelIndexCapacity > totalCapacity) {
      workerParallelIndexCapacity = totalCapacity;
    }
    return workerParallelIndexCapacity;
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

    ImmutableWorkerInfo that = (ImmutableWorkerInfo) o;

    if (currCapacityUsed != that.currCapacityUsed) {
      return false;
    }
    if (currParallelIndexCapacityUsed != that.currParallelIndexCapacityUsed) {
      return false;
    }
    if (!currCapacityUsedByTaskType.equals(that.currCapacityUsedByTaskType)) {
      return false;
    }
    if (!worker.equals(that.worker)) {
      return false;
    }
    if (!availabilityGroups.equals(that.availabilityGroups)) {
      return false;
    }
    if (!runningTasks.equals(that.runningTasks)) {
      return false;
    }
    if (!lastCompletedTaskTime.equals(that.lastCompletedTaskTime)) {
      return false;
    }
    return !(blacklistedUntil != null
             ? !blacklistedUntil.equals(that.blacklistedUntil)
             : that.blacklistedUntil != null);
  }

  @Override
  public int hashCode()
  {
    int result = worker.hashCode();
    result = 31 * result + currCapacityUsed;
    result = 31 * result + currParallelIndexCapacityUsed;
    result = 31 * result + currCapacityUsedByTaskType.hashCode();
    result = 31 * result + availabilityGroups.hashCode();
    result = 31 * result + runningTasks.hashCode();
    result = 31 * result + lastCompletedTaskTime.hashCode();
    result = 31 * result + (blacklistedUntil != null ? blacklistedUntil.hashCode() : 0);
    return result;
  }

  @Override
  public String toString()
  {
    return "ImmutableWorkerInfo{" +
           "worker=" + worker +
           ", currCapacityUsed=" + currCapacityUsed +
           ", currParallelIndexCapacityUsed=" + currParallelIndexCapacityUsed +
           ", currCapacityUsedByTaskType=" + currCapacityUsedByTaskType +
           ", availabilityGroups=" + availabilityGroups +
           ", runningTasks=" + runningTasks +
           ", lastCompletedTaskTime=" + lastCompletedTaskTime +
           ", blacklistedUntil=" + blacklistedUntil +
           '}';
  }
}

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

package org.apache.druid.metadata;

import com.google.common.base.Optional;
import org.apache.druid.error.DruidException;
import org.apache.druid.guice.annotations.ExtensionPoint;
import org.apache.druid.indexer.TaskIdentifier;
import org.apache.druid.indexer.TaskInfo;
import org.apache.druid.metadata.TaskLookup.TaskLookupType;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@ExtensionPoint
public interface MetadataStorageActionHandler<EntryType, StatusType, LogType, LockType>
{
  /**
   * Creates a new entry.
   * 
   * @param id entry id
   * @param timestamp timestamp this entry was created
   * @param dataSource datasource associated with this entry
   * @param entry object representing this entry
   * @param active active or inactive flag
   * @param status status object associated wit this object, can be null
   * @param type entry type
   * @param groupId entry group id
   */
  void insert(
      @NotNull String id,
      @NotNull DateTime timestamp,
      @NotNull String dataSource,
      @NotNull EntryType entry,
      boolean active,
      @Nullable StatusType status,
      @NotNull String type,
      @NotNull String groupId
  );

  /**
   * Sets or updates the status for any active entry with the given id.
   * Once an entry has been set inactive, its status cannot be updated anymore.
   *
   * @param entryId entry id
   * @param active active
   * @param status status
   * @return true if the status was updated, false if the entry did not exist of if the entry was inactive
   */
  boolean setStatus(String entryId, boolean active, StatusType status);

  /**
   * Retrieves the entry with the given id.
   *
   * @param entryId entry id
   * @return optional entry, absent if the given id does not exist
   */
  Optional<EntryType> getEntry(String entryId);

  /**
   * Retrieve the status for the entry with the given id.
   *
   * @param entryId entry id
   * @return optional status, absent if entry does not exist or status is not set
   */
  Optional<StatusType> getStatus(String entryId);

  @Nullable
  TaskInfo<EntryType, StatusType> getTaskInfo(String entryId);

  /**
   * Returns a list of {@link TaskInfo} from metadata store that matches to the given filters.
   *
   * If {@code taskLookups} includes {@link TaskLookupType#ACTIVE}, it returns all active tasks in the metadata store.
   * If {@code taskLookups} includes {@link TaskLookupType#COMPLETE}, it returns all complete tasks in the metadata
   * store. For complete tasks, additional filters in {@code CompleteTaskLookup} can be applied.
   * All lookups should be processed atomically if there are more than one lookup is given.
   *
   * @param taskLookups task lookup type and filters.
   * @param datasource  datasource filter
   */
  List<TaskInfo<EntryType, StatusType>> getTaskInfos(
      Map<TaskLookupType, TaskLookup> taskLookups,
      @Nullable String datasource
  );

  /**
   * Returns the statuses of the specified tasks.
   *
   * If {@code taskLookups} includes {@link TaskLookupType#ACTIVE}, it returns all active tasks in the metadata store.
   * If {@code taskLookups} includes {@link TaskLookupType#COMPLETE}, it returns all complete tasks in the metadata
   * store. For complete tasks, additional filters in {@code CompleteTaskLookup} can be applied.
   * All lookups should be processed atomically if more than one lookup is given.
   *
   * @param taskLookups task lookup type and filters.
   * @param datasource  datasource filter
   */
  List<TaskInfo<TaskIdentifier, StatusType>> getTaskStatusList(
      Map<TaskLookupType, TaskLookup> taskLookups,
      @Nullable String datasource
  );

  default List<TaskInfo<EntryType, StatusType>> getTaskInfos(
      TaskLookup taskLookup,
      @Nullable String datasource
  )
  {
    return getTaskInfos(Collections.singletonMap(taskLookup.getType(), taskLookup), datasource);
  }

  /**
   * Add a lock to the given entry
   *
   * @param entryId entry id
   * @param lock lock to add
   * @return true if the lock was added
   */
  boolean addLock(String entryId, LockType lock);

  /**
   * Replace an existing lock with a new lock.
   *
   * @param entryId   entry id
   * @param oldLockId lock to be replaced
   * @param newLock   lock to be added
   *
   * @return true if the lock is replaced
   */
  boolean replaceLock(String entryId, long oldLockId, LockType newLock);

  /**
   * Remove the lock with the given lock id.
   *
   * @param lockId lock id
   */
  void removeLock(long lockId);

  /**
   * Remove the tasks created older than the given timestamp.
   * 
   * @param timestamp timestamp in milliseconds
   */
  void removeTasksOlderThan(long timestamp);

  /**
   * Task logs are not used anymore and this method is never called by Druid code.
   * It has been retained only for backwards compatibility with older extensions.
   * New extensions must not implement this method.
   *
   * @throws DruidException of category UNSUPPORTED whenever called.
   */
  @Deprecated
  default boolean addLog(String entryId, LogType log)
  {
    throw DruidException.defensive()
                        .ofCategory(DruidException.Category.UNSUPPORTED)
                        .build("Task actions are not logged anymore.");
  }

  /**
   * Task logs are not used anymore and this method is never called by Druid code.
   * It has been retained only for backwards compatibility with older extensions.
   * New extensions must not implement this method.
   *
   * @throws DruidException of category UNSUPPORTED whenever called.
   */
  @Deprecated
  default List<LogType> getLogs(String entryId)
  {
    throw DruidException.defensive()
                        .ofCategory(DruidException.Category.UNSUPPORTED)
                        .build("Task actions are not logged anymore.");
  }

  /**
   * Returns the locks for the given entry
   *
   * @param entryId entry id
   * @return map of lockId to lock
   */
  Map<Long, LockType> getLocks(String entryId);

  /**
   * Returns the lock id for the given entry and the lock.
   *
   * @return lock id if found, otherwise null.
   */
  @Nullable
  Long getLockId(String entryId, LockType lock);

  /**
   * Utility to migrate existing tasks to the new schema by populating type and groupId asynchronously
   */
  void populateTaskTypeAndGroupIdAsync();

}

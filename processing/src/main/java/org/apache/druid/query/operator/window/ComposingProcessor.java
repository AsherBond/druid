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

package org.apache.druid.query.operator.window;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.query.rowsandcols.RowsAndColumns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ComposingProcessor implements Processor
{
  private final Processor[] processors;

  @JsonCreator
  public ComposingProcessor(
      @JsonProperty("processors") Processor... processors
  )
  {
    this.processors = processors;
  }

  @Override
  public List<String> getOutputColumnNames()
  {
    List<String> outputColumnNames = new ArrayList<>();
    for (Processor processor : processors) {
      outputColumnNames.addAll(processor.getOutputColumnNames());
    }
    return outputColumnNames;
  }

  @JsonProperty("processors")
  public Processor[] getProcessors()
  {
    return processors;
  }

  @Override
  public RowsAndColumns process(RowsAndColumns incomingPartition)
  {
    RowsAndColumns retVal = incomingPartition;
    for (int i = processors.length - 1; i >= 0; --i) {
      retVal = processors[i].process(retVal);
    }
    return retVal;
  }

  @Override
  public boolean validateEquivalent(Processor otherProcessor)
  {
    if (otherProcessor instanceof ComposingProcessor) {
      ComposingProcessor other = (ComposingProcessor) otherProcessor;
      for (int i = 0; i < processors.length; ++i) {
        if (!processors[i].validateEquivalent(other.processors[i])) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public String toString()
  {
    return "ComposingProcessor{" +
           "processors=" + Arrays.toString(processors) +
           '}';
  }
}

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

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.guice.annotations.JsonNonNull;
import org.apache.druid.guice.annotations.Smile;

import javax.validation.Validator;
import java.util.Properties;

@LazySingleton
public class DruidSecondaryModule implements Module
{
  private final Properties properties;
  private final ObjectMapper jsonMapper;
  private final ObjectMapper jsonMapperOnlyNonNullValueSerialization;
  private final ObjectMapper smileMapper;
  private final Validator validator;

  @Inject
  public DruidSecondaryModule(
      Properties properties,
      @Json ObjectMapper jsonMapper,
      @JsonNonNull ObjectMapper jsonMapperOnlyNonNullValueSerialization,
      @Smile ObjectMapper smileMapper,
      Validator validator
  )
  {
    this.properties = properties;
    this.jsonMapper = jsonMapper;
    this.jsonMapperOnlyNonNullValueSerialization = jsonMapperOnlyNonNullValueSerialization;
    this.smileMapper = smileMapper;
    this.validator = validator;
  }

  @Override
  public void configure(Binder binder)
  {
    binder.install(new DruidGuiceExtensions());
    binder.bind(Properties.class).toInstance(properties);
    binder.bind(ObjectMapper.class).to(Key.get(ObjectMapper.class, Json.class));
    binder.bind(Validator.class).toInstance(validator);
    binder.bind(JsonConfigurator.class);
  }

  @Provides @LazySingleton @Json
  public ObjectMapper getJsonMapper(final Injector injector)
  {
    setupJackson(injector, jsonMapper);
    return jsonMapper;
  }

  @Provides @LazySingleton @JsonNonNull
  public ObjectMapper getJsonMapperOnlyNonNullValueSerialization(final Injector injector)
  {
    setupJackson(injector, jsonMapperOnlyNonNullValueSerialization);
    return jsonMapperOnlyNonNullValueSerialization;
  }

  @Provides @LazySingleton @Smile
  public ObjectMapper getSmileMapper(Injector injector)
  {
    setupJackson(injector, smileMapper);
    return smileMapper;
  }

  public static void setupJackson(Injector injector, final ObjectMapper mapper)
  {
    mapper.setInjectableValues(new GuiceInjectableValues(injector));
    setupAnnotationIntrospector(mapper, new GuiceAnnotationIntrospector());
  }

  public static void setupAnnotationIntrospector(
      final ObjectMapper mapper,
      final AnnotationIntrospector annotationIntrospector
  )
  {
    mapper.setAnnotationIntrospectors(
        new AnnotationIntrospectorPair(
            annotationIntrospector,
            mapper.getSerializationConfig().getAnnotationIntrospector()
        ),
        new AnnotationIntrospectorPair(
            annotationIntrospector,
            mapper.getDeserializationConfig().getAnnotationIntrospector()
        )
    );
  }
}

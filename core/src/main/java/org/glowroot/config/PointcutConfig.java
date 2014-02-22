/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.config;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import dataflow.quals.Pure;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.config.JsonViews.UiView;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Immutable structure to hold a pointcut configuration.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PointcutConfig {

    private final String typeName;
    private final String methodName;
    private final ImmutableList<String> methodArgTypeNames;
    private final String methodReturnTypeName;
    private final ImmutableList<MethodModifier> methodModifiers;
    private final String metricName;
    private final String spanText;
    @Nullable
    private final Long spanStackTraceThresholdMillis;
    private final boolean spanIgnoreSameNested;
    private final String traceGrouping;
    private final boolean traceBackground;

    // enabledProperty and spanEnabledProperty are for plugin authors
    private final String enabledProperty;
    private final String spanEnabledProperty;

    private final String version;

    @VisibleForTesting
    public PointcutConfig(String typeName, String methodName,
            @ReadOnly List<String> methodArgTypeNames, String methodReturnTypeName,
            @ReadOnly List<MethodModifier> methodModifiers, String metricName, String spanText,
            @Nullable Long spanStackTraceThresholdMillis, boolean spanIgnoreSameNested,
            String traceGrouping, boolean traceBackground, String enabledProperty,
            String spanEnabledProperty) {
        this.typeName = typeName;
        this.methodName = methodName;
        this.methodArgTypeNames = ImmutableList.copyOf(methodArgTypeNames);
        this.methodReturnTypeName = methodReturnTypeName;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.metricName = metricName;
        this.spanText = spanText;
        this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        this.spanIgnoreSameNested = spanIgnoreSameNested;
        this.traceGrouping = traceGrouping;
        this.traceBackground = traceBackground;
        this.enabledProperty = enabledProperty;
        this.spanEnabledProperty = spanEnabledProperty;
        version = VersionHashes.sha1(typeName, methodName, methodArgTypeNames,
                methodReturnTypeName, methodModifiers, metricName, spanText,
                spanStackTraceThresholdMillis, spanIgnoreSameNested, traceGrouping,
                traceBackground, enabledProperty, spanEnabledProperty);
    }

    public String getTypeName() {
        return typeName;
    }

    public String getMethodName() {
        return methodName;
    }

    @Immutable
    public List<String> getMethodArgTypeNames() {
        return methodArgTypeNames;
    }

    public String getMethodReturnTypeName() {
        return methodReturnTypeName;
    }

    @Immutable
    public List<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public String getMetricName() {
        return metricName;
    }

    public String getSpanText() {
        return spanText;
    }

    @Nullable
    public Long getSpanStackTraceThresholdMillis() {
        return spanStackTraceThresholdMillis;
    }

    public boolean isSpanIgnoreSameNested() {
        return spanIgnoreSameNested;
    }

    public String getTraceGrouping() {
        return traceGrouping;
    }

    public boolean isTraceBackground() {
        return traceBackground;
    }

    public String getEnabledProperty() {
        return enabledProperty;
    }

    public String getSpanEnabledProperty() {
        return spanEnabledProperty;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @JsonIgnore
    public boolean isMetric() {
        return metricName.length() > 0;
    }

    @JsonIgnore
    public boolean isSpan() {
        return spanText.length() > 0;
    }

    @JsonIgnore
    public boolean isTrace() {
        return traceGrouping.length() > 0;
    }

    @JsonCreator
    static PointcutConfig readValue(
            @JsonProperty("typeName") @Nullable String typeName,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodArgTypeNames") @Nullable List<String> methodArgTypeNames,
            @JsonProperty("methodReturnTypeName") @Nullable String methodReturnTypeName,
            @JsonProperty("methodModifiers") @Nullable List<MethodModifier> methodModifiers,
            @JsonProperty("metricName") @Nullable String metricName,
            @JsonProperty("spanText") @Nullable String spanText,
            @JsonProperty("spanStackTraceThresholdMillis") @Nullable Long spanStackTraceThresholdMillis,
            @JsonProperty("spanIgnoreSameNested") @Nullable Boolean spanIgnoreSameNested,
            @JsonProperty("traceGrouping") @Nullable String traceGrouping,
            @JsonProperty("traceBackground") @Nullable Boolean traceBackground,
            @JsonProperty("enabledProperty") @Nullable String enabledProperty,
            @JsonProperty("spanEnabledProperty") @Nullable String spanEnabledProperty,
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(typeName, "typeName");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodReturnTypeName, "methodReturnTypeName");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new PointcutConfig(typeName, methodName, orEmpty(methodArgTypeNames),
                methodReturnTypeName, orEmpty(methodModifiers), orEmpty(metricName),
                orEmpty(spanText), spanStackTraceThresholdMillis, orFalse(spanIgnoreSameNested),
                orEmpty(traceGrouping), orFalse(traceBackground), orEmpty(enabledProperty),
                orEmpty(spanEnabledProperty));
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        if (list == null) {
            return Lists.newArrayList();
        }
        return list;
    }

    private static boolean orFalse(@ReadOnly @Nullable Boolean value) {
        return value != null && value;
    }

    private static String orEmpty(@Nullable String value) {
        return Objects.firstNonNull(value, "");
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("typeName", typeName)
                .add("methodName", methodName)
                .add("methodArgTypeNames", methodArgTypeNames)
                .add("methodReturnTypeName", methodReturnTypeName)
                .add("methodModifiers", methodModifiers)
                .add("metricName", metricName)
                .add("spanText", spanText)
                .add("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis)
                .add("spanIgnoreSameNested", spanIgnoreSameNested)
                .add("traceGrouping", traceGrouping)
                .add("traceBackground", traceBackground)
                .add("enabledProperty", enabledProperty)
                .add("spanEnabledProperty", spanEnabledProperty)
                .add("version", version)
                .toString();
    }
}

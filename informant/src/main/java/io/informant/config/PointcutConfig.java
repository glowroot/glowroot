/*
 * Copyright 2013 the original author or authors.
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
package io.informant.config;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import io.informant.api.weaving.MethodModifier;

import static io.informant.common.ObjectMappers.checkRequiredProperty;

/**
 * Immutable structure to hold a dynamic span/metric pointcut.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PointcutConfig {

    private final boolean metric;
    private final boolean span;
    private final boolean trace;
    private final String typeName;
    private final String methodName;
    private final ImmutableList<String> methodArgTypeNames;
    private final String methodReturnTypeName;
    private final ImmutableList<MethodModifier> methodModifiers;
    @Nullable
    private final String metricName;
    @Nullable
    private final String spanText;
    @Nullable
    private final String traceGrouping;
    private final String version;

    @VisibleForTesting
    public PointcutConfig(boolean metric, boolean span, boolean trace, String typeName,
            String methodName, @ReadOnly List<String> methodArgTypeNames,
            String methodReturnTypeName, @ReadOnly List<MethodModifier> methodModifiers,
            @Nullable String metricName, @Nullable String spanText,
            @Nullable String traceGrouping) {
        this.metric = metric;
        this.span = span;
        this.trace = trace;
        this.typeName = typeName;
        this.methodName = methodName;
        this.methodArgTypeNames = ImmutableList.copyOf(methodArgTypeNames);
        this.methodReturnTypeName = methodReturnTypeName;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.metricName = metricName;
        this.spanText = spanText;
        this.traceGrouping = traceGrouping;
        version = VersionHashes.sha1(metric, span, trace, typeName, methodName, methodArgTypeNames,
                methodReturnTypeName, methodModifiers, metricName, spanText, traceGrouping);
    }

    public boolean isMetric() {
        return metric;
    }

    public boolean isSpan() {
        return span;
    }

    public boolean isTrace() {
        return trace;
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

    @Nullable
    public String getMetricName() {
        return metricName;
    }

    @Nullable
    public String getSpanText() {
        return spanText;
    }

    @Nullable
    public String getTraceGrouping() {
        return traceGrouping;
    }

    @JsonView(WithVersionJsonView.class)
    public String getVersion() {
        return version;
    }

    @JsonCreator
    static PointcutConfig readValue(
            @JsonProperty("metric") @Nullable Boolean metric,
            @JsonProperty("span") @Nullable Boolean span,
            @JsonProperty("trace") @Nullable Boolean trace,
            @JsonProperty("typeName") @Nullable String typeName,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodArgTypeNames") @Nullable List<String> methodArgTypeNames,
            @JsonProperty("methodReturnTypeName") @Nullable String methodReturnTypeName,
            @JsonProperty("methodModifiers") @Nullable List<MethodModifier> methodModifiers,
            @JsonProperty("metricName") @Nullable String metricName,
            @JsonProperty("spanText") @Nullable String spanText,
            @JsonProperty("traceGrouping") @Nullable String traceGrouping,
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version)
            throws JsonMappingException {
        checkRequiredProperty(typeName, "typeName");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodReturnTypeName, "methodReturnTypeName");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new PointcutConfig(orFalse(metric), orFalse(span), orFalse(trace), typeName,
                methodName, orEmpty(methodArgTypeNames), methodReturnTypeName,
                orEmpty(methodModifiers), metricName, spanText, traceGrouping);
    }

    @ReadOnly
    private static <T> List<T> orEmpty(@ReadOnly @Nullable List<T> list) {
        if (list == null) {
            return ImmutableList.of();
        }
        return list;
    }

    private static boolean orFalse(@ReadOnly @Nullable Boolean value) {
        return value == null || value;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("metric", metric)
                .add("span", span)
                .add("trace", trace)
                .add("typeName", typeName)
                .add("methodName", methodName)
                .add("methodArgTypeNames", methodArgTypeNames)
                .add("methodReturnTypeName", methodReturnTypeName)
                .add("methodModifiers", methodModifiers)
                .add("metricName", metricName)
                .add("spanText", spanText)
                .add("traceGrouping", traceGrouping)
                .add("version", version)
                .toString();
    }
}

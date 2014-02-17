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
package org.glowroot.container.config;

import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import dataflow.quals.Pure;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfig {

    @Nullable
    private String typeName;
    @Nullable
    private String methodName;
    private ImmutableList<String> methodArgTypeNames;
    @Nullable
    private String methodReturnTypeName;
    private ImmutableList<MethodModifier> methodModifiers;
    @Nullable
    private String metricName;
    @Nullable
    private String spanText;
    @Nullable
    private Long spanStackTraceThresholdMillis;
    @Nullable
    private String traceGrouping;
    private boolean traceBackground;

    // null for new PointcutConfig records that haven't been sent to server yet
    @Nullable
    private final String version;

    // used to create new PointcutConfig records that haven't been sent to server yet
    public PointcutConfig() {
        methodArgTypeNames = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        version = null;
    }

    public PointcutConfig(String version) {
        methodArgTypeNames = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        this.version = version;
    }

    @Nullable
    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    @Nullable
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public ImmutableList<String> getMethodArgTypeNames() {
        return methodArgTypeNames;
    }

    public void setMethodArgTypeNames(@ReadOnly List<String> methodArgTypeNames) {
        this.methodArgTypeNames = ImmutableList.copyOf(methodArgTypeNames);
    }

    @Nullable
    public String getMethodReturnTypeName() {
        return methodReturnTypeName;
    }

    public void setMethodReturnTypeName(String methodReturnTypeName) {
        this.methodReturnTypeName = methodReturnTypeName;
    }

    public ImmutableList<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public void setMethodModifiers(@ReadOnly List<MethodModifier> methodModifiers) {
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
    }

    @Nullable
    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(@Nullable String metricName) {
        this.metricName = metricName;
    }

    @Nullable
    public String getSpanText() {
        return spanText;
    }

    public void setSpanText(@Nullable String spanText) {
        this.spanText = spanText;
    }

    @Nullable
    public Long getSpanStackTraceThresholdMillis() {
        return spanStackTraceThresholdMillis;
    }

    public void setSpanStackTraceThresholdMillis(@Nullable Long spanStackTraceThresholdMillis) {
        this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
    }

    @Nullable
    public String getTraceGrouping() {
        return traceGrouping;
    }

    public void setTraceGrouping(@Nullable String traceGrouping) {
        this.traceGrouping = traceGrouping;
    }

    public boolean isTraceBackground() {
        return traceBackground;
    }

    public void setTraceBackground(boolean traceBackground) {
        this.traceBackground = traceBackground;
    }

    // JsonIgnore so it won't get sent to the server
    @JsonIgnore
    @Nullable
    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PointcutConfig) {
            PointcutConfig that = (PointcutConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(typeName, that.typeName)
                    && Objects.equal(methodName, that.methodName)
                    && Objects.equal(methodArgTypeNames, that.methodArgTypeNames)
                    && Objects.equal(methodReturnTypeName, that.methodReturnTypeName)
                    && Objects.equal(methodModifiers, that.methodModifiers)
                    && Objects.equal(metricName, that.metricName)
                    && Objects.equal(spanText, that.spanText)
                    && Objects.equal(spanStackTraceThresholdMillis,
                            that.spanStackTraceThresholdMillis)
                    && Objects.equal(traceGrouping, that.traceGrouping)
                    && Objects.equal(traceBackground, that.traceBackground);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(typeName, methodName, methodArgTypeNames, methodReturnTypeName,
                methodModifiers, metricName, spanText, spanStackTraceThresholdMillis,
                traceGrouping,
                traceBackground);
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
                .add("traceGrouping", traceGrouping)
                .add("traceBackground", traceBackground)
                .add("version", version)
                .toString();
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
            @JsonProperty("traceGrouping") @Nullable String traceGrouping,
            @JsonProperty("traceBackground") @Nullable Boolean traceBackground,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(typeName, "typeName");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodArgTypeNames, "methodArgTypeNames");
        checkRequiredProperty(methodReturnTypeName, "methodReturnTypeName");
        checkRequiredProperty(methodModifiers, "methodModifiers");
        checkRequiredProperty(metricName, "metricName");
        checkRequiredProperty(spanText, "spanText");
        checkRequiredProperty(traceGrouping, "traceGrouping");
        checkRequiredProperty(traceBackground, "traceBackground");
        checkRequiredProperty(version, "version");
        PointcutConfig config = new PointcutConfig(version);
        config.setTypeName(typeName);
        config.setMethodName(methodName);
        config.setMethodArgTypeNames(methodArgTypeNames);
        config.setMethodReturnTypeName(methodReturnTypeName);
        config.setMethodModifiers(methodModifiers);
        config.setMetricName(metricName);
        config.setSpanText(spanText);
        config.setSpanStackTraceThresholdMillis(spanStackTraceThresholdMillis);
        config.setTraceGrouping(traceGrouping);
        config.setTraceBackground(traceBackground);
        return config;
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC, ABSTRACT;
    }
}

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import static org.glowroot.container.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfig {

    @Nullable
    private String type;
    @Nullable
    private String methodName;
    private ImmutableList<String> methodArgTypes;
    @Nullable
    private String methodReturnType;
    private ImmutableList<MethodModifier> methodModifiers;
    @Nullable
    private String traceMetric;
    @Nullable
    private String spanText;
    @Nullable
    private Long spanStackTraceThresholdMillis;
    private boolean spanIgnoreSameNested;
    @Nullable
    private String transactionName;
    private boolean background;
    @Nullable
    private String enabledProperty;
    @Nullable
    private String spanEnabledProperty;

    // null for new PointcutConfig records that haven't been sent to server yet
    @Nullable
    private final String version;

    // used to create new PointcutConfig records that haven't been sent to server yet
    public PointcutConfig() {
        methodArgTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        version = null;
    }

    public PointcutConfig(String version) {
        methodArgTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        this.version = version;
    }

    @Nullable
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Nullable
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public ImmutableList<String> getMethodArgTypes() {
        return methodArgTypes;
    }

    public void setMethodArgTypes(List<String> methodArgTypes) {
        this.methodArgTypes = ImmutableList.copyOf(methodArgTypes);
    }

    @Nullable
    public String getMethodReturnType() {
        return methodReturnType;
    }

    public void setMethodReturnType(String methodReturnType) {
        this.methodReturnType = methodReturnType;
    }

    public ImmutableList<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public void setMethodModifiers(List<MethodModifier> methodModifiers) {
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
    }

    @Nullable
    public String getTraceMetric() {
        return traceMetric;
    }

    public void setTraceMetric(@Nullable String traceMetric) {
        this.traceMetric = traceMetric;
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

    public boolean isSpanIgnoreSameNested() {
        return spanIgnoreSameNested;
    }

    public void setSpanIgnoreSameNested(boolean spanIgnoreSameNested) {
        this.spanIgnoreSameNested = spanIgnoreSameNested;
    }

    @Nullable
    public String getTransactionName() {
        return transactionName;
    }

    public void setTransactionName(@Nullable String transactionName) {
        this.transactionName = transactionName;
    }

    public boolean isBackground() {
        return background;
    }

    public void setBackground(boolean background) {
        this.background = background;
    }

    @Nullable
    public String getEnabledProperty() {
        return enabledProperty;
    }

    public void setEnabledProperty(@Nullable String enabledProperty) {
        this.enabledProperty = enabledProperty;
    }

    @Nullable
    public String getSpanEnabledProperty() {
        return spanEnabledProperty;
    }

    public void setSpanEnabledProperty(@Nullable String spanEnabledProperty) {
        this.spanEnabledProperty = spanEnabledProperty;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PointcutConfig) {
            PointcutConfig that = (PointcutConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(type, that.type)
                    && Objects.equal(methodName, that.methodName)
                    && Objects.equal(methodArgTypes, that.methodArgTypes)
                    && Objects.equal(methodReturnType, that.methodReturnType)
                    && Objects.equal(methodModifiers, that.methodModifiers)
                    && Objects.equal(traceMetric, that.traceMetric)
                    && Objects.equal(spanText, that.spanText)
                    && Objects.equal(spanStackTraceThresholdMillis,
                            that.spanStackTraceThresholdMillis)
                    && Objects.equal(spanIgnoreSameNested, that.spanIgnoreSameNested)
                    && Objects.equal(transactionName, that.transactionName)
                    && Objects.equal(background, that.background)
                    && Objects.equal(enabledProperty, that.enabledProperty)
                    && Objects.equal(spanEnabledProperty, that.spanEnabledProperty);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(type, methodName, methodArgTypes, methodReturnType,
                methodModifiers, traceMetric, spanText, spanStackTraceThresholdMillis,
                spanIgnoreSameNested, transactionName, background, enabledProperty,
                spanEnabledProperty);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("type", type)
                .add("methodName", methodName)
                .add("methodArgTypes", methodArgTypes)
                .add("methodReturnType", methodReturnType)
                .add("methodModifiers", methodModifiers)
                .add("traceMetric", traceMetric)
                .add("spanText", spanText)
                .add("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis)
                .add("spanIgnoreSameNested", spanIgnoreSameNested)
                .add("transactionName", transactionName)
                .add("background", background)
                .add("enabledProperty", enabledProperty)
                .add("spanEnabledProperty", spanEnabledProperty)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static PointcutConfig readValue(
            @JsonProperty("type") @Nullable String type,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodArgTypes") @Nullable List</*@Nullable*/String> uncheckedMethodArgTypes,
            @JsonProperty("methodReturnType") @Nullable String methodReturnType,
            @JsonProperty("methodModifiers") @Nullable List</*@Nullable*/MethodModifier> uncheckedMethodModifiers,
            @JsonProperty("traceMetric") @Nullable String traceMetric,
            @JsonProperty("spanText") @Nullable String spanText,
            @JsonProperty("spanStackTraceThresholdMillis") @Nullable Long spanStackTraceThresholdMillis,
            @JsonProperty("spanIgnoreSameNested") @Nullable Boolean spanIgnoreSameNested,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("background") @Nullable Boolean background,
            @JsonProperty("enabledProperty") @Nullable String enabledProperty,
            @JsonProperty("spanEnabledProperty") @Nullable String spanEnabledProperty,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodArgTypes =
                checkNotNullItemsForProperty(uncheckedMethodArgTypes, "methodArgTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        checkRequiredProperty(type, "type");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodArgTypes, "methodArgTypes");
        checkRequiredProperty(methodReturnType, "methodReturnType");
        checkRequiredProperty(methodModifiers, "methodModifiers");
        checkRequiredProperty(traceMetric, "traceMetric");
        checkRequiredProperty(spanText, "spanText");
        checkRequiredProperty(spanIgnoreSameNested, "spanIgnoreSameNested");
        checkRequiredProperty(transactionName, "transactionName");
        checkRequiredProperty(background, "background");
        checkRequiredProperty(enabledProperty, "enabledProperty");
        checkRequiredProperty(spanEnabledProperty, "spanEnabledProperty");
        checkRequiredProperty(version, "version");
        PointcutConfig config = new PointcutConfig(version);
        config.setType(type);
        config.setMethodName(methodName);
        config.setMethodArgTypes(methodArgTypes);
        config.setMethodReturnType(methodReturnType);
        config.setMethodModifiers(methodModifiers);
        config.setTraceMetric(traceMetric);
        config.setSpanText(spanText);
        config.setSpanStackTraceThresholdMillis(spanStackTraceThresholdMillis);
        config.setSpanIgnoreSameNested(spanIgnoreSameNested);
        config.setTransactionName(transactionName);
        config.setBackground(background);
        config.setEnabledProperty(enabledProperty);
        config.setSpanEnabledProperty(spanEnabledProperty);
        return config;
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC, ABSTRACT;
    }
}

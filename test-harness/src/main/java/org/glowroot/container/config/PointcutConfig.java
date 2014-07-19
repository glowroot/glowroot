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
    private String className;
    @Nullable
    private String methodName;
    private ImmutableList<String> methodParameterTypes;
    @Nullable
    private String methodReturnType;
    private ImmutableList<MethodModifier> methodModifiers;
    @Nullable
    private String traceMetric;
    @Nullable
    private String messageTemplate;
    @Nullable
    private Long stackTraceThresholdMillis;
    private boolean captureSelfNested;
    @Nullable
    private String transactionType;
    @Nullable
    private String transactionNameTemplate;
    @Nullable
    private String enabledProperty;
    @Nullable
    private String spanEnabledProperty;

    // null for new PointcutConfig records that haven't been sent to server yet
    @Nullable
    private final String version;

    // used to create new PointcutConfig records that haven't been sent to server yet
    public PointcutConfig() {
        methodParameterTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        version = null;
    }

    public PointcutConfig(String version) {
        methodParameterTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        this.version = version;
    }

    @Nullable
    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    @Nullable
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public ImmutableList<String> getMethodParameterTypes() {
        return methodParameterTypes;
    }

    public void setMethodParameterTypes(List<String> methodParameterTypes) {
        this.methodParameterTypes = ImmutableList.copyOf(methodParameterTypes);
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
    public String getMessageTemplate() {
        return messageTemplate;
    }

    public void setMessageTemplate(@Nullable String messageTemplate) {
        this.messageTemplate = messageTemplate;
    }

    @Nullable
    public Long getStackTraceThresholdMillis() {
        return stackTraceThresholdMillis;
    }

    public void setStackTraceThresholdMillis(@Nullable Long stackTraceThresholdMillis) {
        this.stackTraceThresholdMillis = stackTraceThresholdMillis;
    }

    public boolean isCaptureSelfNested() {
        return captureSelfNested;
    }

    public void setCaptureSelfNested(boolean captureSelfNested) {
        this.captureSelfNested = captureSelfNested;
    }

    @Nullable
    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(@Nullable String transactionType) {
        this.transactionType = transactionType;
    }

    @Nullable
    public String getTransactionNameTemplate() {
        return transactionNameTemplate;
    }

    public void setTransactionNameTemplate(@Nullable String transactionNameTemplate) {
        this.transactionNameTemplate = transactionNameTemplate;
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
            return Objects.equal(className, that.className)
                    && Objects.equal(methodName, that.methodName)
                    && Objects.equal(methodParameterTypes, that.methodParameterTypes)
                    && Objects.equal(methodReturnType, that.methodReturnType)
                    && Objects.equal(methodModifiers, that.methodModifiers)
                    && Objects.equal(traceMetric, that.traceMetric)
                    && Objects.equal(messageTemplate, that.messageTemplate)
                    && Objects.equal(stackTraceThresholdMillis, that.stackTraceThresholdMillis)
                    && Objects.equal(captureSelfNested, that.captureSelfNested)
                    && Objects.equal(transactionType, that.transactionType)
                    && Objects.equal(transactionNameTemplate, that.transactionNameTemplate)
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
        return Objects.hashCode(className, methodName, methodParameterTypes, methodReturnType,
                methodModifiers, traceMetric, messageTemplate, stackTraceThresholdMillis,
                captureSelfNested, transactionType, transactionNameTemplate, enabledProperty,
                spanEnabledProperty);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("className", className)
                .add("methodName", methodName)
                .add("methodParameterTypes", methodParameterTypes)
                .add("methodReturnType", methodReturnType)
                .add("methodModifiers", methodModifiers)
                .add("traceMetric", traceMetric)
                .add("messageTemplate", messageTemplate)
                .add("stackTraceThresholdMillis", stackTraceThresholdMillis)
                .add("captureSelfNested", captureSelfNested)
                .add("transactionType", transactionType)
                .add("transactionNameTemplate", transactionNameTemplate)
                .add("enabledProperty", enabledProperty)
                .add("spanEnabledProperty", spanEnabledProperty)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static PointcutConfig readValue(
            @JsonProperty("className") @Nullable String className,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodParameterTypes") @Nullable List</*@Nullable*/String> uncheckedMethodParameterTypes,
            @JsonProperty("methodReturnType") @Nullable String methodReturnType,
            @JsonProperty("methodModifiers") @Nullable List</*@Nullable*/MethodModifier> uncheckedMethodModifiers,
            @JsonProperty("traceMetric") @Nullable String traceMetric,
            @JsonProperty("messageTemplate") @Nullable String messageTemplate,
            @JsonProperty("stackTraceThresholdMillis") @Nullable Long stackTraceThresholdMillis,
            @JsonProperty("captureSelfNested") @Nullable Boolean captureSelfNested,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionNameTemplate") @Nullable String transactionNameTemplate,
            @JsonProperty("enabledProperty") @Nullable String enabledProperty,
            @JsonProperty("spanEnabledProperty") @Nullable String spanEnabledProperty,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodParameterTypes =
                checkNotNullItemsForProperty(uncheckedMethodParameterTypes, "methodParameterTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        checkRequiredProperty(className, "className");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodParameterTypes, "methodParameterTypes");
        checkRequiredProperty(methodReturnType, "methodReturnType");
        checkRequiredProperty(methodModifiers, "methodModifiers");
        checkRequiredProperty(traceMetric, "traceMetric");
        checkRequiredProperty(messageTemplate, "messageTemplate");
        checkRequiredProperty(captureSelfNested, "captureSelfNested");
        checkRequiredProperty(transactionType, "transactionType");
        checkRequiredProperty(transactionNameTemplate, "transactionNameTemplate");
        checkRequiredProperty(enabledProperty, "enabledProperty");
        checkRequiredProperty(spanEnabledProperty, "spanEnabledProperty");
        checkRequiredProperty(version, "version");
        PointcutConfig config = new PointcutConfig(version);
        config.setClassName(className);
        config.setMethodName(methodName);
        config.setMethodParameterTypes(methodParameterTypes);
        config.setMethodReturnType(methodReturnType);
        config.setMethodModifiers(methodModifiers);
        config.setTraceMetric(traceMetric);
        config.setMessageTemplate(messageTemplate);
        config.setStackTraceThresholdMillis(stackTraceThresholdMillis);
        config.setCaptureSelfNested(captureSelfNested);
        config.setTransactionType(transactionType);
        config.setTransactionNameTemplate(transactionNameTemplate);
        config.setEnabledProperty(enabledProperty);
        config.setSpanEnabledProperty(spanEnabledProperty);
        return config;
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC, ABSTRACT;
    }
}

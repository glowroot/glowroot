/*
 * Copyright 2013-2015 the original author or authors.
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
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class InstrumentationConfig {

    private @Nullable String className;
    private @Nullable String declaringClassName;
    private @Nullable String methodName;
    private ImmutableList<String> methodParameterTypes;
    private @Nullable String methodReturnType;
    private ImmutableList<MethodModifier> methodModifiers;
    private @Nullable CaptureKind captureKind;

    private @Nullable String timerName;

    private @Nullable String traceEntryMessageTemplate;
    private @Nullable Long traceEntryStackThresholdMillis;
    private boolean traceEntryCaptureSelfNested;

    private @Nullable String transactionType;
    private @Nullable String transactionNameTemplate;
    private @Nullable String transactionUserTemplate;
    private @Nullable Map<String, String> transactionAttributeTemplates;

    private @Nullable Long transactionSlowThresholdMillis;

    private @Nullable String enabledProperty;
    private @Nullable String traceEntryEnabledProperty;

    // null for new instrumentation config records that haven't been sent to server yet
    private @Nullable final String version;

    // used to create new instrumentation config records that haven't been sent to server yet
    public InstrumentationConfig() {
        methodParameterTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        transactionAttributeTemplates = ImmutableMap.of();
        version = null;
    }

    @JsonCreator
    private InstrumentationConfig(@JsonProperty("version") String version) {
        methodParameterTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        transactionAttributeTemplates = ImmutableMap.of();
        this.version = version;
    }

    public @Nullable String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public @Nullable String getDeclaringClassName() {
        return declaringClassName;
    }

    public void setDeclaringClassName(String declaringClassName) {
        this.declaringClassName = declaringClassName;
    }

    public @Nullable String getMethodName() {
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

    public @Nullable String getMethodReturnType() {
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

    public @Nullable CaptureKind getCaptureKind() {
        return captureKind;
    }

    public void setCaptureKind(CaptureKind captureKind) {
        this.captureKind = captureKind;
    }

    public @Nullable String getTimerName() {
        return timerName;
    }

    public void setTimerName(String timerName) {
        this.timerName = timerName;
    }

    public @Nullable String getTraceEntryMessageTemplate() {
        return traceEntryMessageTemplate;
    }

    public void setTraceEntryMessageTemplate(String traceEntryMessageTemplate) {
        this.traceEntryMessageTemplate = traceEntryMessageTemplate;
    }

    public @Nullable Long getTraceEntryStackThresholdMillis() {
        return traceEntryStackThresholdMillis;
    }

    public void setTraceEntryStackThresholdMillis(@Nullable Long traceEntryStackThresholdMillis) {
        this.traceEntryStackThresholdMillis = traceEntryStackThresholdMillis;
    }

    public boolean isTraceEntryCaptureSelfNested() {
        return traceEntryCaptureSelfNested;
    }

    public void setTraceEntryCaptureSelfNested(boolean traceEntryCaptureSelfNested) {
        this.traceEntryCaptureSelfNested = traceEntryCaptureSelfNested;
    }

    public @Nullable String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public @Nullable String getTransactionNameTemplate() {
        return transactionNameTemplate;
    }

    public void setTransactionNameTemplate(String transactionNameTemplate) {
        this.transactionNameTemplate = transactionNameTemplate;
    }

    public @Nullable String getTransactionUserTemplate() {
        return transactionUserTemplate;
    }

    public void setTransactionUserTemplate(String transactionUserTemplate) {
        this.transactionUserTemplate = transactionUserTemplate;
    }

    public @Nullable Map<String, String> getTransactionAttributeTemplates() {
        return transactionAttributeTemplates;
    }

    public void setTransactionAttributeTemplates(
            Map<String, String> transactionAttributeTemplates) {
        this.transactionAttributeTemplates = transactionAttributeTemplates;
    }

    public @Nullable Long getTransactionSlowThresholdMillis() {
        return transactionSlowThresholdMillis;
    }

    public void setTransactionSlowThresholdMillis(@Nullable Long transactionSlowThresholdMillis) {
        this.transactionSlowThresholdMillis = transactionSlowThresholdMillis;
    }

    public @Nullable String getEnabledProperty() {
        return enabledProperty;
    }

    public void setEnabledProperty(String enabledProperty) {
        this.enabledProperty = enabledProperty;
    }

    public @Nullable String getTraceEntryEnabledProperty() {
        return traceEntryEnabledProperty;
    }

    public void setTraceEntryEnabledProperty(String traceEntryEnabledProperty) {
        this.traceEntryEnabledProperty = traceEntryEnabledProperty;
    }

    public @Nullable String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof InstrumentationConfig) {
            InstrumentationConfig that = (InstrumentationConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(className, that.className)
                    && Objects.equal(declaringClassName, that.declaringClassName)
                    && Objects.equal(methodName, that.methodName)
                    && Objects.equal(methodParameterTypes, that.methodParameterTypes)
                    && Objects.equal(methodReturnType, that.methodReturnType)
                    && Objects.equal(methodModifiers, that.methodModifiers)
                    && Objects.equal(captureKind, that.captureKind)
                    && Objects.equal(timerName, that.timerName)
                    && Objects.equal(traceEntryMessageTemplate, that.traceEntryMessageTemplate)
                    && Objects.equal(traceEntryStackThresholdMillis,
                            that.traceEntryStackThresholdMillis)
                    && Objects.equal(traceEntryCaptureSelfNested, that.traceEntryCaptureSelfNested)
                    && Objects.equal(transactionType, that.transactionType)
                    && Objects.equal(transactionNameTemplate, that.transactionNameTemplate)
                    && Objects.equal(transactionUserTemplate, that.transactionUserTemplate)
                    && Objects.equal(transactionAttributeTemplates,
                            that.transactionAttributeTemplates)
                    && Objects.equal(transactionSlowThresholdMillis,
                            that.transactionSlowThresholdMillis)
                    && Objects.equal(enabledProperty, that.enabledProperty)
                    && Objects.equal(traceEntryEnabledProperty, that.traceEntryEnabledProperty);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(className, declaringClassName, methodName, methodParameterTypes,
                methodReturnType, methodModifiers, captureKind, timerName,
                traceEntryMessageTemplate,
                traceEntryStackThresholdMillis, traceEntryCaptureSelfNested, transactionType,
                transactionNameTemplate, transactionUserTemplate,
                transactionAttributeTemplates, transactionSlowThresholdMillis,
                enabledProperty,
                traceEntryEnabledProperty);
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC, ABSTRACT;
    }

    public enum CaptureKind {
        TIMER, TRACE_ENTRY, TRANSACTION, OTHER
    }
}

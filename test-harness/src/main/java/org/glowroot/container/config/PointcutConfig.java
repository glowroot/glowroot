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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import static org.glowroot.container.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.container.common.ObjectMappers.checkNotNullValuesForProperty;
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
    private AdviceKind adviceKind;
    @Nullable
    private String metricName;
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
    private String traceUserTemplate;
    @Nullable
    private Map<String, String> traceCustomAttributeTemplates;
    @Nullable
    private Long traceStoreThresholdMillis;
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
        traceCustomAttributeTemplates = ImmutableMap.of();
        version = null;
    }

    public PointcutConfig(String version) {
        methodParameterTypes = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        traceCustomAttributeTemplates = ImmutableMap.of();
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
    public AdviceKind getAdviceKind() {
        return adviceKind;
    }

    public void setAdviceKind(AdviceKind adviceKind) {
        this.adviceKind = adviceKind;
    }

    @Nullable
    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    @Nullable
    public String getMessageTemplate() {
        return messageTemplate;
    }

    public void setMessageTemplate(String messageTemplate) {
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

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    @Nullable
    public String getTransactionNameTemplate() {
        return transactionNameTemplate;
    }

    public void setTransactionNameTemplate(String transactionNameTemplate) {
        this.transactionNameTemplate = transactionNameTemplate;
    }

    @Nullable
    public String getTraceUserTemplate() {
        return traceUserTemplate;
    }

    public void setTraceUserTemplate(String traceUserTemplate) {
        this.traceUserTemplate = traceUserTemplate;
    }

    @Nullable
    public Map<String, String> getTraceCustomAttributeTemplates() {
        return traceCustomAttributeTemplates;
    }

    public void setTraceCustomAttributeTemplates(
            Map<String, String> traceCustomAttributeTemplates) {
        this.traceCustomAttributeTemplates = traceCustomAttributeTemplates;
    }

    @Nullable
    public Long getTraceStoreThresholdMillis() {
        return traceStoreThresholdMillis;
    }

    public void setTraceStoreThresholdMillis(@Nullable Long traceStoreThresholdMillis) {
        this.traceStoreThresholdMillis = traceStoreThresholdMillis;
    }

    @Nullable
    public String getEnabledProperty() {
        return enabledProperty;
    }

    public void setEnabledProperty(String enabledProperty) {
        this.enabledProperty = enabledProperty;
    }

    @Nullable
    public String getSpanEnabledProperty() {
        return spanEnabledProperty;
    }

    public void setSpanEnabledProperty(String spanEnabledProperty) {
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
                    && Objects.equal(adviceKind, that.adviceKind)
                    && Objects.equal(metricName, that.metricName)
                    && Objects.equal(messageTemplate, that.messageTemplate)
                    && Objects.equal(stackTraceThresholdMillis, that.stackTraceThresholdMillis)
                    && Objects.equal(captureSelfNested, that.captureSelfNested)
                    && Objects.equal(transactionType, that.transactionType)
                    && Objects.equal(transactionNameTemplate, that.transactionNameTemplate)
                    && Objects.equal(traceUserTemplate, that.traceUserTemplate)
                    && Objects.equal(traceCustomAttributeTemplates,
                            that.traceCustomAttributeTemplates)
                    && Objects.equal(traceStoreThresholdMillis, that.traceStoreThresholdMillis)
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
                methodModifiers, adviceKind, metricName, messageTemplate,
                stackTraceThresholdMillis, captureSelfNested, transactionType,
                transactionNameTemplate, traceUserTemplate, traceCustomAttributeTemplates,
                traceStoreThresholdMillis, enabledProperty, spanEnabledProperty);
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
                .add("adviceKind", adviceKind)
                .add("metricName", metricName)
                .add("messageTemplate", messageTemplate)
                .add("stackTraceThresholdMillis", stackTraceThresholdMillis)
                .add("captureSelfNested", captureSelfNested)
                .add("transactionType", transactionType)
                .add("transactionNameTemplate", transactionNameTemplate)
                .add("traceUserTemplate", traceUserTemplate)
                .add("traceCustomAttributeTemplates", traceCustomAttributeTemplates)
                .add("traceStoreThresholdMillis", traceStoreThresholdMillis)
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
            @JsonProperty("adviceKind") @Nullable AdviceKind adviceKind,
            @JsonProperty("metricName") @Nullable String metricName,
            @JsonProperty("messageTemplate") @Nullable String messageTemplate,
            @JsonProperty("stackTraceThresholdMillis") @Nullable Long stackTraceThresholdMillis,
            @JsonProperty("captureSelfNested") @Nullable Boolean captureSelfNested,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionNameTemplate") @Nullable String transactionNameTemplate,
            @JsonProperty("traceUserTemplate") @Nullable String traceUserTemplate,
            @JsonProperty("traceCustomAttributeTemplates") @Nullable Map<String, /*@Nullable*/String> uncheckedTraceCustomAttributeTemplates,
            @JsonProperty("traceStoreThresholdMillis") @Nullable Long traceStoreThresholdMillis,
            @JsonProperty("enabledProperty") @Nullable String enabledProperty,
            @JsonProperty("spanEnabledProperty") @Nullable String spanEnabledProperty,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodParameterTypes =
                checkNotNullItemsForProperty(uncheckedMethodParameterTypes, "methodParameterTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        Map<String, String> traceCustomAttributeTemplates = checkNotNullValuesForProperty(
                uncheckedTraceCustomAttributeTemplates, "traceCustomAttributeTemplates");
        checkRequiredProperty(className, "className");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodParameterTypes, "methodParameterTypes");
        checkRequiredProperty(methodReturnType, "methodReturnType");
        checkRequiredProperty(methodModifiers, "methodModifiers");
        checkRequiredProperty(adviceKind, "adviceKind");
        checkRequiredProperty(metricName, "metricName");
        checkRequiredProperty(messageTemplate, "messageTemplate");
        checkRequiredProperty(captureSelfNested, "captureSelfNested");
        checkRequiredProperty(transactionType, "transactionType");
        checkRequiredProperty(transactionNameTemplate, "transactionNameTemplate");
        checkRequiredProperty(traceUserTemplate, "traceUserTemplate");
        checkRequiredProperty(traceCustomAttributeTemplates, "traceCustomAttributeTemplates");
        checkRequiredProperty(enabledProperty, "enabledProperty");
        checkRequiredProperty(spanEnabledProperty, "spanEnabledProperty");
        checkRequiredProperty(version, "version");
        PointcutConfig config = new PointcutConfig(version);
        config.setClassName(className);
        config.setMethodName(methodName);
        config.setMethodParameterTypes(methodParameterTypes);
        config.setMethodReturnType(methodReturnType);
        config.setMethodModifiers(methodModifiers);
        config.setAdviceKind(adviceKind);
        config.setMetricName(metricName);
        config.setMessageTemplate(messageTemplate);
        config.setStackTraceThresholdMillis(stackTraceThresholdMillis);
        config.setCaptureSelfNested(captureSelfNested);
        config.setTransactionType(transactionType);
        config.setTransactionNameTemplate(transactionNameTemplate);
        config.setTraceUserTemplate(traceUserTemplate);
        config.setTraceCustomAttributeTemplates(traceCustomAttributeTemplates);
        config.setTraceStoreThresholdMillis(traceStoreThresholdMillis);
        config.setEnabledProperty(enabledProperty);
        config.setSpanEnabledProperty(spanEnabledProperty);
        return config;
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC, ABSTRACT;
    }

    public static enum AdviceKind {
        METRIC, SPAN, TRACE, OTHER
    }
}

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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;

import static com.google.common.base.Strings.nullToEmpty;
import static org.glowroot.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.common.ObjectMappers.checkNotNullValuesForProperty;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;
import static org.glowroot.common.ObjectMappers.nullToFalse;

/**
 * Immutable structure to hold a pointcut configuration.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PointcutConfig {

    private final String className;
    private final String methodName;
    private final ImmutableList<String> methodParameterTypes;
    private final String methodReturnType;
    private final ImmutableList<MethodModifier> methodModifiers;

    private final AdviceKind adviceKind;
    private final String metricName;
    private final String messageTemplate;
    @Nullable
    private final Long stackTraceThresholdMillis;
    private final boolean captureSelfNested;
    private final String transactionType;
    private final String transactionNameTemplate;
    private final String traceUserTemplate;
    private final ImmutableMap<String, String> traceCustomAttributeTemplates;
    @Nullable
    private final Long traceStoreThresholdMillis;

    // enabledProperty and spanEnabledProperty are for plugin authors
    private final String enabledProperty;
    private final String spanEnabledProperty;

    private final String version;

    @VisibleForTesting
    public PointcutConfig(String className, String methodName, List<String> methodParameterTypes,
            String methodReturnType, List<MethodModifier> methodModifiers,
            AdviceKind adviceKind, String metricName, String messageTemplate,
            @Nullable Long stackTraceThresholdMillis, boolean captureSelfNested,
            String transactionType, String transactionNameTemplate, String traceUserTemplate,
            Map<String, String> traceCustomAttributeTemplates,
            @Nullable Long traceStoreThresholdMillis, String enabledProperty,
            String spanEnabledProperty) {
        this.className = className;
        this.methodName = methodName;
        this.methodParameterTypes = ImmutableList.copyOf(methodParameterTypes);
        this.methodReturnType = methodReturnType;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.adviceKind = adviceKind;
        this.metricName = metricName;
        this.messageTemplate = messageTemplate;
        this.stackTraceThresholdMillis = stackTraceThresholdMillis;
        this.captureSelfNested = captureSelfNested;
        this.transactionType = transactionType;
        this.transactionNameTemplate = transactionNameTemplate;
        this.traceUserTemplate = traceUserTemplate;
        this.traceCustomAttributeTemplates = ImmutableMap.copyOf(traceCustomAttributeTemplates);
        this.traceStoreThresholdMillis = traceStoreThresholdMillis;
        this.enabledProperty = enabledProperty;
        this.spanEnabledProperty = spanEnabledProperty;
        version = VersionHashes.sha1(className, methodName, methodParameterTypes, methodReturnType,
                methodModifiers, adviceKind, metricName, messageTemplate,
                stackTraceThresholdMillis, captureSelfNested, transactionType,
                transactionNameTemplate, traceUserTemplate, traceCustomAttributeTemplates,
                traceStoreThresholdMillis, enabledProperty, spanEnabledProperty);
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public ImmutableList<String> getMethodParameterTypes() {
        return methodParameterTypes;
    }

    public String getMethodReturnType() {
        return methodReturnType;
    }

    // TODO this is unused
    public ImmutableList<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public AdviceKind getAdviceKind() {
        return adviceKind;
    }

    public String getMetricName() {
        return metricName;
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    @Nullable
    public Long getStackTraceThresholdMillis() {
        return stackTraceThresholdMillis;
    }

    public boolean isCaptureSelfNested() {
        return captureSelfNested;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getTransactionNameTemplate() {
        return transactionNameTemplate;
    }

    public String getTraceUserTemplate() {
        return traceUserTemplate;
    }

    public ImmutableMap<String, String> getTraceCustomAttributeTemplates() {
        return traceCustomAttributeTemplates;
    }

    @Nullable
    public Long getTraceStoreThresholdMillis() {
        return traceStoreThresholdMillis;
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
    public boolean isMetricOrGreater() {
        return adviceKind == AdviceKind.METRIC || adviceKind == AdviceKind.SPAN
                || adviceKind == AdviceKind.TRACE;
    }

    @JsonIgnore
    public boolean isSpanOrGreater() {
        return adviceKind == AdviceKind.SPAN || adviceKind == AdviceKind.TRACE;
    }

    @JsonIgnore
    public boolean isTrace() {
        return adviceKind == AdviceKind.TRACE;
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
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodParameterTypes =
                checkNotNullItemsForProperty(uncheckedMethodParameterTypes, "methodParameterTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        Map<String, String> traceCustomAttributeTemplates = checkNotNullValuesForProperty(
                uncheckedTraceCustomAttributeTemplates, "traceCustomAttributeTemplates");
        checkRequiredProperty(className, "className");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(adviceKind, "adviceKind");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new PointcutConfig(className, methodName, nullToEmpty(methodParameterTypes),
                nullToEmpty(methodReturnType), nullToEmpty(methodModifiers), adviceKind,
                nullToEmpty(metricName), nullToEmpty(messageTemplate), stackTraceThresholdMillis,
                nullToFalse(captureSelfNested), nullToEmpty(transactionType),
                nullToEmpty(transactionNameTemplate), nullToEmpty(traceUserTemplate),
                nullToEmpty(traceCustomAttributeTemplates), traceStoreThresholdMillis,
                nullToEmpty(enabledProperty), nullToEmpty(spanEnabledProperty));
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

    public static enum AdviceKind {
        METRIC, SPAN, TRACE, OTHER
    }
}

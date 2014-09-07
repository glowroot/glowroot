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
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * Immutable structure to hold a capture point configuration.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class CapturePoint {

    private final String className;
    private final String methodName;
    private final ImmutableList<String> methodParameterTypes;
    private final String methodReturnType;
    private final ImmutableList<MethodModifier> methodModifiers;

    private final CaptureKind captureKind;

    private final String metricName;

    private final String traceEntryTemplate;
    @Nullable
    private final Long traceEntryStackThresholdMillis;
    private final boolean traceEntryCaptureSelfNested;

    private final String transactionType;
    private final String transactionNameTemplate;
    private final String transactionUserTemplate;
    private final ImmutableMap<String, String> transactionCustomAttributeTemplates;

    @Nullable
    private final Long traceStoreThresholdMillis;

    // enabledProperty and traceEntryEnabledProperty are for plugin authors
    private final String enabledProperty;
    private final String traceEntryEnabledProperty;

    private final String version;

    @VisibleForTesting
    public CapturePoint(String className, String methodName, List<String> methodParameterTypes,
            String methodReturnType, List<MethodModifier> methodModifiers, CaptureKind captureKind,
            String metricName, String traceEntryTemplate,
            @Nullable Long entryStackTraceThresholdMillis, boolean traceEntryCaptureSelfNested,
            String transactionType, String transactionNameTemplate,
            @Nullable Long traceStoreThresholdMillis, String transactionUserTemplate,
            Map<String, String> transactionCustomAttributeTemplates, String enabledProperty,
            String traceEntryEnabledProperty) {
        this.className = className;
        this.methodName = methodName;
        this.methodParameterTypes = ImmutableList.copyOf(methodParameterTypes);
        this.methodReturnType = methodReturnType;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.captureKind = captureKind;
        this.metricName = metricName;
        this.traceEntryTemplate = traceEntryTemplate;
        this.traceEntryStackThresholdMillis = entryStackTraceThresholdMillis;
        this.traceEntryCaptureSelfNested = traceEntryCaptureSelfNested;
        this.transactionType = transactionType;
        this.transactionNameTemplate = transactionNameTemplate;
        this.traceStoreThresholdMillis = traceStoreThresholdMillis;
        this.transactionUserTemplate = transactionUserTemplate;
        this.transactionCustomAttributeTemplates =
                ImmutableMap.copyOf(transactionCustomAttributeTemplates);
        this.enabledProperty = enabledProperty;
        this.traceEntryEnabledProperty = traceEntryEnabledProperty;
        version = VersionHashes.sha1(className, methodName, methodParameterTypes, methodReturnType,
                methodModifiers, captureKind, metricName, traceEntryTemplate,
                entryStackTraceThresholdMillis, traceEntryCaptureSelfNested, transactionType,
                transactionNameTemplate, transactionUserTemplate,
                transactionCustomAttributeTemplates, traceStoreThresholdMillis, enabledProperty,
                traceEntryEnabledProperty);
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

    public CaptureKind getCaptureKind() {
        return captureKind;
    }

    public String getMetricName() {
        return metricName;
    }

    public String getTraceEntryTemplate() {
        return traceEntryTemplate;
    }

    @Nullable
    public Long getTraceEntryStackThresholdMillis() {
        return traceEntryStackThresholdMillis;
    }

    public boolean isTraceEntryCaptureSelfNested() {
        return traceEntryCaptureSelfNested;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getTransactionNameTemplate() {
        return transactionNameTemplate;
    }

    public String getTransactionUserTemplate() {
        return transactionUserTemplate;
    }

    public ImmutableMap<String, String> getTransactionCustomAttributeTemplates() {
        return transactionCustomAttributeTemplates;
    }

    @Nullable
    public Long getTraceStoreThresholdMillis() {
        return traceStoreThresholdMillis;
    }

    public String getEnabledProperty() {
        return enabledProperty;
    }

    public String getTraceEntryEnabledProperty() {
        return traceEntryEnabledProperty;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @JsonIgnore
    public boolean isMetricOrGreater() {
        return captureKind == CaptureKind.METRIC || captureKind == CaptureKind.TRACE_ENTRY
                || captureKind == CaptureKind.TRANSACTION;
    }

    @JsonIgnore
    public boolean isTraceEntryOrGreater() {
        return captureKind == CaptureKind.TRACE_ENTRY || captureKind == CaptureKind.TRANSACTION;
    }

    @JsonIgnore
    public boolean isTransaction() {
        return captureKind == CaptureKind.TRANSACTION;
    }

    @JsonCreator
    static CapturePoint readValue(
            @JsonProperty("className") @Nullable String className,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodParameterTypes") @Nullable List</*@Nullable*/String> uncheckedMethodParameterTypes,
            @JsonProperty("methodReturnType") @Nullable String methodReturnType,
            @JsonProperty("methodModifiers") @Nullable List</*@Nullable*/MethodModifier> uncheckedMethodModifiers,
            @JsonProperty("captureKind") @Nullable CaptureKind captureKind,
            @JsonProperty("metricName") @Nullable String metricName,
            @JsonProperty("traceEntryTemplate") @Nullable String traceEntryTemplate,
            @JsonProperty("traceEntryStackThresholdMillis") @Nullable Long traceEntryStackThresholdMillis,
            @JsonProperty("traceEntryCaptureSelfNested") @Nullable Boolean traceEntryCaptureSelfNested,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionNameTemplate") @Nullable String transactionNameTemplate,
            @JsonProperty("transactionUserTemplate") @Nullable String transactionUserTemplate,
            @JsonProperty("transactionCustomAttributeTemplates") @Nullable Map<String, /*@Nullable*/String> uncheckedTransactionCustomAttributeTemplates,
            @JsonProperty("traceStoreThresholdMillis") @Nullable Long traceStoreThresholdMillis,
            @JsonProperty("enabledProperty") @Nullable String enabledProperty,
            @JsonProperty("traceEntryEnabledProperty") @Nullable String traceEntryEnabledProperty,
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodParameterTypes =
                checkNotNullItemsForProperty(uncheckedMethodParameterTypes, "methodParameterTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        Map<String, String> transactionCustomAttributeTemplates =
                checkNotNullValuesForProperty(uncheckedTransactionCustomAttributeTemplates,
                        "transactionCustomAttributeTemplates");
        checkRequiredProperty(className, "className");
        checkRequiredProperty(methodName, "methodName");
        // methodParameterTypes is required since it's not clear whether default should be
        // empty array (matching no-arg method) or [ '..' ] (matching any-arg method)
        checkRequiredProperty(methodParameterTypes, "methodParameterTypes");
        checkRequiredProperty(captureKind, "captureKind");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new CapturePoint(className, methodName, nullToEmpty(methodParameterTypes),
                nullToEmpty(methodReturnType), nullToEmpty(methodModifiers), captureKind,
                nullToEmpty(metricName), nullToEmpty(traceEntryTemplate),
                traceEntryStackThresholdMillis, nullToFalse(traceEntryCaptureSelfNested),
                nullToEmpty(transactionType), nullToEmpty(transactionNameTemplate),
                traceStoreThresholdMillis, nullToEmpty(transactionUserTemplate),
                nullToEmpty(transactionCustomAttributeTemplates), nullToEmpty(enabledProperty),
                nullToEmpty(traceEntryEnabledProperty));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("className", className)
                .add("methodName", methodName)
                .add("methodParameterTypes", methodParameterTypes)
                .add("methodReturnType", methodReturnType)
                .add("methodModifiers", methodModifiers)
                .add("captureKind", captureKind)
                .add("metricName", metricName)
                .add("traceEntryTemplate", traceEntryTemplate)
                .add("traceEntryStackThresholdMillis", traceEntryStackThresholdMillis)
                .add("traceEntryCaptureSelfNested", traceEntryCaptureSelfNested)
                .add("transactionType", transactionType)
                .add("transactionNameTemplate", transactionNameTemplate)
                .add("transactionUserTemplate", transactionUserTemplate)
                .add("transactionCustomAttributeTemplates", transactionCustomAttributeTemplates)
                .add("traceStoreThresholdMillis", traceStoreThresholdMillis)
                .add("enabledProperty", enabledProperty)
                .add("traceEntryEnabledProperty", traceEntryEnabledProperty)
                .add("version", version)
                .toString();
    }

    public static enum CaptureKind {
        METRIC, TRACE_ENTRY, TRANSACTION, OTHER
    }
}

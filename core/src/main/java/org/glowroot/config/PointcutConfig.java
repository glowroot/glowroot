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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;

import static com.google.common.base.Strings.nullToEmpty;
import static org.glowroot.common.ObjectMappers.checkNotNullItemsForProperty;
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
    private final String traceMetric;
    private final String messageTemplate;
    @Nullable
    private final Long stackTraceThresholdMillis;
    private final boolean captureSelfNested;
    private final String transactionType;
    private final String transactionNameTemplate;

    // enabledProperty and spanEnabledProperty are for plugin authors
    private final String enabledProperty;
    private final String spanEnabledProperty;

    private final String version;

    @VisibleForTesting
    public PointcutConfig(String className, String methodName, List<String> methodParameterTypes,
            String methodReturnType, List<MethodModifier> methodModifiers, String traceMetric,
            String messageTemplate, @Nullable Long stackTraceThresholdMillis,
            boolean captureSelfNested, String transactionType, String transactionNameTemplate,
            String enabledProperty, String spanEnabledProperty) {
        this.className = className;
        this.methodName = methodName;
        this.methodParameterTypes = ImmutableList.copyOf(methodParameterTypes);
        this.methodReturnType = methodReturnType;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.traceMetric = traceMetric;
        this.messageTemplate = messageTemplate;
        this.stackTraceThresholdMillis = stackTraceThresholdMillis;
        this.captureSelfNested = captureSelfNested;
        this.transactionType = transactionType;
        this.transactionNameTemplate = transactionNameTemplate;
        this.enabledProperty = enabledProperty;
        this.spanEnabledProperty = spanEnabledProperty;
        version = VersionHashes.sha1(className, methodName, methodParameterTypes, methodReturnType,
                methodModifiers, traceMetric, messageTemplate, stackTraceThresholdMillis,
                captureSelfNested, transactionType, transactionNameTemplate, enabledProperty,
                spanEnabledProperty);
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

    public String getTraceMetric() {
        return traceMetric;
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

    // TODO unused because spans are currently not supported without an associated trace metric
    @JsonIgnore
    public boolean isTraceMetric() {
        return !traceMetric.isEmpty();
    }

    @JsonIgnore
    public boolean isSpan() {
        return !messageTemplate.isEmpty();
    }

    @JsonIgnore
    public boolean isTrace() {
        return !transactionNameTemplate.isEmpty();
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
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodParameterTypes =
                checkNotNullItemsForProperty(uncheckedMethodParameterTypes, "methodParameterTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        checkRequiredProperty(className, "className");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodReturnType, "methodReturnType");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new PointcutConfig(className, methodName, nullToEmpty(methodParameterTypes),
                methodReturnType, nullToEmpty(methodModifiers), nullToEmpty(traceMetric),
                nullToEmpty(messageTemplate), stackTraceThresholdMillis,
                nullToFalse(captureSelfNested), nullToEmpty(transactionType),
                nullToEmpty(transactionNameTemplate), nullToEmpty(enabledProperty),
                nullToEmpty(spanEnabledProperty));
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
}

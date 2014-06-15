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

    private final String type;
    private final String methodName;
    private final ImmutableList<String> methodArgTypes;
    private final String methodReturnType;
    private final ImmutableList<MethodModifier> methodModifiers;
    private final String traceMetric;
    private final String spanText;
    @Nullable
    private final Long spanStackTraceThresholdMillis;
    private final boolean spanIgnoreSameNested;
    private final String transactionName;
    private final boolean background;

    // enabledProperty and spanEnabledProperty are for plugin authors
    private final String enabledProperty;
    private final String spanEnabledProperty;

    private final String version;

    @VisibleForTesting
    public PointcutConfig(String type, String methodName, List<String> methodArgTypes,
            String methodReturnType, List<MethodModifier> methodModifiers, String traceMetric,
            String spanText, @Nullable Long spanStackTraceThresholdMillis,
            boolean spanIgnoreSameNested, String transactionName, boolean background,
            String enabledProperty, String spanEnabledProperty) {
        this.type = type;
        this.methodName = methodName;
        this.methodArgTypes = ImmutableList.copyOf(methodArgTypes);
        this.methodReturnType = methodReturnType;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.traceMetric = traceMetric;
        this.spanText = spanText;
        this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        this.spanIgnoreSameNested = spanIgnoreSameNested;
        this.transactionName = transactionName;
        this.background = background;
        this.enabledProperty = enabledProperty;
        this.spanEnabledProperty = spanEnabledProperty;
        version = VersionHashes.sha1(type, methodName, methodArgTypes, methodReturnType,
                methodModifiers, traceMetric, spanText, spanStackTraceThresholdMillis,
                spanIgnoreSameNested, transactionName, background, enabledProperty,
                spanEnabledProperty);
    }

    public String getType() {
        return type;
    }

    public String getMethodName() {
        return methodName;
    }

    public ImmutableList<String> getMethodArgTypes() {
        return methodArgTypes;
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

    public String getTransactionName() {
        return transactionName;
    }

    public boolean isBackground() {
        return background;
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
        return !spanText.isEmpty();
    }

    @JsonIgnore
    public boolean isTrace() {
        return !transactionName.isEmpty();
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
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        List<String> methodArgTypes =
                checkNotNullItemsForProperty(uncheckedMethodArgTypes, "methodArgTypes");
        List<MethodModifier> methodModifiers =
                checkNotNullItemsForProperty(uncheckedMethodModifiers, "methodModifiers");
        checkRequiredProperty(type, "type");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodReturnType, "methodReturnType");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new PointcutConfig(type, methodName, nullToEmpty(methodArgTypes), methodReturnType,
                nullToEmpty(methodModifiers), nullToEmpty(traceMetric), nullToEmpty(spanText),
                spanStackTraceThresholdMillis, nullToFalse(spanIgnoreSameNested),
                nullToEmpty(transactionName), nullToFalse(background),
                nullToEmpty(enabledProperty), nullToEmpty(spanEnabledProperty));
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
}

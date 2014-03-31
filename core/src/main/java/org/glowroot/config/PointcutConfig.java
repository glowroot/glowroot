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

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.config.JsonViews.UiView;

import static com.google.common.base.Strings.nullToEmpty;
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

    private final String typeName;
    private final String methodName;
    private final ImmutableList<String> methodArgTypeNames;
    private final String methodReturnTypeName;
    private final ImmutableList<MethodModifier> methodModifiers;
    private final String metricName;
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
    public PointcutConfig(String typeName, String methodName, List<String> methodArgTypeNames,
            String methodReturnTypeName, List<MethodModifier> methodModifiers, String metricName,
            String spanText, @Nullable Long spanStackTraceThresholdMillis,
            boolean spanIgnoreSameNested, String transactionName, boolean background,
            String enabledProperty, String spanEnabledProperty) {
        this.typeName = typeName;
        this.methodName = methodName;
        this.methodArgTypeNames = ImmutableList.copyOf(methodArgTypeNames);
        this.methodReturnTypeName = methodReturnTypeName;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.metricName = metricName;
        this.spanText = spanText;
        this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        this.spanIgnoreSameNested = spanIgnoreSameNested;
        this.transactionName = transactionName;
        this.background = background;
        this.enabledProperty = enabledProperty;
        this.spanEnabledProperty = spanEnabledProperty;
        version = VersionHashes.sha1(typeName, methodName, methodArgTypeNames,
                methodReturnTypeName, methodModifiers, metricName, spanText,
                spanStackTraceThresholdMillis, spanIgnoreSameNested, transactionName, background,
                enabledProperty, spanEnabledProperty);
    }

    public String getTypeName() {
        return typeName;
    }

    public String getMethodName() {
        return methodName;
    }

    public ImmutableList<String> getMethodArgTypeNames() {
        return methodArgTypeNames;
    }

    public String getMethodReturnTypeName() {
        return methodReturnTypeName;
    }

    public ImmutableList<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public String getMetricName() {
        return metricName;
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

    @JsonIgnore
    public boolean isMetric() {
        return !metricName.isEmpty();
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
            @JsonProperty("typeName") @Nullable String typeName,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodArgTypeNames") @Nullable List<String> methodArgTypeNames,
            @JsonProperty("methodReturnTypeName") @Nullable String methodReturnTypeName,
            @JsonProperty("methodModifiers") @Nullable List<MethodModifier> methodModifiers,
            @JsonProperty("metricName") @Nullable String metricName,
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
        checkRequiredProperty(typeName, "typeName");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodReturnTypeName, "methodReturnTypeName");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new PointcutConfig(typeName, methodName, nullToEmpty(methodArgTypeNames),
                methodReturnTypeName, nullToEmpty(methodModifiers), nullToEmpty(metricName),
                nullToEmpty(spanText), spanStackTraceThresholdMillis,
                nullToFalse(spanIgnoreSameNested), nullToEmpty(transactionName),
                nullToFalse(background), nullToEmpty(enabledProperty),
                nullToEmpty(spanEnabledProperty));
    }

    /*@Pure*/
    @Override
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
                .add("spanIgnoreSameNested", spanIgnoreSameNested)
                .add("transactionName", transactionName)
                .add("background", background)
                .add("enabledProperty", enabledProperty)
                .add("spanEnabledProperty", spanEnabledProperty)
                .add("version", version)
                .toString();
    }
}

/**
 * Copyright 2013 the original author or authors.
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
package io.informant.container.config;

import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfig {

    private ImmutableList<CaptureItem> captureItems;
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
    private String spanTemplate;
    @Nullable
    private String traceGrouping;

    // null for new PointcutConfig records that haven't been sent to server yet
    @Nullable
    private final String version;

    // used to create new PointcutConfig records that haven't been sent to server yet
    public PointcutConfig() {
        captureItems = ImmutableList.of();
        methodArgTypeNames = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        version = null;
    }

    public PointcutConfig(String version) {
        captureItems = ImmutableList.of();
        methodArgTypeNames = ImmutableList.of();
        methodModifiers = ImmutableList.of();
        this.version = version;
    }

    public ImmutableList<CaptureItem> getCaptureItems() {
        return captureItems;
    }

    public void setCaptureItems(@ReadOnly List<CaptureItem> captureItems) {
        this.captureItems = ImmutableList.copyOf(captureItems);
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
    public String getSpanTemplate() {
        return spanTemplate;
    }

    public void setSpanTemplate(@Nullable String spanTemplate) {
        this.spanTemplate = spanTemplate;
    }

    @Nullable
    public String getTraceGrouping() {
        return traceGrouping;
    }

    public void setTraceGrouping(@Nullable String traceGrouping) {
        this.traceGrouping = traceGrouping;
    }

    // JsonIgnore so it won't get sent to the server
    @JsonIgnore
    @Nullable
    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PointcutConfig) {
            PointcutConfig that = (PointcutConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(captureItems, that.captureItems)
                    && Objects.equal(typeName, that.typeName)
                    && Objects.equal(methodName, that.methodName)
                    && Objects.equal(methodArgTypeNames, that.methodArgTypeNames)
                    && Objects.equal(methodReturnTypeName, that.methodReturnTypeName)
                    && Objects.equal(methodModifiers, that.methodModifiers)
                    && Objects.equal(metricName, that.metricName)
                    && Objects.equal(spanTemplate, that.spanTemplate)
                    && Objects.equal(traceGrouping, that.traceGrouping);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(captureItems, typeName, methodName, methodArgTypeNames,
                methodReturnTypeName, methodModifiers, metricName, spanTemplate, traceGrouping);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("captureItems", captureItems)
                .add("typeName", typeName)
                .add("methodName", methodName)
                .add("methodArgTypeNames", methodArgTypeNames)
                .add("methodReturnTypeName", methodReturnTypeName)
                .add("methodModifiers", methodModifiers)
                .add("metricName", metricName)
                .add("spanTemplate", spanTemplate)
                .add("traceGrouping", traceGrouping)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static PointcutConfig readValue(
            @JsonProperty("captureItems") @Nullable List<CaptureItem> captureItems,
            @JsonProperty("typeName") @Nullable String typeName,
            @JsonProperty("methodName") @Nullable String methodName,
            @JsonProperty("methodArgTypeNames") @Nullable List<String> methodArgTypeNames,
            @JsonProperty("methodReturnTypeName") @Nullable String methodReturnTypeName,
            @JsonProperty("methodModifiers") @Nullable List<MethodModifier> methodModifiers,
            @JsonProperty("metricName") @Nullable String metricName,
            @JsonProperty("spanTemplate") @Nullable String spanTemplate,
            @JsonProperty("traceGrouping") @Nullable String traceGrouping,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(captureItems, "captureItems");
        checkRequiredProperty(typeName, "typeName");
        checkRequiredProperty(methodName, "methodName");
        checkRequiredProperty(methodArgTypeNames, "methodArgTypeNames");
        checkRequiredProperty(methodReturnTypeName, "methodReturnTypeName");
        checkRequiredProperty(methodModifiers, "methodModifiers");
        checkRequiredProperty(metricName, "metricName");
        checkRequiredProperty(version, "version");
        PointcutConfig config = new PointcutConfig(version);
        config.setCaptureItems(captureItems);
        config.setTypeName(typeName);
        config.setMethodName(methodName);
        config.setMethodArgTypeNames(methodArgTypeNames);
        config.setMethodReturnTypeName(methodReturnTypeName);
        config.setMethodModifiers(methodModifiers);
        config.setMetricName(metricName);
        config.setSpanTemplate(spanTemplate);
        config.setTraceGrouping(traceGrouping);
        return config;
    }

    public enum CaptureItem {
        METRIC, SPAN, TRACE;
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC, ABSTRACT;
    }
}

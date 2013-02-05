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
package io.informant.core.config;

import io.informant.api.weaving.MethodModifier;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Immutable structure to hold an adhoc span/metric pointcut.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PointcutConfig {

    // serialize nulls so that all properties will be listed in config.json (for humans)
    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    private final ImmutableList<CaptureItem> captureItems;
    private final String typeName;
    private final String methodName;
    private final ImmutableList<String> methodArgTypeNames;
    private final String methodReturnTypeName;
    private final ImmutableList<MethodModifier> methodModifiers;
    private final String metricName;
    private final String spanTemplate;

    static PointcutConfig fromJson(JsonObject configObject) throws JsonSyntaxException {
        return gson.fromJson(configObject, PointcutConfig.Builder.class).build();
    }

    private PointcutConfig(@ReadOnly List<CaptureItem> captureItems, String typeName,
            String methodName, @ReadOnly List<String> methodArgTypeNames,
            String methodReturnTypeName, @ReadOnly List<MethodModifier> methodModifiers,
            String metricName, String spanTemplate) {
        this.captureItems = ImmutableList.copyOf(captureItems);
        this.typeName = typeName;
        this.methodName = methodName;
        this.methodArgTypeNames = ImmutableList.copyOf(methodArgTypeNames);
        this.methodReturnTypeName = methodReturnTypeName;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.metricName = metricName;
        this.spanTemplate = spanTemplate;
    }

    public JsonObject toJson() {
        return gson.toJsonTree(this).getAsJsonObject();
    }

    public JsonObject toJsonWithUniqueHash() {
        JsonObject configObject = gson.toJsonTree(this).getAsJsonObject();
        configObject.addProperty("uniqueHash", getUniqueHash());
        return configObject;
    }

    public String getUniqueHash() {
        return Hashing.md5().hashString(toJson().toString()).toString();
    }

    public ImmutableList<CaptureItem> getCaptureItems() {
        return captureItems;
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

    @Nullable
    public String getMetricName() {
        return metricName;
    }

    @Nullable
    public String getSpanTemplate() {
        return spanTemplate;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("uniqueHash", getUniqueHash())
                .add("captureItems", captureItems)
                .add("typeName", typeName)
                .add("methodName", methodName)
                .add("methodArgTypeNames", methodArgTypeNames)
                .add("methodReturnTypeName", methodReturnTypeName)
                .add("methodModifiers", methodModifiers)
                .add("metricName", metricName)
                .add("spanTemplate", spanTemplate)
                .toString();
    }

    @Immutable
    public enum CaptureItem {
        METRIC, SPAN, TRACE;
    }

    public static class Builder {

        @ReadOnly
        private List<CaptureItem> captureItems = ImmutableList.of();
        @Nullable
        private String typeName;
        @Nullable
        private String methodName;
        @ReadOnly
        private List<String> methodArgTypeNames = ImmutableList.of();
        @Nullable
        private String methodReturnTypeName;
        @ReadOnly
        private List<MethodModifier> methodModifiers = ImmutableList.of();
        @Nullable
        private String metricName;
        @Nullable
        private String spanTemplate;

        private Builder() {}

        public Builder captureItems(@ReadOnly List<CaptureItem> captureItems) {
            this.captureItems = captureItems;
            return this;
        }
        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }
        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }
        public Builder methodArgTypeNames(@ReadOnly List<String> methodArgTypeNames) {
            this.methodArgTypeNames = methodArgTypeNames;
            return this;
        }
        public Builder methodReturnTypeName(String methodReturnTypeName) {
            this.methodReturnTypeName = methodReturnTypeName;
            return this;
        }
        public Builder methodModifiers(@ReadOnly List<MethodModifier> methodModifiers) {
            this.methodModifiers = methodModifiers;
            return this;
        }
        public Builder metricName(String metricName) {
            this.metricName = metricName;
            return this;
        }
        public Builder spanTemplate(String spanTemplate) {
            this.spanTemplate = spanTemplate;
            return this;
        }
        public PointcutConfig build() {
            if (typeName == null) {
                throw new NullPointerException("Call to typeName() is required");
            }
            if (methodName == null) {
                throw new NullPointerException("Call to methodName() is required");
            }
            if (methodReturnTypeName == null) {
                throw new NullPointerException("Call to methodReturnTypeName() is required");
            }
            if (metricName == null) {
                throw new NullPointerException("Call to metricName() is required");
            }
            if (spanTemplate == null) {
                throw new NullPointerException("Call to spanTemplate() is required");
            }
            return new PointcutConfig(captureItems, typeName, methodName, methodArgTypeNames,
                    methodReturnTypeName, methodModifiers, metricName, spanTemplate);
        }
    }
}

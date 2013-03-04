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
package io.informant.config;

import io.informant.api.weaving.MethodModifier;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

/**
 * Immutable structure to hold an adhoc span/metric pointcut.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@JsonDeserialize(builder = PointcutConfig.Builder.class)
public class PointcutConfig {

    private final ImmutableList<CaptureItem> captureItems;
    private final String typeName;
    private final String methodName;
    private final ImmutableList<String> methodArgTypeNames;
    private final String methodReturnTypeName;
    private final ImmutableList<MethodModifier> methodModifiers;
    @Nullable
    private final String metricName;
    @Nullable
    private final String spanTemplate;
    private final String version;

    private PointcutConfig(@ReadOnly List<CaptureItem> captureItems, String typeName,
            String methodName, @ReadOnly List<String> methodArgTypeNames,
            String methodReturnTypeName, @ReadOnly List<MethodModifier> methodModifiers,
            @Nullable String metricName, @Nullable String spanTemplate, String version) {
        this.captureItems = ImmutableList.copyOf(captureItems);
        this.typeName = typeName;
        this.methodName = methodName;
        this.methodArgTypeNames = ImmutableList.copyOf(methodArgTypeNames);
        this.methodReturnTypeName = methodReturnTypeName;
        this.methodModifiers = ImmutableList.copyOf(methodModifiers);
        this.metricName = metricName;
        this.spanTemplate = spanTemplate;
        this.version = version;
    }

    @Immutable
    public List<CaptureItem> getCaptureItems() {
        return captureItems;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getMethodName() {
        return methodName;
    }

    @Immutable
    public List<String> getMethodArgTypeNames() {
        return methodArgTypeNames;
    }

    public String getMethodReturnTypeName() {
        return methodReturnTypeName;
    }

    @Immutable
    public List<MethodModifier> getMethodModifiers() {
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

    @JsonView(WithVersionJsonView.class)
    public String getVersion() {
        return version;
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
                .add("version", version)
                .toString();
    }

    @Immutable
    public enum CaptureItem {
        METRIC, SPAN, TRACE;
    }

    @JsonPOJOBuilder(withPrefix = "")
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

        @VisibleForTesting
        public Builder() {}

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

        public Builder metricName(@Nullable String metricName) {
            this.metricName = metricName;
            return this;
        }

        public Builder spanTemplate(@Nullable String spanTemplate) {
            this.spanTemplate = spanTemplate;
            return this;
        }

        public PointcutConfig build() {
            Preconditions.checkNotNull(typeName);
            Preconditions.checkNotNull(methodName);
            Preconditions.checkNotNull(methodReturnTypeName);
            String version = buildVersion();
            return new PointcutConfig(captureItems, typeName, methodName, methodArgTypeNames,
                    methodReturnTypeName, methodModifiers, metricName, spanTemplate, version);
        }

        private String buildVersion() {
            Preconditions.checkNotNull(typeName);
            Preconditions.checkNotNull(methodName);
            Preconditions.checkNotNull(methodReturnTypeName);
            Hasher hasher = Hashing.sha1().newHasher();
            for (CaptureItem captureItem : captureItems) {
                hasher.putString(captureItem.name());
            }
            hasher.putString(typeName);
            hasher.putString(methodName);
            for (String methodArgTypeName : methodArgTypeNames) {
                hasher.putString(methodArgTypeName);
            }
            hasher.putString(methodReturnTypeName);
            for (MethodModifier methodModifier : methodModifiers) {
                hasher.putString(methodModifier.name());
            }
            if (metricName == null) {
                hasher.putInt(-1);
            } else {
                hasher.putString(metricName);
                hasher.putInt(metricName.length());
            }
            if (spanTemplate == null) {
                hasher.putInt(-1);
            } else {
                hasher.putString(spanTemplate);
                hasher.putInt(spanTemplate.length());
            }
            return hasher.hash().toString();
        }
    }
}

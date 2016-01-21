/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.common.config;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.immutables.value.Value;

@Value.Immutable
public abstract class InstrumentationConfig {

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String className() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String classAnnotation() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String methodDeclaringClassName() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String methodName() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String methodAnnotation() {
        return "";
    }

    // empty methodParameterTypes means match no-arg methods only
    public abstract ImmutableList<String> methodParameterTypes();

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String methodReturnType() {
        return "";
    }

    // currently unused, but will have a purpose someday, e.g. to capture all public methods
    @JsonInclude(value = Include.NON_EMPTY)
    public abstract ImmutableList<MethodModifier> methodModifiers();

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String nestingGroup() {
        return "";
    }

    // need to write zero since it is treated different from null
    // (although @Pointcut default priority is zero so ends up being the same thing)
    @JsonInclude(value = Include.NON_NULL)
    public abstract @Nullable Integer priority();

    public abstract CaptureKind captureKind();

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String transactionType() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String transactionNameTemplate() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String transactionUserTemplate() {
        return "";
    }

    @JsonInclude(value = Include.NON_EMPTY)
    public abstract Map<String, String> transactionAttributeTemplates();

    // need to write zero since it is treated different from null
    @JsonInclude(value = Include.NON_NULL)
    public abstract @Nullable Integer transactionSlowThresholdMillis();

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String traceEntryMessageTemplate() {
        return "";
    }

    // need to write zero since it is treated different from null
    @JsonInclude(value = Include.NON_NULL)
    public abstract @Nullable Integer traceEntryStackThresholdMillis();

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public boolean traceEntryCaptureSelfNested() {
        return false;
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String timerName() {
        return "";
    }

    // this is only for plugin authors (to be used in glowroot.plugin.json)
    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String enabledProperty() {
        return "";
    }

    // this is only for plugin authors (to be used in glowroot.plugin.json)
    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String traceEntryEnabledProperty() {
        return "";
    }

    // this uses Guava's Hashing instead of json/md5 "cheat" like other configs, because it is used
    // by weaving, and using json/md5 would require pre-initialization tons of Jackson classes
    // see PreInitializeWeavingClassesTest.java
    @JsonIgnore
    @Value.Derived
    public String version() {
        Hasher hasher = Hashing.md5().newHasher()
                .putString(className(), Charsets.UTF_8)
                .putString(classAnnotation(), Charsets.UTF_8)
                .putString(methodDeclaringClassName(), Charsets.UTF_8)
                .putString(methodName(), Charsets.UTF_8)
                .putString(methodAnnotation(), Charsets.UTF_8)
                .putInt(methodParameterTypes().size());
        for (String methodParameterType : methodParameterTypes()) {
            hasher.putString(methodParameterType, Charsets.UTF_8);
        }
        hasher.putString(methodReturnType(), Charsets.UTF_8);
        hasher.putInt(methodModifiers().size());
        for (MethodModifier methodModifier : methodModifiers()) {
            hasher.putString(methodModifier.name(), Charsets.UTF_8);
        }
        Integer priority = priority();
        if (priority != null) {
            hasher.putInt(priority);
        }
        hasher.putString(captureKind().name(), Charsets.UTF_8);
        hasher.putString(transactionType(), Charsets.UTF_8);
        hasher.putString(transactionNameTemplate(), Charsets.UTF_8);
        hasher.putString(transactionUserTemplate(), Charsets.UTF_8);
        hasher.putInt(transactionAttributeTemplates().size());
        for (Entry<String, String> entry : transactionAttributeTemplates().entrySet()) {
            hasher.putString(entry.getKey(), Charsets.UTF_8);
            hasher.putString(entry.getValue(), Charsets.UTF_8);
        }
        Integer transactionSlowThresholdMillis = transactionSlowThresholdMillis();
        if (transactionSlowThresholdMillis != null) {
            hasher.putInt(transactionSlowThresholdMillis);
        }
        hasher.putString(traceEntryMessageTemplate(), Charsets.UTF_8);
        Integer traceEntryStackThresholdMillis = traceEntryStackThresholdMillis();
        if (traceEntryStackThresholdMillis != null) {
            hasher.putInt(traceEntryStackThresholdMillis);
        }
        hasher.putBoolean(traceEntryCaptureSelfNested());
        hasher.putString(timerName(), Charsets.UTF_8);
        hasher.putString(enabledProperty(), Charsets.UTF_8);
        hasher.putString(traceEntryEnabledProperty(), Charsets.UTF_8);
        return hasher.hash().toString();
    }

    @JsonIgnore
    @Value.Derived
    public boolean isTimerOrGreater() {
        return captureKind() == CaptureKind.TIMER || captureKind() == CaptureKind.TRACE_ENTRY
                || captureKind() == CaptureKind.TRANSACTION;
    }

    @JsonIgnore
    @Value.Derived
    public boolean isTraceEntryOrGreater() {
        return captureKind() == CaptureKind.TRACE_ENTRY || captureKind() == CaptureKind.TRANSACTION;
    }

    @JsonIgnore
    @Value.Derived
    public boolean isTransaction() {
        return captureKind() == CaptureKind.TRANSACTION;
    }

    @JsonIgnore
    @Value.Derived
    public ImmutableList<String> validationErrors() {
        List<String> errors = Lists.newArrayList();
        if (className().isEmpty() && classAnnotation().isEmpty()) {
            errors.add("className and classAnnotation are both empty");
        }
        if (methodName().isEmpty() && methodAnnotation().isEmpty()) {
            errors.add("methodName and methodAnnotation are both empty");
        }
        if (isTimerOrGreater() && timerName().isEmpty()) {
            errors.add("timerName is empty");
        }
        if (captureKind() == CaptureKind.TRACE_ENTRY && traceEntryMessageTemplate().isEmpty()) {
            errors.add("traceEntryMessageTemplate is empty");
        }
        if (isTransaction() && transactionType().isEmpty()) {
            errors.add("transactionType is empty");
        }
        if (isTransaction() && transactionNameTemplate().isEmpty()) {
            errors.add("transactionNameTemplate is empty");
        }
        if (!timerName().matches("[a-zA-Z0-9 ]*")) {
            errors.add("timerName contains invalid characters: " + timerName());
        }
        return ImmutableList.copyOf(errors);
    }

    public enum MethodModifier {
        PUBLIC, STATIC, NOT_STATIC;
    }

    public enum CaptureKind {
        TRANSACTION, TRACE_ENTRY, TIMER, OTHER
    }
}

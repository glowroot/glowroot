/*
 * Copyright 2013-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import org.immutables.value.Value;

import org.glowroot.api.weaving.MethodModifier;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@JsonSerialize
public abstract class InstrumentationConfigBase {

    public static final Ordering<InstrumentationConfig> ordering =
            new InstrumentationConfigOrdering();

    public abstract String className();

    public abstract String methodName();

    // empty methodParameterTypes means match no-arg methods only
    public abstract ImmutableList<String> methodParameterTypes();

    @Value.Default
    public String methodReturnType() {
        return "";
    }

    // currently unused, but will have a purpose soon, e.g. to capture all public methods
    public abstract ImmutableList<MethodModifier> methodModifiers();

    public abstract CaptureKind captureKind();

    @Value.Default
    public String timerName() {
        return "";
    }

    @Value.Default
    public String traceEntryTemplate() {
        return "";
    }

    public abstract @Nullable Long traceEntryStackThresholdMillis();

    @Value.Default
    public boolean traceEntryCaptureSelfNested() {
        return false;
    }

    @Value.Default
    public String transactionType() {
        return "";
    }

    @Value.Default
    public String transactionNameTemplate() {
        return "";
    }

    @Value.Default
    public String transactionUserTemplate() {
        return "";
    }

    public abstract Map<String, String> transactionCustomAttributeTemplates();

    public abstract @Nullable Long traceStoreThresholdMillis();

    // enabledProperty and traceEntryEnabledProperty are for plugin authors
    @Value.Default
    public String enabledProperty() {
        return "";
    }

    @Value.Default
    public String traceEntryEnabledProperty() {
        return "";
    }

    @JsonIgnore
    @Value.Derived
    public String version() {
        return Versions.getVersion(this);
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
        if (className().isEmpty()) {
            errors.add("className is empty");
        }
        if (methodName().isEmpty()) {
            errors.add("methodName is empty");
        }
        if (isTimerOrGreater() && timerName().isEmpty()) {
            errors.add("timerName is empty");
        }
        if (captureKind() == CaptureKind.TRACE_ENTRY && traceEntryTemplate().isEmpty()) {
            errors.add("traceEntryTemplate is empty");
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

    private static class InstrumentationConfigOrdering extends Ordering<InstrumentationConfig> {
        @Override
        public int compare(@Nullable InstrumentationConfig left,
                @Nullable InstrumentationConfig right) {
            checkNotNull(left);
            checkNotNull(right);
            int compare = left.className().compareToIgnoreCase(right.className());
            if (compare != 0) {
                return compare;
            }
            compare = left.methodName().compareToIgnoreCase(right.methodName());
            if (compare != 0) {
                return compare;
            }
            compare = Ints.compare(left.methodParameterTypes().size(),
                    right.methodParameterTypes().size());
            if (compare != 0) {
                return compare;
            }
            List<String> leftParameterTypes = left.methodParameterTypes();
            List<String> rightParameterTypes = right.methodParameterTypes();
            for (int i = 0; i < leftParameterTypes.size(); i++) {
                compare = leftParameterTypes.get(i).compareToIgnoreCase(rightParameterTypes.get(i));
                if (compare != 0) {
                    return compare;
                }
            }
            return 0;
        }
    }
}

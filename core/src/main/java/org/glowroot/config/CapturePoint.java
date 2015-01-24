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

import com.google.common.base.Charsets;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.common.Marshaling2;
import org.glowroot.config.MarshalingRoutines.LowercaseMarshaling;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
@Json.Import(MarshalingRoutines.class)
public abstract class CapturePoint {

    public static final Ordering<CapturePoint> defaultOrdering = new CapturePointOrdering();

    public abstract String className();
    public abstract String methodName();
    // empty methodParameterTypes means match no-arg methods only
    @Json.ForceEmpty
    public abstract List<String> methodParameterTypes();
    @Value.Default
    public String methodReturnType() {
        return "";
    }
    // currently unused, but will have a purpose soon, e.g. to capture all public methods
    public abstract List<MethodModifier> methodModifiers();
    public abstract CaptureKind captureKind();
    @Value.Default
    public String metricName() {
        return "";
    }
    @Value.Default
    public String traceEntryTemplate() {
        return "";
    }
    @Json.ForceEmpty
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
    @Json.ForceEmpty
    public abstract Map<String, String> transactionCustomAttributeTemplates();
    @Json.ForceEmpty
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

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling2.toJson(this), Charsets.UTF_8).toString();
    }

    public boolean isMetricOrGreater() {
        return captureKind() == CaptureKind.METRIC || captureKind() == CaptureKind.TRACE_ENTRY
                || captureKind() == CaptureKind.TRANSACTION;
    }

    public boolean isTraceEntryOrGreater() {
        return captureKind() == CaptureKind.TRACE_ENTRY || captureKind() == CaptureKind.TRANSACTION;
    }

    public boolean isTransaction() {
        return captureKind() == CaptureKind.TRANSACTION;
    }

    public static enum CaptureKind implements LowercaseMarshaling {
        METRIC, TRACE_ENTRY, TRANSACTION, OTHER
    }

    private static class CapturePointOrdering extends Ordering<CapturePoint> {
        @Override
        public int compare(@Nullable CapturePoint left, @Nullable CapturePoint right) {
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
            List<String> rightParameterTypes = left.methodParameterTypes();
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

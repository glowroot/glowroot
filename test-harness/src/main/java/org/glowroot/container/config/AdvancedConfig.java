/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AdvancedConfig {

    private boolean traceMetricWrapperMethods;
    private boolean warnOnSpanOutsideTrace;
    private boolean weavingDisabled;

    private final String version;

    public AdvancedConfig(String version) {
        this.version = version;
    }

    public boolean isTraceMetricWrapperMethods() {
        return traceMetricWrapperMethods;
    }

    public void setTraceMetricWrapperMethods(boolean traceMetricWrapperMethods) {
        this.traceMetricWrapperMethods = traceMetricWrapperMethods;
    }

    public boolean isWarnOnSpanOutsideTrace() {
        return warnOnSpanOutsideTrace;
    }

    public void setWarnOnSpanOutsideTrace(boolean warnOnSpanOutsideTrace) {
        this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
    }

    public boolean isWeavingDisabled() {
        return weavingDisabled;
    }

    public void setWeavingDisabled(boolean weavingDisabled) {
        this.weavingDisabled = weavingDisabled;
    }

    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof AdvancedConfig) {
            AdvancedConfig that = (AdvancedConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(traceMetricWrapperMethods, that.traceMetricWrapperMethods)
                    && Objects.equal(warnOnSpanOutsideTrace, that.warnOnSpanOutsideTrace)
                    && Objects.equal(weavingDisabled, that.weavingDisabled);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(traceMetricWrapperMethods, warnOnSpanOutsideTrace, weavingDisabled);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("traceMetricWrapperMethods", traceMetricWrapperMethods)
                .add("warnOnSpanOutsideTrace", warnOnSpanOutsideTrace)
                .add("weavingDisabled", weavingDisabled)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static AdvancedConfig readValue(
            @JsonProperty("traceMetricWrapperMethods") @Nullable Boolean traceMetricWrapperMethods,
            @JsonProperty("warnOnSpanOutsideTrace") @Nullable Boolean warnOnSpanOutsideTrace,
            @JsonProperty("weavingDisabled") @Nullable Boolean weavingDisabled,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(traceMetricWrapperMethods, "traceMetricWrapperMethods");
        checkRequiredProperty(warnOnSpanOutsideTrace, "warnOnSpanOutsideTrace");
        checkRequiredProperty(weavingDisabled, "weavingDisabled");
        checkRequiredProperty(version, "version");
        AdvancedConfig config = new AdvancedConfig(version);
        config.setTraceMetricWrapperMethods(traceMetricWrapperMethods);
        config.setWarnOnSpanOutsideTrace(warnOnSpanOutsideTrace);
        config.setWeavingDisabled(weavingDisabled);
        return config;
    }
}

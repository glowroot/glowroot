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
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AdvancedConfig {

    private boolean metricWrapperMethods;
    private int immediatePartialStoreThresholdSeconds;
    private int maxEntriesPerTrace;
    private boolean captureThreadInfo;
    private boolean captureGcInfo;
    private int mbeanGaugeNotFoundDelaySeconds;
    private int internalQueryTimeoutSeconds;

    private final String version;

    public AdvancedConfig(String version) {
        this.version = version;
    }

    public boolean isMetricWrapperMethods() {
        return metricWrapperMethods;
    }

    public void setMetricWrapperMethods(boolean metricWrapperMethods) {
        this.metricWrapperMethods = metricWrapperMethods;
    }

    public int getImmediatePartialStoreThresholdSeconds() {
        return immediatePartialStoreThresholdSeconds;
    }

    public void setImmediatePartialStoreThresholdSeconds(
            int immediatePartialStoreThresholdSeconds) {
        this.immediatePartialStoreThresholdSeconds = immediatePartialStoreThresholdSeconds;
    }

    public int getMaxEntriesPerTrace() {
        return maxEntriesPerTrace;
    }

    public void setMaxEntriesPerTrace(int maxEntriesPerTrace) {
        this.maxEntriesPerTrace = maxEntriesPerTrace;
    }

    public boolean isCaptureThreadInfo() {
        return captureThreadInfo;
    }

    public void setCaptureThreadInfo(boolean captureThreadInfo) {
        this.captureThreadInfo = captureThreadInfo;
    }

    public boolean isCaptureGcInfo() {
        return captureGcInfo;
    }

    public void setCaptureGcInfo(boolean captureGcInfo) {
        this.captureGcInfo = captureGcInfo;
    }

    public int getMBeanGaugeNotFoundDelaySeconds() {
        return mbeanGaugeNotFoundDelaySeconds;
    }

    public void setMBeanGaugeNotFoundDelaySeconds(int mbeanGaugeNotFoundDelaySeconds) {
        this.mbeanGaugeNotFoundDelaySeconds = mbeanGaugeNotFoundDelaySeconds;
    }

    public int getInternalQueryTimeoutSeconds() {
        return internalQueryTimeoutSeconds;
    }

    public void setInternalQueryTimeoutSeconds(int internalQueryTimeoutSeconds) {
        this.internalQueryTimeoutSeconds = internalQueryTimeoutSeconds;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof AdvancedConfig) {
            AdvancedConfig that = (AdvancedConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(metricWrapperMethods, that.metricWrapperMethods)
                    && Objects.equal(immediatePartialStoreThresholdSeconds,
                            that.immediatePartialStoreThresholdSeconds)
                    && Objects.equal(maxEntriesPerTrace, that.maxEntriesPerTrace)
                    && Objects.equal(captureThreadInfo, that.captureThreadInfo)
                    && Objects.equal(captureGcInfo, that.captureGcInfo)
                    && Objects.equal(mbeanGaugeNotFoundDelaySeconds,
                            that.mbeanGaugeNotFoundDelaySeconds)
                    && Objects.equal(internalQueryTimeoutSeconds, that.internalQueryTimeoutSeconds);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(metricWrapperMethods, immediatePartialStoreThresholdSeconds,
                maxEntriesPerTrace, captureThreadInfo, captureGcInfo,
                mbeanGaugeNotFoundDelaySeconds, internalQueryTimeoutSeconds);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("metricWrapperMethods", metricWrapperMethods)
                .add("immediatePartialStoreThresholdSeconds", immediatePartialStoreThresholdSeconds)
                .add("maxEntriesPerTrace", maxEntriesPerTrace)
                .add("captureThreadInfo", captureThreadInfo)
                .add("captureGcInfo", captureGcInfo)
                .add("mbeanGaugeNotFoundDelaySeconds", mbeanGaugeNotFoundDelaySeconds)
                .add("internalQueryTimeoutSeconds", internalQueryTimeoutSeconds)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static AdvancedConfig readValue(
            @JsonProperty("metricWrapperMethods") @Nullable Boolean metricWrapperMethods,
            @JsonProperty("immediatePartialStoreThresholdSeconds") @Nullable Integer immediatePartialStoreThresholdSeconds,
            @JsonProperty("maxEntriesPerTrace") @Nullable Integer maxEntriesPerTrace,
            @JsonProperty("captureThreadInfo") @Nullable Boolean captureThreadInfo,
            @JsonProperty("captureGcInfo") @Nullable Boolean captureGcInfo,
            @JsonProperty("mbeanGaugeNotFoundDelaySeconds") @Nullable Integer mbeanGaugeNotFoundDelaySeconds,
            @JsonProperty("internalQueryTimeoutSeconds") @Nullable Integer internalQueryTimeoutSeconds,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(metricWrapperMethods, "metricWrapperMethods");
        checkRequiredProperty(immediatePartialStoreThresholdSeconds,
                "immediatePartialStoreThresholdSeconds");
        checkRequiredProperty(maxEntriesPerTrace, "maxEntriesPerTrace");
        checkRequiredProperty(captureThreadInfo, "captureThreadInfo");
        checkRequiredProperty(captureGcInfo, "captureGcInfo");
        checkRequiredProperty(mbeanGaugeNotFoundDelaySeconds, "mbeanGaugeNotFoundDelaySeconds");
        checkRequiredProperty(internalQueryTimeoutSeconds, "internalQueryTimeoutSeconds");
        checkRequiredProperty(version, "version");
        AdvancedConfig config = new AdvancedConfig(version);
        config.setMetricWrapperMethods(metricWrapperMethods);
        config.setImmediatePartialStoreThresholdSeconds(immediatePartialStoreThresholdSeconds);
        config.setMaxEntriesPerTrace(maxEntriesPerTrace);
        config.setCaptureThreadInfo(captureThreadInfo);
        config.setCaptureGcInfo(captureGcInfo);
        config.setMBeanGaugeNotFoundDelaySeconds(mbeanGaugeNotFoundDelaySeconds);
        config.setInternalQueryTimeoutSeconds(internalQueryTimeoutSeconds);
        return config;
    }
}

/*
 * Copyright 2011-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class AdvancedConfig {

    private boolean timerWrapperMethods;
    private boolean weavingTimer;
    private int immediatePartialStoreThresholdSeconds;
    private int maxTraceEntriesPerTransaction;
    private int maxStackTraceSamplesPerTransaction;
    private boolean captureThreadInfo;
    private boolean captureGcInfo;
    private int mbeanGaugeNotFoundDelaySeconds;
    private int internalQueryTimeoutSeconds;

    private final String version;

    private AdvancedConfig(String version) {
        this.version = version;
    }

    public boolean isTimerWrapperMethods() {
        return timerWrapperMethods;
    }

    public void setTimerWrapperMethods(boolean timerWrapperMethods) {
        this.timerWrapperMethods = timerWrapperMethods;
    }

    public boolean isWeavingTimer() {
        return weavingTimer;
    }

    public void setWeavingTimer(boolean weavingTimer) {
        this.weavingTimer = weavingTimer;
    }

    public int getImmediatePartialStoreThresholdSeconds() {
        return immediatePartialStoreThresholdSeconds;
    }

    public void setImmediatePartialStoreThresholdSeconds(
            int immediatePartialStoreThresholdSeconds) {
        this.immediatePartialStoreThresholdSeconds = immediatePartialStoreThresholdSeconds;
    }

    public int getMaxTraceEntriesPerTransaction() {
        return maxTraceEntriesPerTransaction;
    }

    public void setMaxTraceEntriesPerTransaction(int maxTraceEntriesPerTransaction) {
        this.maxTraceEntriesPerTransaction = maxTraceEntriesPerTransaction;
    }

    public int getMaxStackTraceSamplesPerTransaction() {
        return maxStackTraceSamplesPerTransaction;
    }

    public void setMaxStackTraceSamplesPerTransaction(int maxStackTraceSamplesPerTransaction) {
        this.maxStackTraceSamplesPerTransaction = maxStackTraceSamplesPerTransaction;
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
            return Objects.equal(timerWrapperMethods, that.timerWrapperMethods)
                    && Objects.equal(weavingTimer, that.weavingTimer)
                    && Objects.equal(immediatePartialStoreThresholdSeconds,
                            that.immediatePartialStoreThresholdSeconds)
                    && Objects.equal(maxTraceEntriesPerTransaction,
                            that.maxTraceEntriesPerTransaction)
                    && Objects.equal(maxStackTraceSamplesPerTransaction,
                            that.maxStackTraceSamplesPerTransaction)
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
        return Objects.hashCode(timerWrapperMethods, weavingTimer,
                immediatePartialStoreThresholdSeconds, maxTraceEntriesPerTransaction,
                maxStackTraceSamplesPerTransaction, captureThreadInfo, captureGcInfo,
                mbeanGaugeNotFoundDelaySeconds, internalQueryTimeoutSeconds);
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("timerWrapperMethods", timerWrapperMethods)
                .add("weavingTimer", weavingTimer)
                .add("immediatePartialStoreThresholdSeconds", immediatePartialStoreThresholdSeconds)
                .add("maxTraceEntriesPerTransaction", maxTraceEntriesPerTransaction)
                .add("maxStackTraceSamplesPerTransaction", maxStackTraceSamplesPerTransaction)
                .add("captureThreadInfo", captureThreadInfo)
                .add("captureGcInfo", captureGcInfo)
                .add("mbeanGaugeNotFoundDelaySeconds", mbeanGaugeNotFoundDelaySeconds)
                .add("internalQueryTimeoutSeconds", internalQueryTimeoutSeconds)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static AdvancedConfig readValue(
            @JsonProperty("timerWrapperMethods") @Nullable Boolean timerWrapperMethods,
            @JsonProperty("weavingTimer") @Nullable Boolean weavingTimer,
            @JsonProperty("immediatePartialStoreThresholdSeconds") @Nullable Integer immediatePartialStoreThresholdSeconds,
            @JsonProperty("maxTraceEntriesPerTransaction") @Nullable Integer maxTraceEntriesPerTransaction,
            @JsonProperty("maxStackTraceSamplesPerTransaction") @Nullable Integer maxStackTraceSamplesPerTransaction,
            @JsonProperty("captureThreadInfo") @Nullable Boolean captureThreadInfo,
            @JsonProperty("captureGcInfo") @Nullable Boolean captureGcInfo,
            @JsonProperty("mbeanGaugeNotFoundDelaySeconds") @Nullable Integer mbeanGaugeNotFoundDelaySeconds,
            @JsonProperty("internalQueryTimeoutSeconds") @Nullable Integer internalQueryTimeoutSeconds,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(timerWrapperMethods, "timerWrapperMethods");
        checkRequiredProperty(weavingTimer, "weavingTimer");
        checkRequiredProperty(immediatePartialStoreThresholdSeconds,
                "immediatePartialStoreThresholdSeconds");
        checkRequiredProperty(maxTraceEntriesPerTransaction, "maxTraceEntriesPerTransaction");
        checkRequiredProperty(maxStackTraceSamplesPerTransaction,
                "maxStackTraceSamplesPerTransaction");
        checkRequiredProperty(captureThreadInfo, "captureThreadInfo");
        checkRequiredProperty(captureGcInfo, "captureGcInfo");
        checkRequiredProperty(mbeanGaugeNotFoundDelaySeconds, "mbeanGaugeNotFoundDelaySeconds");
        checkRequiredProperty(internalQueryTimeoutSeconds, "internalQueryTimeoutSeconds");
        checkRequiredProperty(version, "version");
        AdvancedConfig config = new AdvancedConfig(version);
        config.setTimerWrapperMethods(timerWrapperMethods);
        config.setWeavingTimer(weavingTimer);
        config.setImmediatePartialStoreThresholdSeconds(immediatePartialStoreThresholdSeconds);
        config.setMaxTraceEntriesPerTransaction(maxTraceEntriesPerTransaction);
        config.setMaxStackTraceSamplesPerTransaction(maxStackTraceSamplesPerTransaction);
        config.setCaptureThreadInfo(captureThreadInfo);
        config.setCaptureGcInfo(captureGcInfo);
        config.setMBeanGaugeNotFoundDelaySeconds(mbeanGaugeNotFoundDelaySeconds);
        config.setInternalQueryTimeoutSeconds(internalQueryTimeoutSeconds);
        return config;
    }
}

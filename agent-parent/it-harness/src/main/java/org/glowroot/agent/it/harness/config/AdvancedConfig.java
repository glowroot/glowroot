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
package org.glowroot.agent.it.harness.config;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

public class AdvancedConfig {

    private boolean timerWrapperMethods;
    private boolean weavingTimer;
    private int immediatePartialStoreThresholdSeconds;
    private int maxAggregateTransactionsPerTransactionType;
    private int maxAggregateQueriesPerQueryType;
    private int maxTraceEntriesPerTransaction;
    private int maxStackTraceSamplesPerTransaction;
    private boolean captureThreadInfo;
    private boolean captureGcActivity;
    private int mbeanGaugeNotFoundDelaySeconds;

    private final String version;

    @JsonCreator
    private AdvancedConfig(@JsonProperty("version") String version) {
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

    public int getMaxAggregateTransactionsPerTransactionType() {
        return maxAggregateTransactionsPerTransactionType;
    }

    public void setMaxAggregateTransactionsPerTransactionType(
            int maxAggregateTransactionsPerTransactionType) {
        this.maxAggregateTransactionsPerTransactionType =
                maxAggregateTransactionsPerTransactionType;
    }

    public int getMaxAggregateQueriesPerQueryType() {
        return maxAggregateQueriesPerQueryType;
    }

    public void setMaxAggregateQueriesPerQueryType(int maxAggregateQueriesPerQueryType) {
        this.maxAggregateQueriesPerQueryType = maxAggregateQueriesPerQueryType;
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

    public boolean isCaptureGcActivity() {
        return captureGcActivity;
    }

    public void setCaptureGcActivity(boolean captureGcActivity) {
        this.captureGcActivity = captureGcActivity;
    }

    public int getMBeanGaugeNotFoundDelaySeconds() {
        return mbeanGaugeNotFoundDelaySeconds;
    }

    public void setMBeanGaugeNotFoundDelaySeconds(int mbeanGaugeNotFoundDelaySeconds) {
        this.mbeanGaugeNotFoundDelaySeconds = mbeanGaugeNotFoundDelaySeconds;
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
                    && Objects.equal(maxAggregateTransactionsPerTransactionType,
                            that.maxAggregateTransactionsPerTransactionType)
                    && Objects.equal(maxAggregateQueriesPerQueryType,
                            that.maxAggregateQueriesPerQueryType)
                    && Objects.equal(maxTraceEntriesPerTransaction,
                            that.maxTraceEntriesPerTransaction)
                    && Objects.equal(maxStackTraceSamplesPerTransaction,
                            that.maxStackTraceSamplesPerTransaction)
                    && Objects.equal(captureThreadInfo, that.captureThreadInfo)
                    && Objects.equal(captureGcActivity, that.captureGcActivity)
                    && Objects.equal(mbeanGaugeNotFoundDelaySeconds,
                            that.mbeanGaugeNotFoundDelaySeconds);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(timerWrapperMethods, weavingTimer,
                immediatePartialStoreThresholdSeconds, maxAggregateTransactionsPerTransactionType,
                maxAggregateQueriesPerQueryType, maxTraceEntriesPerTransaction,
                maxStackTraceSamplesPerTransaction, captureThreadInfo, captureGcActivity,
                mbeanGaugeNotFoundDelaySeconds);
    }
}

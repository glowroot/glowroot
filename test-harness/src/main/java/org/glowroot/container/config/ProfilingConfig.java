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
public class ProfilingConfig {

    private boolean enabled;
    private double transactionPercentage;
    private int intervalMillis;
    private int traceStoreThresholdOverrideMillis;

    private final String version;

    public ProfilingConfig(String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getTransactionPercentage() {
        return transactionPercentage;
    }

    public void setTransactionPercentage(double transactionPercentage) {
        this.transactionPercentage = transactionPercentage;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public int getTraceStoreThresholdOverrideMillis() {
        return traceStoreThresholdOverrideMillis;
    }

    public void setTraceStoreThresholdOverrideMillis(int traceStoreThresholdOverrideMillis) {
        this.traceStoreThresholdOverrideMillis = traceStoreThresholdOverrideMillis;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof ProfilingConfig) {
            ProfilingConfig that = (ProfilingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(transactionPercentage, that.transactionPercentage)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(traceStoreThresholdOverrideMillis,
                            that.traceStoreThresholdOverrideMillis);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, transactionPercentage, intervalMillis,
                traceStoreThresholdOverrideMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("transactionPercentage", transactionPercentage)
                .add("intervalMillis", intervalMillis)
                .add("traceStoreThresholdOverrideMillis", traceStoreThresholdOverrideMillis)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static ProfilingConfig readValue(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("transactionPercentage") @Nullable Double transactionPercentage,
            @JsonProperty("intervalMillis") @Nullable Integer intervalMillis,
            @JsonProperty("traceStoreThresholdOverrideMillis") @Nullable Integer traceStoreThresholdOverrideMillis,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(transactionPercentage, "transactionPercentage");
        checkRequiredProperty(intervalMillis, "intervalMillis");
        checkRequiredProperty(traceStoreThresholdOverrideMillis,
                "traceStoreThresholdOverrideMillis");
        checkRequiredProperty(version, "version");
        ProfilingConfig config = new ProfilingConfig(version);
        config.setEnabled(enabled);
        config.setTransactionPercentage(transactionPercentage);
        config.setIntervalMillis(intervalMillis);
        config.setTraceStoreThresholdOverrideMillis(traceStoreThresholdOverrideMillis);
        return config;
    }
}

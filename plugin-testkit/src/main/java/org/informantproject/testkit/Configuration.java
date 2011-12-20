/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.testkit;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Configuration {

    private CoreConfiguration coreConfiguration;

    public CoreConfiguration getCoreConfiguration() {
        return coreConfiguration;
    }

    public void setCoreConfiguration(CoreConfiguration coreConfiguration) {
        this.coreConfiguration = coreConfiguration;
    }

    public static class CoreConfiguration {
        private boolean enabled;
        private int thresholdMillis;
        private int stuckThresholdMillis;
        private int stackTraceInitialDelayMillis;
        private int stackTracePeriodMillis;
        private int maxSpansPerTrace;
        private boolean warnOnSpanOutsideTrace;
        private int metricPeriodMillis;
        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getThresholdMillis() {
            return thresholdMillis;
        }
        public void setThresholdMillis(int thresholdMillis) {
            this.thresholdMillis = thresholdMillis;
        }
        public int getStuckThresholdMillis() {
            return stuckThresholdMillis;
        }
        public void setStuckThresholdMillis(int stuckThresholdMillis) {
            this.stuckThresholdMillis = stuckThresholdMillis;
        }
        public int getStackTraceInitialDelayMillis() {
            return stackTraceInitialDelayMillis;
        }
        public void setStackTraceInitialDelayMillis(int stackTraceInitialDelayMillis) {
            this.stackTraceInitialDelayMillis = stackTraceInitialDelayMillis;
        }
        public int getStackTracePeriodMillis() {
            return stackTracePeriodMillis;
        }
        public void setStackTracePeriodMillis(int stackTracePeriodMillis) {
            this.stackTracePeriodMillis = stackTracePeriodMillis;
        }
        public int getMaxSpansPerTrace() {
            return maxSpansPerTrace;
        }
        public void setMaxSpansPerTrace(int maxSpansPerTrace) {
            this.maxSpansPerTrace = maxSpansPerTrace;
        }
        public boolean isWarnOnSpanOutsideTrace() {
            return warnOnSpanOutsideTrace;
        }
        public void setWarnOnSpanOutsideTrace(boolean warnOnSpanOutsideTrace) {
            this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
        }
        public int getMetricPeriodMillis() {
            return metricPeriodMillis;
        }
        public void setMetricPeriodMillis(int metricPeriodMillis) {
            this.metricPeriodMillis = metricPeriodMillis;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, thresholdMillis, stuckThresholdMillis,
                    stackTraceInitialDelayMillis, stackTracePeriodMillis, maxSpansPerTrace,
                    warnOnSpanOutsideTrace, metricPeriodMillis);
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CoreConfiguration)) {
                return false;
            }
            CoreConfiguration other = (CoreConfiguration) o;
            return Objects.equal(enabled, other.enabled)
                    && Objects.equal(thresholdMillis, other.thresholdMillis)
                    && Objects.equal(stuckThresholdMillis, other.stuckThresholdMillis)
                    && Objects.equal(stackTraceInitialDelayMillis,
                            other.stackTraceInitialDelayMillis)
                    && Objects.equal(stackTracePeriodMillis, other.stackTracePeriodMillis)
                    && Objects.equal(maxSpansPerTrace, other.maxSpansPerTrace)
                    && Objects.equal(warnOnSpanOutsideTrace, other.warnOnSpanOutsideTrace)
                    && Objects.equal(metricPeriodMillis, other.metricPeriodMillis);
        }
    }
}

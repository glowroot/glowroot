/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class CoarseProfilingConfig {

    private boolean enabled;
    private int initialDelayMillis;
    private int intervalMillis;
    private int totalSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInitialDelayMillis() {
        return initialDelayMillis;
    }

    public void setInitialDelayMillis(int initialDelayMillis) {
        this.initialDelayMillis = initialDelayMillis;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public void setTotalSeconds(int totalSeconds) {
        this.totalSeconds = totalSeconds;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof CoarseProfilingConfig) {
            CoarseProfilingConfig that = (CoarseProfilingConfig) obj;
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(initialDelayMillis, that.initialDelayMillis)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(totalSeconds, that.totalSeconds);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(enabled, initialDelayMillis, intervalMillis, totalSeconds);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("initialDelayMillis", initialDelayMillis)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .toString();
    }
}

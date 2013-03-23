/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.config;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

/**
 * Immutable structure to hold the user tracing config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UserConfig {

    private final boolean enabled;
    @Nullable
    private final String userId;
    // store threshold of -1 means use general config store threshold
    // for session traces, the real threshold is the minimum of this and the general threshold
    private final int storeThresholdMillis;
    private final boolean fineProfiling;
    private final String version;

    static UserConfig getDefault() {
        final boolean enabled = true;
        final String userId = null;
        final int storeThresholdMillis = 0;
        final boolean fineProfiling = true;
        return new UserConfig(enabled, userId, storeThresholdMillis, fineProfiling);
    }

    public static Overlay overlay(UserConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public UserConfig(boolean enabled, @Nullable String userId, int storeThresholdMillis,
            boolean fineProfiling) {
        this.enabled = enabled;
        this.userId = userId;
        this.storeThresholdMillis = storeThresholdMillis;
        this.fineProfiling = fineProfiling;
        version = VersionHashes.sha1(enabled, userId, storeThresholdMillis, fineProfiling);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public boolean isFineProfiling() {
        return fineProfiling;
    }

    @JsonView(WithVersionJsonView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("userId", userId)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("fineProfiling", fineProfiling)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    public static class Overlay {

        private boolean enabled;
        @Nullable
        private String userId;
        private int storeThresholdMillis;
        private boolean fineProfiling;

        private Overlay(UserConfig base) {
            enabled = base.enabled;
            userId = base.userId;
            storeThresholdMillis = base.storeThresholdMillis;
            fineProfiling = base.fineProfiling;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setUserId(@Nullable String userId) {
            this.userId = userId;
        }
        public void setStoreThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
        }
        public void setFineProfiling(boolean fineProfiling) {
            this.fineProfiling = fineProfiling;
        }
        public UserConfig build() {
            return new UserConfig(enabled, userId, storeThresholdMillis, fineProfiling);
        }
    }
}

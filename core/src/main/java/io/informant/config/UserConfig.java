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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.base.Objects;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

/**
 * Immutable structure to hold the user tracing config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@JsonDeserialize(builder = UserConfig.Builder.class)
public class UserConfig {

    private final boolean enabled;
    @Nullable
    private final String userId;
    // store threshold of -1 means use core config store threshold
    // for session traces, the real threshold is the minimum of this and the core
    // threshold
    private final int storeThresholdMillis;
    private final boolean fineProfiling;
    private final String version;

    public static Builder builder(UserConfig base) {
        return new Builder(base);
    }

    static UserConfig getDefault() {
        return new Builder().build();
    }

    private UserConfig(boolean enabled, @Nullable String userId, int storeThresholdMillis,
            boolean fineProfiling, String version) {
        this.enabled = enabled;
        this.userId = userId;
        this.storeThresholdMillis = storeThresholdMillis;
        this.fineProfiling = fineProfiling;
        this.version = version;
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

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {

        private boolean enabled = true;
        @Nullable
        private String userId;
        private int storeThresholdMillis = 0;
        private boolean fineProfiling = true;

        private Builder() {}

        private Builder(UserConfig base) {
            enabled = base.enabled;
            userId = base.userId;
            storeThresholdMillis = base.storeThresholdMillis;
            fineProfiling = base.fineProfiling;
        }

        // JsonProperty annotations are needed in order to use ObjectMapper.readerForUpdating()
        // for overlaying values on top of a base config
        @JsonProperty
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        @JsonProperty
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        @JsonProperty
        public Builder storeThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
            return this;
        }

        @JsonProperty
        public Builder fineProfiling(boolean fineProfiling) {
            this.fineProfiling = fineProfiling;
            return this;
        }

        public UserConfig build() {
            String version = buildVersion();
            return new UserConfig(enabled, userId, storeThresholdMillis, fineProfiling, version);
        }

        private String buildVersion() {
            Hasher hasher = Hashing.sha1().newHasher();
            hasher.putBoolean(enabled);
            if (userId != null) {
                hasher.putString(userId);
            }
            hasher.putInt(storeThresholdMillis);
            hasher.putBoolean(fineProfiling);
            return hasher.hash().toString();
        }
    }
}

/**
 * Copyright 2012 the original author or authors.
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
package io.informant.core.config;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Immutable structure to hold the session tracing config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UserTracingConfig {

    private static final Gson gson = new Gson();

    private final boolean enabled;

    @Nullable
    private final String userId;

    // persistence threshold of -1 means use core config persistence threshold
    // for session traces, the real threshold is the minimum of this and the core
    // threshold
    private final int persistenceThresholdMillis;
    private final boolean fineProfiling;

    static UserTracingConfig getDefaultInstance() {
        return new Builder().build();
    }

    static UserTracingConfig fromJson(String json) throws JsonSyntaxException {
        return gson.fromJson(json, UserTracingConfig.Builder.class).build();
    }

    public static Builder builder(UserTracingConfig base) {
        return new Builder(base);
    }

    private UserTracingConfig(boolean enabled, @Nullable String userId,
            int persistenceThresholdMillis, boolean fineProfiling) {

        this.enabled = enabled;
        this.userId = userId;
        this.persistenceThresholdMillis = persistenceThresholdMillis;
        this.fineProfiling = fineProfiling;
    }

    public String toJson() {
        return gson.toJson(this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    public int getPersistenceThresholdMillis() {
        return persistenceThresholdMillis;
    }

    public boolean isFineProfiling() {
        return fineProfiling;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("userId", userId)
                .add("persistenceThresholdMillis", persistenceThresholdMillis)
                .add("fineProfiling", fineProfiling)
                .toString();
    }

    public static class Builder {

        private boolean enabled = true;
        @Nullable
        private String userId;
        private int persistenceThresholdMillis = 0;
        private boolean fineProfiling = true;

        private Builder() {}
        private Builder(UserTracingConfig base) {
            enabled = base.enabled;
            userId = base.userId;
            persistenceThresholdMillis = base.persistenceThresholdMillis;
            fineProfiling = base.fineProfiling;
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder userId(@Nullable String userId) {
            this.userId = userId;
            return this;
        }
        public Builder persistenceThresholdMillis(int persistenceThresholdMillis) {
            this.persistenceThresholdMillis = persistenceThresholdMillis;
            return this;
        }
        public Builder fineProfiling(boolean fineProfiling) {
            this.fineProfiling = fineProfiling;
            return this;
        }
        public UserTracingConfig build() {
            return new UserTracingConfig(enabled, userId, persistenceThresholdMillis,
                    fineProfiling);
        }
    }
}

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
package io.informant.core.config;

import io.informant.core.util.GsonFactory;
import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Immutable structure to hold the session tracing config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UserConfig {

    // serialize nulls so that all properties will be listed in config.json (for humans)
    private static final Gson gson = GsonFactory.newBuilder().serializeNulls().create();

    private final boolean enabled;
    @Nullable
    private final String userId;
    // store threshold of -1 means use core config store threshold
    // for session traces, the real threshold is the minimum of this and the core
    // threshold
    private final int storeThresholdMillis;
    private final boolean fineProfiling;

    public static Builder builder(UserConfig base) {
        return new Builder(base);
    }

    static UserConfig fromJson(@ReadOnly JsonObject configObject) throws JsonSyntaxException {
        return gson.fromJson(configObject, UserConfig.Builder.class).build();
    }

    static UserConfig getDefault() {
        return new Builder().build();
    }

    private UserConfig(boolean enabled, @Nullable String userId, int storeThresholdMillis,
            boolean fineProfiling) {

        this.enabled = enabled;
        this.userId = userId;
        this.storeThresholdMillis = storeThresholdMillis;
        this.fineProfiling = fineProfiling;
    }

    public JsonObject toJson() {
        return gson.toJsonTree(this).getAsJsonObject();
    }

    public JsonObject toJsonWithVersionHash() {
        JsonObject configObject = toJson();
        configObject.addProperty("versionHash", getVersionHash());
        return configObject;
    }

    public String getVersionHash() {
        return Hashing.md5().hashString(toJson().toString()).toString();
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

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("userId", userId)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("fineProfiling", fineProfiling)
                .add("versionHash", getVersionHash())
                .toString();
    }

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
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }
        public Builder storeThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
            return this;
        }
        public Builder fineProfiling(boolean fineProfiling) {
            this.fineProfiling = fineProfiling;
            return this;
        }
        public Builder overlay(@ReadOnly JsonObject configObject) {
            JsonElement enabledElement = configObject.get("enabled");
            if (enabledElement != null) {
                enabled(enabledElement.getAsBoolean());
            }
            JsonElement userIdElement = configObject.get("userId");
            if (userIdElement != null) {
                if (userIdElement.isJsonNull()) {
                    userId("");
                } else {
                    userId(userIdElement.getAsString());
                }
            }
            JsonElement storeThresholdMillisElement = configObject.get("storeThresholdMillis");
            if (storeThresholdMillisElement != null) {
                storeThresholdMillis(storeThresholdMillisElement.getAsInt());
            }
            JsonElement fineProfilingElement = configObject.get("fineProfiling");
            if (fineProfilingElement != null) {
                fineProfiling(fineProfilingElement.getAsBoolean());
            }
            return this;
        }
        public UserConfig build() {
            return new UserConfig(enabled, userId, storeThresholdMillis, fineProfiling);
        }
    }
}

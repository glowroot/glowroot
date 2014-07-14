/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.config;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the user tracing config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UserTracingConfig {

    @Nullable
    private final String user;
    // store threshold of -1 means use general config store threshold
    // for session traces, the real threshold is the minimum of this and the general threshold
    private final int storeThresholdMillis;
    private final boolean profile;
    private final String version;

    static UserTracingConfig getDefault() {
        final String user = null;
        final int storeThresholdMillis = 0;
        final boolean profiling = true;
        return new UserTracingConfig(user, storeThresholdMillis, profiling);
    }

    public static Overlay overlay(UserTracingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public UserTracingConfig(@Nullable String user, int storeThresholdMillis, boolean profile) {
        this.user = user;
        this.storeThresholdMillis = storeThresholdMillis;
        this.profile = profile;
        version = VersionHashes.sha1(user, storeThresholdMillis, profile);
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public boolean isProfile() {
        return profile;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("user", user)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("profile", profile)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        @Nullable
        private String user;
        private int storeThresholdMillis;
        private boolean profile;

        private Overlay(UserTracingConfig base) {
            user = base.user;
            storeThresholdMillis = base.storeThresholdMillis;
            profile = base.profile;
        }
        public void setUser(@Nullable String user) {
            this.user = user;
        }
        public void setStoreThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
        }
        public void setProfile(boolean profile) {
            this.profile = profile;
        }
        public UserTracingConfig build() {
            return new UserTracingConfig(user, storeThresholdMillis, profile);
        }
    }
}

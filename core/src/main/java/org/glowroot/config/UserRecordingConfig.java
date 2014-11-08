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
import com.google.common.base.MoreObjects;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

@Immutable
public class UserRecordingConfig {

    private final boolean enabled;
    @Nullable
    private final String user;
    private final int profileIntervalMillis;
    private final String version;

    static UserRecordingConfig getDefault() {
        final boolean enabled = false;
        final String user = null;
        final int profileIntervalMillis = 10;
        return new UserRecordingConfig(enabled, user, profileIntervalMillis);
    }

    public static Overlay overlay(UserRecordingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public UserRecordingConfig(boolean enabled, @Nullable String user, int profileIntervalMillis) {
        this.enabled = enabled;
        this.user = user;
        this.profileIntervalMillis = profileIntervalMillis;
        version = VersionHashes.sha1(user, profileIntervalMillis);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public int getProfileIntervalMillis() {
        return profileIntervalMillis;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("user", user)
                .add("profileIntervalMillis", profileIntervalMillis)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean enabled;
        @Nullable
        private String user;
        private int profileIntervalMillis;

        private Overlay(UserRecordingConfig base) {
            enabled = base.enabled;
            user = base.user;
            profileIntervalMillis = base.profileIntervalMillis;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setUser(@Nullable String user) {
            this.user = user;
        }
        public void setProfileIntervalMillis(int profileIntervalMillis) {
            this.profileIntervalMillis = profileIntervalMillis;
        }
        public UserRecordingConfig build() {
            return new UserRecordingConfig(enabled, user, profileIntervalMillis);
        }
    }
}

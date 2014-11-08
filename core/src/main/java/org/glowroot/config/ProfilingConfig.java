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

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class ProfilingConfig {

    private final boolean enabled;
    private final int intervalMillis;

    private final String version;

    static ProfilingConfig getDefault() {
        final boolean enabled = true;
        final int intervalMillis = 2000;
        return new ProfilingConfig(enabled, intervalMillis);
    }

    public static Overlay overlay(ProfilingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public ProfilingConfig(boolean enabled, int intervalMillis) {
        this.enabled = enabled;
        this.intervalMillis = intervalMillis;
        version = VersionHashes.sha1(enabled, intervalMillis);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("intervalMillis", intervalMillis)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean enabled;
        private int intervalMillis;

        private Overlay(ProfilingConfig base) {
            enabled = base.enabled;
            intervalMillis = base.intervalMillis;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setIntervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
        }
        public ProfilingConfig build() {
            return new ProfilingConfig(enabled, intervalMillis);
        }
    }
}

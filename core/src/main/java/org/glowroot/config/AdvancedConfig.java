/*
 * Copyright 2013-2014 the original author or authors.
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
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the advanced config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class AdvancedConfig {

    private final boolean traceMetricWrapperMethods;

    private final String version;

    static AdvancedConfig getDefault() {
        final boolean traceMetricWrapperMethods = false;
        return new AdvancedConfig(traceMetricWrapperMethods);
    }

    public static Overlay overlay(AdvancedConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public AdvancedConfig(boolean traceMetricWrapperMethods) {
        this.traceMetricWrapperMethods = traceMetricWrapperMethods;
        this.version = VersionHashes.sha1(traceMetricWrapperMethods);
    }

    public boolean isTraceMetricWrapperMethods() {
        return traceMetricWrapperMethods;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("traceMetricWrapperMethods", traceMetricWrapperMethods)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean traceMetricWrapperMethods;

        private Overlay(AdvancedConfig base) {
            traceMetricWrapperMethods = base.traceMetricWrapperMethods;
        }
        public void setTraceMetricWrapperMethods(boolean traceMetricWrapperMethods) {
            this.traceMetricWrapperMethods = traceMetricWrapperMethods;
        }
        public AdvancedConfig build() {
            return new AdvancedConfig(traceMetricWrapperMethods);
        }
    }
}

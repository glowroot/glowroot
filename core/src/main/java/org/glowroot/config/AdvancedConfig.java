/*
 * Copyright 2013 the original author or authors.
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

import checkers.igj.quals.Immutable;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

import org.glowroot.config.JsonViews.UiView;
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

    private final boolean generateMetricNameWrapperMethods;
    private final boolean warnOnSpanOutsideTrace;
    private final boolean weavingDisabled;

    private final String version;

    static AdvancedConfig getDefault() {
        final boolean generateMetricNameWrapperMethods = false;
        final boolean warnOnSpanOutsideTrace = false;
        final boolean weavingDisabled = false;
        return new AdvancedConfig(generateMetricNameWrapperMethods, warnOnSpanOutsideTrace,
                weavingDisabled);
    }

    public static Overlay overlay(AdvancedConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public AdvancedConfig(boolean generateMetricNameWrapperMethods, boolean warnOnSpanOutsideTrace,
            boolean weavingDisabled) {
        this.generateMetricNameWrapperMethods = generateMetricNameWrapperMethods;
        this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
        this.weavingDisabled = weavingDisabled;
        this.version = VersionHashes.sha1(generateMetricNameWrapperMethods, warnOnSpanOutsideTrace,
                weavingDisabled);
    }

    public boolean isGenerateMetricNameWrapperMethods() {
        return generateMetricNameWrapperMethods;
    }

    public boolean isWarnOnSpanOutsideTrace() {
        return warnOnSpanOutsideTrace;
    }

    public boolean isWeavingDisabled() {
        return weavingDisabled;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("generateMetricNameWrapperMethods", generateMetricNameWrapperMethods)
                .add("warnOnSpanOutsideTrace", warnOnSpanOutsideTrace)
                .add("weavingDisabled", weavingDisabled)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean generateMetricNameWrapperMethods;
        private boolean warnOnSpanOutsideTrace;
        private boolean weavingDisabled;

        private Overlay(AdvancedConfig base) {
            generateMetricNameWrapperMethods = base.generateMetricNameWrapperMethods;
            warnOnSpanOutsideTrace = base.warnOnSpanOutsideTrace;
            weavingDisabled = base.weavingDisabled;
        }
        public void setGenerateMetricNameWrapperMethods(boolean generateMetricNameWrapperMethods) {
            this.generateMetricNameWrapperMethods = generateMetricNameWrapperMethods;
        }
        public void setWarnOnSpanOutsideTrace(boolean warnOnSpanOutsideTrace) {
            this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
        }
        public void setWeavingDisabled(boolean weavingDisabled) {
            this.weavingDisabled = weavingDisabled;
        }
        public AdvancedConfig build() {
            return new AdvancedConfig(generateMetricNameWrapperMethods, warnOnSpanOutsideTrace,
                    weavingDisabled);
        }
    }
}

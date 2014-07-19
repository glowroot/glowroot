/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.config.VersionHashes;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByJsonBinding
@Immutable
public class Layout {

    private final boolean jvmHeapHistogram;
    private final boolean jvmHeapDump;
    private final String footerMessage;
    private final boolean passwordEnabled;
    private final ImmutableList<LayoutPlugin> plugins;
    private final ImmutableList<String> transactionTypes;
    private final String defaultTransactionType;
    private final ImmutableList<String> traceCustomAttributes;
    private final long fixedTransactionPointIntervalSeconds;
    private final String version;

    static Builder builder() {
        return new Builder();
    }

    Layout(boolean jvmHeapHistogram, boolean jvmHeapDump, String footerMessage,
            boolean passwordEnabled, List<LayoutPlugin> plugins, List<String> transactionTypes,
            String defaultTransactionType, List<String> traceCustomAttributes,
            long fixedTransactionPointIntervalSeconds) {
        this.jvmHeapHistogram = jvmHeapHistogram;
        this.jvmHeapDump = jvmHeapDump;
        this.footerMessage = footerMessage;
        this.passwordEnabled = passwordEnabled;
        this.plugins = ImmutableList.copyOf(plugins);
        this.transactionTypes = ImmutableList.copyOf(transactionTypes);
        this.defaultTransactionType = defaultTransactionType;
        this.traceCustomAttributes = ImmutableList.copyOf(traceCustomAttributes);
        this.fixedTransactionPointIntervalSeconds = fixedTransactionPointIntervalSeconds;
        version = VersionHashes.sha1(jvmHeapHistogram, jvmHeapDump, footerMessage, passwordEnabled,
                transactionTypes, defaultTransactionType, traceCustomAttributes,
                fixedTransactionPointIntervalSeconds);
    }

    public boolean isJvmHeapHistogram() {
        return jvmHeapHistogram;
    }

    public boolean isJvmHeapDump() {
        return jvmHeapDump;
    }

    public String getFooterMessage() {
        return footerMessage;
    }

    public boolean isPasswordEnabled() {
        return passwordEnabled;
    }

    public ImmutableList<LayoutPlugin> getPlugins() {
        return plugins;
    }

    public ImmutableList<String> getTransactionTypes() {
        return transactionTypes;
    }

    public String getDefaultTransactionType() {
        return defaultTransactionType;
    }

    public ImmutableList<String> getTraceCustomAttributes() {
        return traceCustomAttributes;
    }

    public long getFixedTransactionPointIntervalSeconds() {
        return fixedTransactionPointIntervalSeconds;
    }

    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("jvmHeapHistogram", jvmHeapHistogram)
                .add("jvmHeapDump", jvmHeapDump)
                .add("footerMessage", footerMessage)
                .add("passwordEnabled", passwordEnabled)
                .add("plugins", plugins)
                .add("transactionTypes", transactionTypes)
                .add("defaultTransactionType", defaultTransactionType)
                .add("traceCustomAttributes", traceCustomAttributes)
                .add("fixedTransactionPointIntervalSeconds", fixedTransactionPointIntervalSeconds)
                .toString();
    }

    @UsedByJsonBinding
    @Immutable
    public static class LayoutPlugin {

        private final String id;
        private final String name;

        public LayoutPlugin(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }
        public String getName() {
            return name;
        }
    }

    static class Builder {

        private boolean jvmHeapHistogram;
        private boolean jvmHeapDump;
        @MonotonicNonNull
        private String footerMessage;
        private boolean passwordEnabled;
        private List<LayoutPlugin> plugins = ImmutableList.of();
        private List<String> transactionTypes = ImmutableList.of();
        @MonotonicNonNull
        private String defaultTransactionType;
        private List<String> traceCustomAttributes = ImmutableList.of();
        private long fixedTransactionPointIntervalSeconds;

        private Builder() {}

        Builder jvmHeapHistogram(boolean jvmHeapHistogram) {
            this.jvmHeapHistogram = jvmHeapHistogram;
            return this;
        }

        Builder jvmHeapDump(boolean jvmHeapDump) {
            this.jvmHeapDump = jvmHeapDump;
            return this;
        }

        @EnsuresNonNull("footerMessage")
        Builder footerMessage(String footerMessage) {
            this.footerMessage = footerMessage;
            return this;
        }

        Builder passwordEnabled(boolean passwordEnabled) {
            this.passwordEnabled = passwordEnabled;
            return this;
        }

        Builder plugins(List<LayoutPlugin> plugins) {
            this.plugins = plugins;
            return this;
        }

        Builder transactionTypes(List<String> transactionTypes) {
            this.transactionTypes = transactionTypes;
            return this;
        }

        @EnsuresNonNull("defaultTransactionType")
        Builder defaultTransactionType(String defaultTransactionType) {
            this.defaultTransactionType = defaultTransactionType;
            return this;
        }

        Builder traceCustomAttributes(List<String> traceCustomAttributes) {
            this.traceCustomAttributes = traceCustomAttributes;
            return this;
        }

        Builder fixedTransactionPointIntervalSeconds(long fixedTransactionPointIntervalSeconds) {
            this.fixedTransactionPointIntervalSeconds = fixedTransactionPointIntervalSeconds;
            return this;
        }

        @RequiresNonNull({"footerMessage", "defaultTransactionType"})
        Layout build() {
            return new Layout(jvmHeapHistogram, jvmHeapDump, footerMessage, passwordEnabled,
                    plugins, transactionTypes, defaultTransactionType, traceCustomAttributes,
                    fixedTransactionPointIntervalSeconds);
        }
    }
}

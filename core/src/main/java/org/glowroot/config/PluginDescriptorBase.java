/*
 * Copyright 2012-2015 the original author or authors.
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

import java.util.Locale;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@JsonDeserialize(as = PluginDescriptor.class)
public abstract class PluginDescriptorBase {

    static final Ordering<PluginDescriptor> specialOrderingByName =
            new Ordering<PluginDescriptor>() {
                @Override
                public int compare(@Nullable PluginDescriptor left,
                        @Nullable PluginDescriptor right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    // conventionally plugin names ends with " Plugin", so strip this off when
                    // comparing names so that, e.g., "Abc Plugin" will come before
                    // "Abc Extra Plugin"
                    String leftName = stripEndingIgnoreCase(left.name(), " Plugin");
                    String rightName = stripEndingIgnoreCase(right.name(), " Plugin");
                    return leftName.compareToIgnoreCase(rightName);
                }
            };

    static final Ordering<PluginDescriptor> orderingById = new Ordering<PluginDescriptor>() {
        @Override
        public int compare(@Nullable PluginDescriptor left, @Nullable PluginDescriptor right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.id().compareToIgnoreCase(right.id());
        }
    };

    public abstract String name();
    public abstract String id();
    public abstract ImmutableList<String> transactionTypes();
    public abstract ImmutableList<String> transactionCustomAttributes();
    public abstract ImmutableList<PropertyDescriptor> properties();
    @JsonProperty("instrumentation")
    public abstract ImmutableList<InstrumentationConfig> instrumentationConfigs();
    public abstract ImmutableList<String> aspects();

    private static String stripEndingIgnoreCase(String original, String ending) {
        if (original.toUpperCase(Locale.ENGLISH).endsWith(ending.toUpperCase(Locale.ENGLISH))) {
            return original.substring(0, original.length() - ending.length());
        } else {
            return original;
        }
    }
}

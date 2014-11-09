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

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.collect.Ordering;
import org.immutables.value.Json;
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
public abstract class PluginDescriptor {

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

    public abstract String name();
    public abstract String id();
    public abstract String version();
    public abstract List<String> transactionTypes();
    public abstract List<String> transactionCustomAttributes();
    public abstract List<PropertyDescriptor> properties();
    public abstract List<CapturePoint> capturePoints();
    public abstract List<String> aspects();

    public PluginDescriptor copyWithoutAdvice() {
        return ((ImmutablePluginDescriptor) this)
                .withCapturePoints()
                .withAspects();
    }

    private static String stripEndingIgnoreCase(String original, String ending) {
        if (original.toUpperCase(Locale.ENGLISH).endsWith(ending.toUpperCase(Locale.ENGLISH))) {
            return original.substring(0, original.length() - ending.length());
        } else {
            return original;
        }
    }
}

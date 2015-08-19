/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.common.config;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
public abstract class GaugeConfig {

    public static final Ordering<GaugeConfig> orderingByName = new Ordering<GaugeConfig>() {
        @Override
        public int compare(@Nullable GaugeConfig left, @Nullable GaugeConfig right) {
            checkNotNull(left);
            checkNotNull(right);
            return display(left.mbeanObjectName())
                    .compareToIgnoreCase(display(right.mbeanObjectName()));
        }
    };

    public abstract String mbeanObjectName();
    public abstract ImmutableList<MBeanAttribute> mbeanAttributes();

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }

    public static String display(String mbeanObjectName) {
        // e.g. java.lang:name=PS Eden Space,type=MemoryPool
        List<String> parts = Splitter.on(CharMatcher.anyOf(":,")).splitToList(mbeanObjectName);
        StringBuilder name = new StringBuilder();
        name.append(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            name.append('/');
            name.append(parts.get(i).split("=")[1]);
        }
        return name.toString();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface MBeanAttribute {
        String name();
        boolean counter();
    }
}

/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.storage.repo.helper;

import java.util.List;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;

import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.storage.repo.ImmutableGauge;

public class Gauges {

    private Gauges() {}

    public static Gauge getGauge(String gaugeName) {
        int index = gaugeName.lastIndexOf(':');
        String mbeanObjectName = gaugeName.substring(0, index);
        String mbeanAttributeName = gaugeName.substring(index + 1);
        boolean counter = mbeanAttributeName.endsWith("[counter]");
        if (counter) {
            mbeanAttributeName = mbeanAttributeName.substring(0,
                    mbeanAttributeName.length() - "[counter]".length());
        }
        String display = display(mbeanObjectName) + '/' + mbeanAttributeName;
        return ImmutableGauge.of(gaugeName, display, counter);
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
}

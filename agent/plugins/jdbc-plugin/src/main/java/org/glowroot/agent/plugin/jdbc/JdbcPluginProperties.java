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
package org.glowroot.agent.plugin.jdbc;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMultimap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;

// this is public so it can be called from other plugins
public class JdbcPluginProperties {

    private static final ConfigService configService = Agent.getConfigService("jdbc");

    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private static int stackTraceThresholdMillis;

    private static volatile ImmutableMultimap<String, Integer> displayBinaryParameterAsHex =
            ImmutableMultimap.of();

    static {
        configService.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                Double value = configService.getDoubleProperty("stackTraceThresholdMillis").value();
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
    }

    private JdbcPluginProperties() {}

    // this can always be called multiple times with the same sql if want to display multiple
    // parameters in the same sql as hex
    //
    // this is public so it can be called from other plugins
    public static void setDisplayBinaryParameterAsHex(String sql, int parameterIndex) {
        HashMultimap<String, Integer> mutableMultimap =
                HashMultimap.create(displayBinaryParameterAsHex);
        mutableMultimap.put(sql, parameterIndex);
        displayBinaryParameterAsHex = ImmutableMultimap.copyOf(mutableMultimap);
    }

    static int stackTraceThresholdMillis() {
        return stackTraceThresholdMillis;
    }

    static boolean displayBinaryParameterAsHex(String sql, int parameterIndex) {
        return displayBinaryParameterAsHex.containsEntry(sql, parameterIndex);
    }
}

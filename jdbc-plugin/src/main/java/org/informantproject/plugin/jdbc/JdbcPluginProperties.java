/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.plugin.jdbc;

import org.informantproject.shaded.google.common.collect.HashMultimap;
import org.informantproject.shaded.google.common.collect.ImmutableMultimap;
import org.informantproject.shaded.google.common.collect.Multimap;

/**
 * Public API for other plugins to modify the jdbc plugin behavior.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class JdbcPluginProperties {

    private static volatile Multimap<String, Integer> displayBinaryParameterAsHex =
            ImmutableMultimap.of();

    private JdbcPluginProperties() {}

    public static void setDisplayBinaryParameterAsHex(String sql, int parameterIndex) {
        HashMultimap<String, Integer> mutableMultimap = HashMultimap
                .create(displayBinaryParameterAsHex);
        mutableMultimap.put(sql, parameterIndex);
        displayBinaryParameterAsHex = ImmutableMultimap.copyOf(mutableMultimap);
    }

    public static void setDisplayBinaryParamatersAsHex(String sql, int... parameterIndexes) {
        HashMultimap<String, Integer> mutableMultimap = HashMultimap
                .create(displayBinaryParameterAsHex);
        for (int parameterIndex : parameterIndexes) {
            mutableMultimap.put(sql, parameterIndex);
        }
        displayBinaryParameterAsHex = ImmutableMultimap.copyOf(mutableMultimap);
    }

    static boolean displayBinaryParameterAsHex(String sql, int parameterIndex) {
        return displayBinaryParameterAsHex.containsEntry(sql, parameterIndex);
    }
}

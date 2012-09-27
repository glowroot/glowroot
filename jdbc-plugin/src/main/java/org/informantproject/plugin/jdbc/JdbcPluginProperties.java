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

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.shaded.google.common.collect.HashMultimap;
import org.informantproject.shaded.google.common.collect.ImmutableMultimap;

/**
 * Public API for other plugins to modify the jdbc plugin behavior.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public final class JdbcPluginProperties {

    private static volatile ImmutableMultimap<String, Integer> displayBinaryParameterAsHex =
            ImmutableMultimap.of();

    // this can always be called multiple times with the same sql if want to display multiple
    // parameters in the same sql as hex
    public static void setDisplayBinaryParameterAsHex(String sql, int parameterIndex) {
        HashMultimap<String, Integer> mutableMultimap = HashMultimap
                .create(displayBinaryParameterAsHex);
        mutableMultimap.put(sql, parameterIndex);
        displayBinaryParameterAsHex = ImmutableMultimap.copyOf(mutableMultimap);
    }

    static boolean displayBinaryParameterAsHex(String sql, int parameterIndex) {
        return displayBinaryParameterAsHex.containsEntry(sql, parameterIndex);
    }

    private JdbcPluginProperties() {}
}

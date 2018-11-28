/*
 * Copyright 2018 the original author or authors.
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

import java.util.Locale;

public class PluginNameComparison {

    private PluginNameComparison() {}

    public static int compareNames(String left, String right) {
        // conventionally plugin names ends with " Plugin", so strip this off when comparing names
        // so that, e.g., "Abc Plugin" will come before "Abc Extra Plugin"
        String leftName = stripEndingIgnoreCase(left, " Plugin");
        String rightName = stripEndingIgnoreCase(right, " Plugin");
        return leftName.compareToIgnoreCase(rightName);
    }

    private static String stripEndingIgnoreCase(String original, String ending) {
        if (original.toUpperCase(Locale.ENGLISH).endsWith(ending.toUpperCase(Locale.ENGLISH))) {
            return original.substring(0, original.length() - ending.length());
        } else {
            return original;
        }
    }
}

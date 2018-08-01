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
package org.glowroot.agent.util;

import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import org.checkerframework.checker.nullness.qual.Nullable;

public class MaybePatterns {

    public static @Nullable Pattern buildPattern(String maybePattern) {
        if (maybePattern.startsWith("/") && maybePattern.endsWith("/")) {
            // full regex power
            return Pattern.compile(maybePattern.substring(1, maybePattern.length() - 1));
        }
        // limited regex, | and *, should be used whenever possible over full regex since
        // . and $ are common in class names
        if (maybePattern.contains("|")) {
            String[] parts = maybePattern.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = buildSimplePattern(parts[i]);
            }
            return Pattern.compile(Joiner.on('|').join(parts));
        }
        if (maybePattern.contains("*")) {
            return Pattern.compile(buildSimplePattern(maybePattern));
        }
        return null;
    }

    private static String buildSimplePattern(String part) {
        // convert * into .* and quote the rest of the text using \Q...\E
        String pattern = "\\Q" + part.replace("*", "\\E.*\\Q") + "\\E";
        // strip off unnecessary \\Q\\E in case * appeared at beginning or end of part
        return pattern.replace("\\Q\\E", "");
    }
}

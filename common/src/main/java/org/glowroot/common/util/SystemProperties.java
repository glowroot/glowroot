/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.common.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class SystemProperties {

    private SystemProperties() {}

    public static List<String> maskJvmArgs(List<String> jvmArgs,
            List<String> maskSystemProperties) {
        if (maskSystemProperties.isEmpty()) {
            return jvmArgs;
        }
        List<Pattern> maskSystemPropertyPatterns = buildPatternList(maskSystemProperties);
        List<String> maskedJvmArgs = Lists.newArrayList();
        for (String arg : jvmArgs) {
            maskedJvmArgs.add(maskSystemProperty(arg, maskSystemPropertyPatterns));
        }
        return maskedJvmArgs;
    }

    public static Map<String, String> maskSystemProperties(Map<String, String> systemProperties,
            List<String> maskSystemProperties) {
        if (maskSystemProperties.isEmpty()) {
            return systemProperties;
        }
        List<Pattern> maskPatterns = buildPatternList(maskSystemProperties);
        Map<String, String> maskedSystemProperties = Maps.newHashMap();
        for (Map.Entry<String, String> entry : systemProperties.entrySet()) {
            String name = entry.getKey();
            if (matchesAny(name, maskPatterns)) {
                maskedSystemProperties.put(name, "****");
            } else {
                maskedSystemProperties.put(name, entry.getValue());
            }
        }
        return maskedSystemProperties;
    }

    private static String maskSystemProperty(String arg, List<Pattern> maskPatterns) {
        if (!arg.startsWith("-D")) {
            return arg;
        }
        int index = arg.indexOf('=');
        if (index == -1) {
            return arg;
        }
        String name = arg.substring(2, index);
        // converted to lower case for case-insensitive matching (patterns are lower case)
        String nameLowerCase = name.toLowerCase(Locale.ENGLISH);
        if (!matchesAny(nameLowerCase, maskPatterns)) {
            return arg;
        }
        return "-D" + name + "=****";
    }

    private static boolean matchesAny(String text, List<Pattern> patterns) {
        // converted to lower case for case-insensitive matching (patterns are lower case)
        String textLowerCase = text.toLowerCase(Locale.ENGLISH);
        for (Pattern pattern : patterns) {
            if (pattern.matcher(textLowerCase).matches()) {
                return true;
            }
        }
        return false;
    }

    private static ImmutableList<Pattern> buildPatternList(List<String> properties) {
        List<Pattern> propertyPatterns = Lists.newArrayList();
        for (String property : properties) {
            // converted to lower case for case-insensitive matching
            propertyPatterns.add(buildRegexPattern(property.toLowerCase(Locale.ENGLISH)));
        }
        return ImmutableList.copyOf(propertyPatterns);
    }

    private static Pattern buildRegexPattern(String wildcardPattern) {
        // convert * into .* and quote the rest of the text using \Q...\E
        String regex = "\\Q" + wildcardPattern.replace("*", "\\E.*\\Q") + "\\E";
        // strip off unnecessary \\Q\\E in case * appeared at beginning or end of part
        regex = regex.replace("\\Q\\E", "");
        return Pattern.compile(regex);
    }
}

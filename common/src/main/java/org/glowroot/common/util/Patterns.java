/*
 * Copyright 2015 the original author or authors.
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

public class Patterns {

    private Patterns() {}

    public static String buildSimplePattern(String part) {
        // convert * into .* and quote the rest of the text using \Q...\E
        String pattern = "\\Q" + part.replace("*", "\\E.*\\Q") + "\\E";
        // strip off unnecessary \\Q\\E in case * appeared at beginning or end of part
        return pattern.replace("\\Q\\E", "");
    }
}

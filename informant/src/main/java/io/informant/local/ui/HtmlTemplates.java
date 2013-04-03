/**
 * Copyright 2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;

import io.informant.common.CharStreams2;
import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class HtmlTemplates {

    private HtmlTemplates() {}

    static CharSource processTemplate(String resourcePath) throws IOException {
        return processTemplate(resourcePath, ImmutableMap.<String, CharSource>of());
    }

    static CharSource processTemplate(String resourcePath,
            Map<String, CharSource> extraMappedContent) throws IOException {
        URL url = Resources.getResource(resourcePath);
        // process server side includes
        String templateContent = Resources.toString(url, Charsets.UTF_8);
        Pattern pattern = Pattern.compile("/\\*#include \"([^\"]+)\" \\*/");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<CharSource> charSources = Lists.newArrayList();
        while (matcher.find()) {
            charSources.add(CharStreams.asCharSource(
                    templateContent.substring(curr, matcher.start())));
            String include = matcher.group(1);
            CharSource charSource = extraMappedContent.get(include);
            if (charSource == null) {
                URL resource = Resources.getResource("io/informant/local/ui/" + include);
                charSources.add(Resources.asCharSource(resource, Charsets.UTF_8));
            } else {
                charSources.add(charSource);
            }
            curr = matcher.end();
        }
        charSources.add(CharStreams.asCharSource(templateContent.substring(curr)));
        return CharStreams2.join(charSources);
    }
}

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.CharStreams2;
import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class HtmlPages {

    private static final Logger logger = LoggerFactory.getLogger(HtmlPages.class);

    private HtmlPages() {}

    static CharSource render(String resourcePath, boolean devMode) throws IOException {
        return render(resourcePath, ImmutableMap.<String, CharSource>of(), devMode);
    }

    static CharSource render(String resourcePath, Map<String, CharSource> extraMappedContent,
            boolean devMode) throws IOException {
        URL url = Resources.getResource(resourcePath);
        // process server side includes
        String templateContent = Resources.toString(url, Charsets.UTF_8);
        Pattern pattern = Pattern.compile(
                "/\\*#include \"([^\"]+)\" \\*/"
                        + "|<!--#ifDevMode -->"
                        + "|<!--#else -->"
                        + "|<!--#endif -->");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<CharSource> charSources = Lists.newArrayList();
        boolean render = true;
        while (matcher.find()) {
            if (render) {
                charSources.add(CharStreams.asCharSource(
                        templateContent.substring(curr, matcher.start())));
            }
            curr = matcher.end();
            String match = matcher.group();
            if (match.startsWith("<!--#ifDevMode -->")) {
                render = devMode;
            } else if (match.equals("<!--#else -->")) {
                render = !devMode;
            } else if (match.equals("<!--#endif -->")) {
                render = true;
            } else if (match.startsWith("/*#include ")) {
                if (render) {
                    String include = matcher.group(1);
                    CharSource charSource = extraMappedContent.get(include);
                    if (charSource == null) {
                        String resourceBase = resourcePath.substring(0,
                                resourcePath.lastIndexOf('/'));
                        URL resource = Resources.getResource(resourceBase + '/' + include);
                        charSources.add(Resources.asCharSource(resource, Charsets.UTF_8));
                    } else {
                        charSources.add(charSource);
                    }
                }
            } else {
                logger.error("unexpected match: {}", match);
            }
        }
        charSources.add(CharStreams.asCharSource(templateContent.substring(curr)));
        return CharStreams2.join(charSources);
    }
}

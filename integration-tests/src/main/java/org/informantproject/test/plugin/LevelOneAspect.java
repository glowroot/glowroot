/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.test.plugin;

import org.informantproject.api.Metric;
import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.Span;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class LevelOneAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-integration-tests");

    @Pointcut(typeName = "org.informantproject.test.api.LevelOne", methodName = "call",
            methodArgs = { "java.lang.String", "java.lang.String" }, metricName = "level one")
    public static class LevelOneAdvice {

        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(final String arg1, final String arg2) {
            RootSpanDetail rootSpanDetail = new RootSpanDetail() {
                public String getDescription() {
                    String description = pluginServices.getStringProperty("alternateDescription")
                            .or("Level One");
                    if (pluginServices.getBooleanProperty("starredDescription")) {
                        return description + "*";
                    } else {
                        return description;
                    }
                }
                public SpanContextMap getContextMap() {
                    SpanContextMap contextMap = SpanContextMap.of("arg1", arg1, "arg2", arg2);
                    SpanContextMap nestedContextMap = SpanContextMap.of("nestedkey11", arg1,
                            "nestedkey12", arg2, "subnested1",
                            SpanContextMap.of("subnestedkey1", arg1, "subnestedkey2", arg2));
                    contextMap.put("nested1", nestedContextMap);
                    contextMap.put("nested2",
                            SpanContextMap.of("nestedkey21", arg1, "nestedkey22", arg2));
                    return contextMap;
                }
                public Optional<String> getUsername() {
                    return Optional.absent();
                }
            };
            return pluginServices.startRootSpan(metric, rootSpanDetail);
        }

        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            pluginServices.endSpan(span);
        }
    }
}

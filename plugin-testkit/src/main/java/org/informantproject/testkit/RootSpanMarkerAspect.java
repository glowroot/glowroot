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
package org.informantproject.testkit;

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
public class RootSpanMarkerAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-plugin-testkit");

    @Pointcut(typeName = "org.informantproject.testkit.RootSpanMarker",
            methodName = "rootSpanMarker", metricName = "mock root span")
    public static class RootSpanMarkerAdvice {
        private static final Metric metric = pluginServices.getMetric(RootSpanMarkerAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            return pluginServices.startRootSpan(metric, new MockRootSpan());
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            pluginServices.endSpan(span);
        }
    }

    private static class MockRootSpan implements RootSpanDetail {
        public String getDescription() {
            return "mock root span";
        }
        public SpanContextMap getContextMap() {
            return null;
        }
        public Optional<String> getUsername() {
            return Optional.absent();
        }
    }
}

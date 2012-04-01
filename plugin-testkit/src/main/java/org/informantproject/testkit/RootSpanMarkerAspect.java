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
import org.informantproject.api.SpanContextMap;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class RootSpanMarkerAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-plugin-testkit");

    private static final Metric metric = pluginServices.createMetric("mock root span");

    @Pointcut("execution(void org.informantproject.testkit.RootSpanMarker.rootSpanMarker())")
    void rootSpanMarkerPointcut() {}

    @Around("rootSpanMarkerPointcut()")
    public void mockRootSpanSpanMarker(ProceedingJoinPoint joinPoint) throws Throwable {
        pluginServices.executeRootSpan(metric, new MockRootSpan(), joinPoint);
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

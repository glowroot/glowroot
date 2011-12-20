/**
 * Copyright 2011 the original author or authors.
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
public class MockAspect {

    @Pointcut("execution(void org.informantproject.testkit.MockEntryPoint.run())")
    void runPointcut() {}

    @Around("runPointcut()")
    public void aroundRunPointcut(ProceedingJoinPoint joinPoint) throws Throwable {
        PluginServices.get().executeRootSpan(new MockRootSpan(), joinPoint, "mock entry point");
    }

    private static class MockRootSpan implements RootSpanDetail {
        public String getDescription() {
            return "mock";
        }
        public SpanContextMap getContextMap() {
            return null;
        }
        public String getUsername() {
            return null;
        }
    }
}

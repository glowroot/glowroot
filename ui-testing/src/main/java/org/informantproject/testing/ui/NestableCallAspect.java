/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.testing.ui;

import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class NestableCallAspect {

    private static final PluginServices pluginServices = PluginServices.get("unittest");

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("call(void org.informantproject.testing.ui.NestableCall.execute())")
    void nestablePointcut() {}

    @Around("isPluginEnabled() && nestablePointcut()")
    public Object nestableAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        if (pluginServices.getRootSpanDetail() == null) {
            return pluginServices.executeRootSpan(getRootSpanDetail(), joinPoint, "nestable");
        } else {
            return pluginServices.executeSpan(getSpanDetail(), joinPoint, "nestable");
        }
    }

    private RootSpanDetail getRootSpanDetail() {
        return new RootSpanDetail() {
            public String getDescription() {
                return "Nestable";
            }
            public SpanContextMap getContextMap() {
                return SpanContextMap.of("attr1", "value1", "attr2", "value2", "attr3",
                        SpanContextMap.of("attr31", SpanContextMap.of("attr311", "value311",
                                "attr312", "value312"), "attr32", "value32", "attr33", "value33"));
            }
            public String getUsername() {
                return null;
            }
        };
    }

    private SpanDetail getSpanDetail() {
        return new SpanDetail() {
            public String getDescription() {
                return "Nestable";
            }
            public SpanContextMap getContextMap() {
                return null;
            }
        };
    }
}

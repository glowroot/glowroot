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

import org.informantproject.api.PluginServices;
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
public class LevelTwoAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-integration-tests");

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("if()")
    public static boolean inTrace() {
        return pluginServices.getRootSpanDetail() != null;
    }

    @Pointcut("call(void org.informantproject.test.api.LevelTwo.call(java.lang.String,"
            + " java.lang.String))")
    void levelTwoPointcut() {}

    @Around("isPluginEnabled() && levelTwoPointcut() && args(arg1, arg2)")
    public Object callAdvice(ProceedingJoinPoint joinPoint, final String arg1, final String arg2)
            throws Throwable {

        SpanDetail spanDetail = new SpanDetail() {
            public String getDescription() {
                return "Level Two";
            }
            public SpanContextMap getContextMap() {
                return SpanContextMap.of("arg1", arg1, "arg2", arg2);
            }
        };
        return pluginServices.executeSpan("level two", spanDetail, joinPoint);
    }
}

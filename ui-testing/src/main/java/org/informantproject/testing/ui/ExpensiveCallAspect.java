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
public class ExpensiveCallAspect {

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return PluginServices.get().isEnabled();
    }

    @Pointcut("call(void org.informantproject.testing.ui.ExpensiveCall.execute())")
    void expensivePointcut() {}

    @Around("isPluginEnabled() && expensivePointcut() && target(expensive)")
    public Object nestableAdvice(ProceedingJoinPoint joinPoint, ExpensiveCall expensive)
            throws Throwable {
        return PluginServices.get().executeSpan(getSpanDetail(expensive), joinPoint, "expensive");
    }

    private SpanDetail getSpanDetail(final ExpensiveCall expensive) {
        return new SpanDetail() {
            public String getDescription() {
                return expensive.getDescription();
            }
            public SpanContextMap getContextMap() {
                return null;
            }
        };
    }
}

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

import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.Metric;
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

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-ui-testing");

    private static final Metric expensive0Metric = pluginServices.createMetric("expensive0");
    private static final Metric expensive1Metric = pluginServices.createMetric("expensive1");
    private static final Metric expensive2Metric = pluginServices.createMetric("expensive2");
    private static final Metric expensive3Metric = pluginServices.createMetric("expensive3");
    private static final Metric expensive4Metric = pluginServices.createMetric("expensive4");
    private static final Metric expensive5Metric = pluginServices.createMetric("expensive5");
    private static final Metric expensive6Metric = pluginServices.createMetric("expensive6");
    private static final Metric expensive7Metric = pluginServices.createMetric("expensive7");
    private static final Metric expensive8Metric = pluginServices.createMetric("expensive8");
    private static final Metric expensive9Metric = pluginServices.createMetric("expensive9");

    private static final AtomicInteger counter = new AtomicInteger();

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("call(void org.informantproject.testing.ui.ExpensiveCall.execute())")
    void expensivePointcut() {}

    @Around("isPluginEnabled() && expensivePointcut() && target(expensive)")
    public Object expensiveSpanMarker(ProceedingJoinPoint joinPoint, ExpensiveCall expensive)
            throws Throwable {

        // using different span names to create large number of metrics in breakdown summary
        int count = counter.getAndIncrement();
        if (count % 10 == 0) {
            return expensive0SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 1) {
            return expensive1SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 2) {
            return expensive2SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 3) {
            return expensive3SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 4) {
            return expensive4SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 5) {
            return expensive5SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 6) {
            return expensive6SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 7) {
            return expensive7SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 8) {
            return expensive8SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else if (count % 10 == 9) {
            return expensive9SpanMarker(joinPoint, getSpanDetail(expensive, count));
        } else {
            throw new IllegalStateException("unexpected result modulo 10: " + count % 10);
        }
    }

    private SpanDetail getSpanDetail(final ExpensiveCall expensive, final int count) {
        return new SpanDetail() {
            public String getDescription() {
                return expensive.getDescription();
            }
            public SpanContextMap getContextMap() {
                if (count % 10 == 0) {
                    return SpanContextMap.of("attr1", "value1", "attr2", "value2", "attr3",
                            SpanContextMap.of("attr31", SpanContextMap.of("attr311", "value311",
                                    "attr312", "value312"), "attr32", "value32", "attr33",
                                    "value33"));
                } else {
                    return null;
                }
            }
        };
    }

    private Object expensive0SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive0Metric, spanDetail, joinPoint);
    }

    private Object expensive1SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive1Metric, spanDetail, joinPoint);
    }

    private Object expensive2SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive2Metric, spanDetail, joinPoint);
    }

    private Object expensive3SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive3Metric, spanDetail, joinPoint);
    }

    private Object expensive4SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive4Metric, spanDetail, joinPoint);
    }

    private Object expensive5SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive5Metric, spanDetail, joinPoint);
    }

    private Object expensive6SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive6Metric, spanDetail, joinPoint);
    }

    private Object expensive7SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive7Metric, spanDetail, joinPoint);
    }

    private Object expensive8SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive8Metric, spanDetail, joinPoint);
    }

    private Object expensive9SpanMarker(ProceedingJoinPoint joinPoint, SpanDetail spanDetail)
            throws Throwable {

        return pluginServices.executeSpan(expensive9Metric, spanDetail, joinPoint);
    }
}

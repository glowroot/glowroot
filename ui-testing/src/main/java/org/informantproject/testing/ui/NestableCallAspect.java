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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.Metric;
import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.Pointcut;

import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class NestableCallAspect {

    private static final List<Optional<String>> USERNAMES = ImmutableList.of(Optional.of("able"),
            Optional.of("baker"), Optional.of("charlie"), Optional.absent(String.class));

    private static final AtomicInteger counter = new AtomicInteger();

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-ui-testing");

    private static final Metric nestableMetric = pluginServices.createMetric("nestable");
    private static final Metric nestableAndVeryLongMetric = pluginServices.createMetric(
            "nestable and very long");

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("call(void org.informantproject.testing.ui.NestableCall.execute())")
    void nestablePointcut() {}

    @Around("isPluginEnabled() && nestablePointcut()")
    public Object nestableAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        if (pluginServices.getRootSpanDetail() == null) {
            return nestableSpanMarker(joinPoint);
        } else {
            pluginServices.putTraceAttribute("my first attribute", "hello world");
            pluginServices.putTraceAttribute("and second", "val");
            pluginServices.putTraceAttribute("and a very long attribute value", "abcdefghijklmnop"
                    + "qrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            pluginServices.putTraceAttribute("and another", "a b c d e f g"
                    + " h i j k l m n o p q r s t u v w x y z a b c d e f g h i j k l m n o p"
                    + " q r s t u v w x y z");
            return nestableAndVeryLongSpanMarker(joinPoint);
        }
    }

    private Object nestableSpanMarker(ProceedingJoinPoint joinPoint) throws Throwable {
        return pluginServices.executeRootSpan(nestableMetric, getRootSpanDetail(), joinPoint);
    }

    private Object nestableAndVeryLongSpanMarker(ProceedingJoinPoint joinPoint) throws Throwable {
        return pluginServices.executeSpan(nestableAndVeryLongMetric, getSpanDetail(), joinPoint);
    }

    private RootSpanDetail getRootSpanDetail() {
        return new RootSpanDetail() {
            public String getDescription() {
                return "Nestable with a very long description to test wrapping"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz";
            }
            public SpanContextMap getContextMap() {
                return SpanContextMap.of("attr1", "value1", "attr2", "value2", "attr3",
                        SpanContextMap.of("attr31", SpanContextMap.of("attr311", "value311",
                                "attr312", "value312"), "attr32", "value32", "attr33", "value33"));
            }
            public Optional<String> getUsername() {
                return USERNAMES.get(counter.getAndIncrement() % 4);
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

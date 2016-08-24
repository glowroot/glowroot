/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.glowroot.agent.plugin.api.weaving.OptionalReturn;
import org.glowroot.agent.weaving.SomeAspect.TestClassMeta;
import org.glowroot.agent.weaving.SomeAspect.TestMethodMeta;

public class SomeAspectThreadLocals {

    private static final Set<ThreadLocal<?>> threadLocals = Sets.newConcurrentHashSet();

    public static final ThreadLocal<Boolean> enabled = createBoolean(true);

    public static final IntegerThreadLocal enabledCount = createInteger();
    public static final IntegerThreadLocal onBeforeCount = createInteger();
    public static final IntegerThreadLocal onReturnCount = createInteger();
    public static final IntegerThreadLocal onThrowCount = createInteger();
    public static final IntegerThreadLocal onAfterCount = createInteger();

    public static final ThreadLocal<Object> isEnabledReceiver = create();
    public static final ThreadLocal<Object> onBeforeReceiver = create();
    public static final ThreadLocal<Object> onReturnReceiver = create();
    public static final ThreadLocal<Object> onThrowReceiver = create();
    public static final ThreadLocal<Object> onAfterReceiver = create();

    public static final ThreadLocal<Object[]> isEnabledParams = create();
    public static final ThreadLocal<Object[]> onBeforeParams = create();
    public static final ThreadLocal<Object[]> onReturnParams = create();
    public static final ThreadLocal<Object[]> onThrowParams = create();
    public static final ThreadLocal<Object[]> onAfterParams = create();

    public static final ThreadLocal<Object> onReturnTraveler = create();
    public static final ThreadLocal<Object> onThrowTraveler = create();
    public static final ThreadLocal<Object> onAfterTraveler = create();

    public static final ThreadLocal<Object> returnValue = create();
    public static final ThreadLocal<OptionalReturn> optionalReturnValue = create();

    public static final ThreadLocal<Throwable> throwable = create();

    public static final ThreadLocal<String> isEnabledMethodName = create();
    public static final ThreadLocal<String> onBeforeMethodName = create();
    public static final ThreadLocal<String> onReturnMethodName = create();
    public static final ThreadLocal<String> onThrowMethodName = create();
    public static final ThreadLocal<String> onAfterMethodName = create();

    public static final ThreadLocal<TestClassMeta> isEnabledClassMeta = create();
    public static final ThreadLocal<TestClassMeta> onBeforeClassMeta = create();
    public static final ThreadLocal<TestClassMeta> onReturnClassMeta = create();
    public static final ThreadLocal<TestClassMeta> onThrowClassMeta = create();
    public static final ThreadLocal<TestClassMeta> onAfterClassMeta = create();

    public static final ThreadLocal<TestMethodMeta> isEnabledMethodMeta = create();
    public static final ThreadLocal<TestMethodMeta> onBeforeMethodMeta = create();
    public static final ThreadLocal<TestMethodMeta> onReturnMethodMeta = create();
    public static final ThreadLocal<TestMethodMeta> onThrowMethodMeta = create();
    public static final ThreadLocal<TestMethodMeta> onAfterMethodMeta = create();

    public static final ThreadLocal<List<String>> orderedEvents = createList();

    public static void resetThreadLocals() {
        for (ThreadLocal<?> threadLocal : threadLocals) {
            threadLocal.remove();
        }
    }

    private static <T> ThreadLocal<T> create() {
        ThreadLocal<T> threadLocal = new ThreadLocal<T>();
        threadLocals.add(threadLocal);
        return threadLocal;
    }

    private static <T> ThreadLocal<List<T>> createList() {
        ThreadLocal<List<T>> threadLocal = new ThreadLocal<List<T>>() {
            @Override
            protected List<T> initialValue() {
                return Lists.newArrayList();
            }
        };
        threadLocals.add(threadLocal);
        return threadLocal;
    }

    private static ThreadLocal<Boolean> createBoolean(final Boolean initialValue) {
        ThreadLocal<Boolean> threadLocal = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return initialValue;
            }
        };
        threadLocals.add(threadLocal);
        return threadLocal;
    }

    private static IntegerThreadLocal createInteger() {
        IntegerThreadLocal threadLocal = new IntegerThreadLocal();
        threadLocals.add(threadLocal);
        return threadLocal;
    }

    public static class IntegerThreadLocal extends ThreadLocal<Integer> {
        @Override
        protected Integer initialValue() {
            return 0;
        }
        public void increment() {
            set(get() + 1);
        }
    }
}

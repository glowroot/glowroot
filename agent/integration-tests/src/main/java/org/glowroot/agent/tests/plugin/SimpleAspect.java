/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.tests.plugin;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.weaving.BindOptionalReturn;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

// this exists to test bytecode verification problem when both @BindReturn and OptionalThreadContext
// are used in @OnReturn
public class SimpleAspect {

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 1)
    public static class SimpleRunAdvice1 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(OptionalThreadContext context) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run",
            methodParameterTypes = {}, order = 2)
    public static class SimpleRunAdvice2 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(@BindReturn Object value, OptionalThreadContext context) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 3)
    public static class SimpleRunAdvice3 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(@BindOptionalReturn Object value,
                OptionalThreadContext context) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 4)
    public static class SimpleRunAdvice4 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(OptionalThreadContext context) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run",
            methodParameterTypes = {}, order = 5)
    public static class SimpleRunAdvice5 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(@BindReturn Object value,
                OptionalThreadContext context) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 6)
    public static class SimpleRunAdvice6 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(@BindOptionalReturn Object value,
                OptionalThreadContext context) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 7)
    public static class SimpleRunAdvice7 {

        @OnReturn
        public static void onReturn() {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run",
            methodParameterTypes = {}, order = 8)
    public static class SimpleRunAdvice8 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(@BindReturn Object value) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 9)
    public static class SimpleRunAdvice9 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(@BindOptionalReturn Object value) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 10)
    public static class SimpleRunAdvice10 {

        @OnReturn
        public static @Nullable Object onReturn() {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run",
            methodParameterTypes = {}, order = 11)
    public static class SimpleRunAdvice11 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(@BindReturn Object value) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 12)
    public static class SimpleRunAdvice12 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(@BindOptionalReturn Object value) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 13)
    public static class SimpleRunAdvice13 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(ThreadContext context) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run",
            methodParameterTypes = {}, order = 14)
    public static class SimpleRunAdvice14 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(@BindReturn Object value, ThreadContext context) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 15)
    public static class SimpleRunAdvice15 {

        @OnReturn
        @SuppressWarnings("unused")
        public static void onReturn(@BindOptionalReturn Object value, ThreadContext context) {}
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 16)
    public static class SimpleRunAdvice16 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(ThreadContext context) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run",
            methodParameterTypes = {}, order = 17)
    public static class SimpleRunAdvice17 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(@BindReturn Object value, ThreadContext context) {
            return null;
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.Simple", methodName = "run*",
            methodParameterTypes = {}, order = 18)
    public static class SimpleRunAdvice18 {

        @OnReturn
        @SuppressWarnings("unused")
        public static @Nullable Object onReturn(@BindOptionalReturn Object value,
                ThreadContext context) {
            return null;
        }
    }
}

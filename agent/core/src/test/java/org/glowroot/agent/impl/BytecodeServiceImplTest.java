/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BytecodeServiceImplTest {

    // the classic "public static void main(String[])" is the bottom-most frame on the stack
    @Test
    public void shouldNotIgnoreClassicStaticMain() {
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                frame("org.glowroot.agent.bytecode.api.Bytecode", "enteringMainMethod"),
                frame("com.example.App", "main"),
        };
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.App", "main", stackTrace))
                .isFalse();
    }

    // the Java 25 (JEP 512) launch protocol invokes instance / no-arg main methods through
    // sun.launcher / java.lang.reflect frames, so the main method is no longer the bottom-most frame
    @Test
    public void shouldNotIgnoreJava25MainBelowLauncherFrames() {
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                frame("org.glowroot.agent.bytecode.api.Bytecode", "enteringMainMethod"),
                frame("com.example.App", "main"),
                frame("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke"),
                frame("java.lang.reflect.Method", "invoke"),
                frame("sun.launcher.LauncherHelper", "executeMainClass"),
        };
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.App", "main", stackTrace))
                .isFalse();
    }

    // a "main" (or "start*") method that is invoked from deeper in the application is not the entry
    // point and must be ignored - there is application (non-JDK) code below it on the stack
    @Test
    public void shouldIgnoreMainCalledFromApplicationCode() {
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                frame("org.glowroot.agent.bytecode.api.Bytecode", "enteringMainMethod"),
                frame("com.example.Helper", "main"),
                frame("com.example.Service", "run"),
                frame("com.example.RealApp", "main"),
        };
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.Helper", "main", stackTrace))
                .isTrue();
    }

    @Test
    public void shouldIgnoreWhenExpectedMainNotOnStack() {
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                frame("org.glowroot.agent.bytecode.api.Bytecode", "enteringMainMethod"),
                frame("com.example.Other", "run"),
                frame("com.example.RealApp", "main"),
        };
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.App", "main", stackTrace))
                .isTrue();
    }

    @Test
    public void shouldIgnoreWhenMethodNameDoesNotMatch() {
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                frame("com.example.App", "notMain"),
        };
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.App", "main", stackTrace))
                .isTrue();
    }

    @Test
    public void shouldIgnoreEmptyStackTrace() {
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.App", "main",
                new StackTraceElement[0])).isTrue();
    }

    // procrun start* methods reuse the same entry-point logic with the start method name
    @Test
    public void shouldNotIgnoreProcrunStartMethodAsEntryPoint() {
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                frame("org.glowroot.agent.bytecode.api.Bytecode",
                        "enteringPossibleProcrunStartMethod"),
                frame("com.example.App", "start"),
        };
        assertThat(BytecodeServiceImpl.ignoreMainClass("com.example.App", "start", stackTrace))
                .isFalse();
    }

    private static StackTraceElement frame(String className, String methodName) {
        return new StackTraceElement(className, methodName, null, 0);
    }
}

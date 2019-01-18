/*
 * Copyright 2018-2019 the original author or authors.
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
package org.glowroot.agent.bytecode.api;

import org.checkerframework.checker.nullness.qual.Nullable;

public class BytecodeServiceHolder {

    private static volatile @Nullable BytecodeService service;

    private static volatile boolean glowrootFailedToStart;

    private BytecodeServiceHolder() {}

    public static BytecodeService get() {
        if (service == null) {
            ClassLoader loader = BytecodeServiceHolder.class.getClassLoader();
            if (loader == null) {
                throw new RuntimeException("Bytecode service retrieved too early");
            } else {
                throw new RuntimeException(
                        "Bytecode service retrieved from class loader: " + loader);
            }
        } else {
            return service;
        }
    }

    public static void set(BytecodeService service) {
        BytecodeServiceHolder.service = service;
    }

    public static void setGlowrootFailedToStart() {
        BytecodeServiceHolder.glowrootFailedToStart = true;
    }

    static boolean isGlowrootFailedToStart() {
        return glowrootFailedToStart;
    }
}

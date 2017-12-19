/*
 * Copyright 2017 the original author or authors.
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

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.JavaVersion;

import static com.google.common.base.Preconditions.checkNotNull;

public class Java9 {

    private static final Logger logger = LoggerFactory.getLogger(Java9.class);

    private static final @Nullable Java9 instance;

    private final Method getModuleMethod;
    private final Object glowrootModule;
    private final Class<?> moduleClass;
    private final Method redefineModuleMethod;

    static {
        if (JavaVersion.isGreaterThanOrEqualToJava9()) {
            instance = new Java9();
        } else {
            instance = null;
        }
    }

    public static Object getModule(Class<?> clazz) throws Exception {
        checkNotNull(instance);
        return instance.getModuleInternal(clazz);
    }

    public static void grantAccessToGlowroot(Instrumentation instrumentation, Object module)
            throws Exception {
        checkNotNull(instance);
        instance.grantAccessToGlowrootInternal(instrumentation, module);
    }

    public static void grantAccess(Instrumentation instrumentation, String fromClassName,
            String toClassName, boolean toClassMayNotExist) throws Exception {
        checkNotNull(instance);
        instance.grantAccessInternal(instrumentation, fromClassName, toClassName,
                toClassMayNotExist);
    }

    private Java9() {
        try {
            getModuleMethod = Class.class.getMethod("getModule");
            // getModule() always returns non-null
            glowrootModule = checkNotNull(getModuleMethod.invoke(Java9.class));
            moduleClass = Class.forName("java.lang.Module");
            redefineModuleMethod = Instrumentation.class.getMethod("redefineModule",
                    moduleClass, Set.class, Map.class, Map.class, Set.class, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object getModuleInternal(Class<?> clazz) throws Exception {
        // getModule() always returns non-null
        return checkNotNull(getModuleMethod.invoke(clazz));
    }

    private void grantAccessToGlowrootInternal(Instrumentation instrumentation, Object module)
            throws Exception {
        redefineModuleMethod.invoke(instrumentation, module, ImmutableSet.of(glowrootModule),
                ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of());
    }

    private void grantAccessInternal(Instrumentation instrumentation, String fromClassName,
            String toClassName, boolean toClassMayNotExist) throws Exception {
        Class<?> fromClass;
        try {
            fromClass = Class.forName(fromClassName);
        } catch (ClassNotFoundException e) {
            logger.error(e.getMessage(), e);
            return;
        }
        Class<?> toClass;
        try {
            toClass = Class.forName(toClassName);
        } catch (ClassNotFoundException e) {
            if (toClassMayNotExist) {
                logger.debug(e.getMessage(), e);
            } else {
                logger.error(e.getMessage(), e);
            }
            return;
        }
        Map<String, Set<?>> extraOpens;
        Package pkg = toClass.getPackage();
        if (pkg == null) {
            extraOpens = ImmutableMap.of();
        } else {
            // getModule() always returns non-null
            extraOpens = ImmutableMap.<String, Set<?>>of(pkg.getName(),
                    ImmutableSet.of(checkNotNull(getModuleMethod.invoke(fromClass))));
        }
        // getModule() always returns non-null
        redefineModuleMethod.invoke(instrumentation, checkNotNull(getModuleMethod.invoke(toClass)),
                ImmutableSet.of(), ImmutableMap.of(), extraOpens, ImmutableSet.of(),
                ImmutableMap.of());
    }
}

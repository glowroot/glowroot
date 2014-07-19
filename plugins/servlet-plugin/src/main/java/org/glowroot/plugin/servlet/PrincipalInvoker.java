/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import java.lang.reflect.Method;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PrincipalInvoker {

    private static final Logger logger = LoggerFactory.getLogger(PrincipalInvoker.class);

    @Nullable
    private final Method getNameMethod;

    public PrincipalInvoker(Class<?> clazz) {
        Class<?> principalClass = null;
        try {
            principalClass = Class.forName("java.security.Principal", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getNameMethod = getMethod(principalClass, "getName");
    }

    @Nullable
    public String getName(Object principal) {
        if (getNameMethod == null) {
            return null;
        }
        try {
            return (String) getNameMethod.invoke(principal);
        } catch (Throwable t) {
            logger.warn("error calling Principal.getName()", t);
            return "<error calling Principal.getName()>";
        }
    }

    @Nullable
    private static Method getMethod(@Nullable Class<?> clazz, String methodName,
            Class<?>... parameterTypes) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }
}

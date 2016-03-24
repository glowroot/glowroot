/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

public class Invokers {

    private static final Logger logger = Agent.getLogger(Invokers.class);

    private Invokers() {}

    static @Nullable Method getMethod(@Nullable Class<?> clazz, String methodName) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getMethod(methodName);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }

    static @Nullable Field getField(@Nullable Class<?> clazz, String fieldName) {
        if (clazz == null) {
            return null;
        }
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T invoke(@Nullable Method method, Object obj, T defaultValue) {
        if (method == null) {
            return defaultValue;
        }
        try {
            Object value = method.invoke(obj);
            if (value == null) {
                return defaultValue;
            }
            return (T) value;
        } catch (Throwable t) {
            logger.warn("error calling {}.{}()", method.getDeclaringClass().getName(),
                    method.getName(), t);
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T get(@Nullable Field field, Object obj, T defaultValue) {
        if (field == null) {
            return defaultValue;
        }
        try {
            Object value = field.get(obj);
            if (value == null) {
                return defaultValue;
            }
            return (T) value;
        } catch (Throwable t) {
            logger.warn("error calling {}.{}()", field.getDeclaringClass().getName(),
                    field.getName(), t);
            return defaultValue;
        }
    }
}

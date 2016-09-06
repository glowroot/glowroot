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
package org.glowroot.agent.plugin.httpclient;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.util.Reflection;

public class OkHttpClientCallInvoker {

    private static final Logger logger = Agent.getLogger(OkHttpClientCallInvoker.class);

    private final @Nullable Field originalRequestField;

    public OkHttpClientCallInvoker(Class<?> clazz) {
        originalRequestField = getRequestField(clazz);
    }

    @Nullable
    Object getOriginalRequest(Object call) {
        if (originalRequestField == null) {
            return null;
        }
        return Reflection.getFieldValue(originalRequestField, call);
    }

    private static @Nullable Field getRequestField(Class<?> clazz) {
        Class<?> callClass = getCallClass(clazz);
        if (callClass == null) {
            return null;
        }
        try {
            Field field = clazz.getDeclaredField("originalRequest");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            // okhttp version prior to 2.2.0
            Field field;
            try {
                field = clazz.getDeclaredField("request");
                field.setAccessible(true);
                return field;
            } catch (Exception f) {
                // log outer exception at warn level, inner exception at debug level
                logger.warn(e.getMessage(), e);
                logger.debug(f.getMessage(), f);
            }
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }

    private static @Nullable Class<?> getCallClass(Class<?> clazz) {
        try {
            return Class.forName("com.squareup.okhttp.Call", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }
}

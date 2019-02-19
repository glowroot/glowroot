/*
 * Copyright 2016-2019 the original author or authors.
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
package org.glowroot.agent.plugin.httpclient._;

import java.lang.reflect.Field;

import org.glowroot.agent.plugin.api.ClassInfo;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Reflection;

public class OkHttpClientCallInvoker {

    private static final Logger logger = Logger.getLogger(OkHttpClientCallInvoker.class);

    private final @Nullable Field originalRequestField;

    public OkHttpClientCallInvoker(ClassInfo classInfo) {
        originalRequestField = getRequestField(classInfo.getLoader());
    }

    public @Nullable Object getOriginalRequest(Object call) {
        if (originalRequestField == null) {
            return null;
        }
        return Reflection.getFieldValue(originalRequestField, call);
    }

    private static @Nullable Field getRequestField(@Nullable ClassLoader loader) {
        Class<?> callClass =
                Reflection.getClassWithWarnIfNotFound("com.squareup.okhttp.Call", loader);
        if (callClass == null) {
            return null;
        }
        try {
            Field field = callClass.getDeclaredField("originalRequest");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            // okhttp version prior to 2.2.0
            Field field;
            try {
                field = callClass.getDeclaredField("request");
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
}

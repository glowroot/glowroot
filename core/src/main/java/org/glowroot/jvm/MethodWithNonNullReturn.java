/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.jvm;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class MethodWithNonNullReturn {

    private static final Logger logger = LoggerFactory.getLogger(MethodWithNonNullReturn.class);

    private final Method method;
    private final Object fallbackReturnValue;

    MethodWithNonNullReturn(Method method, Object fallbackReturnValue) {
        this.method = method;
        this.fallbackReturnValue = fallbackReturnValue;
    }

    Object invoke(Object obj, Object... args) {
        try {
            Object returnValue = Reflections.invoke(method, obj, args);
            if (returnValue == null) {
                logger.error("method unexpectedly returned null: {}", method);
                return fallbackReturnValue;
            }
            return returnValue;
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
            return fallbackReturnValue;
        }
    }
}

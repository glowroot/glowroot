/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.play;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.util.Reflection;
import org.glowroot.agent.plugin.play.Play2xAspect.HandlerDef;

public class PlayInvoker {

    private static final Logger logger = Agent.getLogger(PlayInvoker.class);

    private final @Nullable Method pathMethod;
    private final @Nullable Field actionField;

    public PlayInvoker(Class<?> clazz) {
        Class<?> handlerDefClass = getHandlerDefClass(clazz);
        pathMethod = Reflection.getMethod(handlerDefClass, "path");
        Class<?> requestClass = getRequestClass(clazz);
        actionField = Reflection.getDeclaredField(requestClass, "action");
    }

    @Nullable
    String path(HandlerDef handlerDef) {
        return Reflection.invoke(pathMethod, handlerDef);
    }

    @Nullable
    String getAction(Object request) {
        return (String) Reflection.getFieldValue(actionField, request);
    }

    private static @Nullable Class<?> getHandlerDefClass(Class<?> clazz) {
        try {
            return Class.forName("play.core.Router$HandlerDef", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
        }
        return null;
    }

    private static @Nullable Class<?> getRequestClass(Class<?> clazz) {
        try {
            return Class.forName("play.mvc.Http$Request", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
        }
        return null;
    }
}

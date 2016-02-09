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
package org.glowroot.agent.plugin.spring;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

public class ControllerMethodMeta {

    private static final Logger logger = Agent.getLogger(ControllerMethodMeta.class);

    private final String declaredClassSimpleName;
    private final String methodName;

    private final String path;

    public ControllerMethodMeta(Method method) {
        declaredClassSimpleName = method.getDeclaringClass().getSimpleName();
        methodName = method.getName();
        String classPath = getPath(method.getDeclaringClass());
        String methodPath = getPath(method);
        path = combine(classPath, methodPath);
    }

    String getDeclaredClassSimpleName() {
        return declaredClassSimpleName;
    }

    String getMethodName() {
        return methodName;
    }

    String getPath() {
        return path;
    }

    private static @Nullable String getPath(AnnotatedElement annotatedElement) {
        try {
            for (Annotation annotation : annotatedElement.getDeclaredAnnotations()) {
                Class<?> annotationClass = annotation.annotationType();
                if (annotationClass.getName()
                        .equals("org.springframework.web.bind.annotation.RequestMapping")) {
                    return getRequestMappingAttribute(annotationClass, annotation, "value");
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        return null;
    }

    private static @Nullable String getRequestMappingAttribute(Class<?> requestMappingClass,
            Object requestMapping, String attributeName) throws Exception {
        Method method = requestMappingClass.getMethod(attributeName);
        String[] values = (String[]) method.invoke(requestMapping);
        if (values == null || values.length == 0) {
            return null;
        }
        // TODO handle more than one value
        return values[0];
    }

    // VisibleForTesting
    static String combine(@Nullable String classPath, @Nullable String methodPath) {
        if (classPath == null || classPath.isEmpty() || classPath.equals("/")) {
            return normalize(methodPath);
        }
        if (methodPath == null || methodPath.isEmpty() || methodPath.equals("/")) {
            return normalize(classPath);
        }
        return normalize(classPath) + normalize(methodPath);
    }

    private static String normalize(@Nullable String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "/";
        }
        boolean addLeadingSlash = path.charAt(0) != '/';
        if (addLeadingSlash) {
            return '/' + replacePathSegmentsWithAsterisk(path);
        } else {
            return replacePathSegmentsWithAsterisk(path);
        }
    }

    private static String replacePathSegmentsWithAsterisk(String path) {
        return path.replaceAll("\\{[^}]*\\}", "*");
    }
}

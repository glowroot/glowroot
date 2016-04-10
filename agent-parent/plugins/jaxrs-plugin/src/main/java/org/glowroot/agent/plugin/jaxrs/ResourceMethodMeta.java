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
package org.glowroot.agent.plugin.jaxrs;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

public class ResourceMethodMeta {

    private static final Logger logger = Agent.getLogger(ResourceMethodMeta.class);

    private final String resourceClassName;
    private final String methodName;

    private final String path;

    private final String altTransactionName;

    public ResourceMethodMeta(Method method) {
        Class<?> resourceClass = method.getDeclaringClass();
        resourceClassName = resourceClass.getName();
        methodName = method.getName();
        String classPath = getPath(resourceClass);
        String methodPath = getPath(method);
        path = combine(classPath, methodPath);
        altTransactionName = resourceClass.getSimpleName() + "#" + methodName;
    }

    String getResourceClassName() {
        return resourceClassName;
    }

    String getMethodName() {
        return methodName;
    }

    String getPath() {
        return path;
    }

    String getAltTransactionName() {
        return altTransactionName;
    }

    private static @Nullable String getPath(AnnotatedElement annotatedElement) {
        try {
            for (Annotation annotation : annotatedElement.getDeclaredAnnotations()) {
                Class<?> annotationClass = annotation.annotationType();
                if (annotationClass.getName().equals("javax.ws.rs.Path")) {
                    return getPathAttribute(annotationClass, annotation, "value");
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        return null;
    }

    private static @Nullable String getPathAttribute(Class<?> pathClass, Object path,
            String attributeName) throws Exception {
        Method method = pathClass.getMethod(attributeName);
        return (String) method.invoke(path);
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

/*
 * Copyright 2016-2018 the original author or authors.
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

import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.MethodInfo;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Reflection;

public class ResourceMethodMeta {

    private static final Logger logger = Logger.getLogger(ResourceMethodMeta.class);

    private final String resourceClassName;
    private final String methodName;

    private final String path;

    private final String altTransactionName;

    public ResourceMethodMeta(MethodInfo methodInfo) {
        resourceClassName = methodInfo.getDeclaringClassName();
        methodName = methodInfo.getName();
        String classPath = getPath(getClass(methodInfo));
        String methodPath = getPath(getMethod(methodInfo));
        path = combine(classPath, methodPath);
        altTransactionName = getSimpleName(resourceClassName) + "#" + methodName;
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

    private static @Nullable String getPath(@Nullable AnnotatedElement annotatedElement) {
        if (annotatedElement == null) {
            return null;
        }
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

    private static @Nullable Class<?> getClass(MethodInfo methodInfo) {
        return Reflection.getClass(methodInfo.getDeclaringClassName(), methodInfo.getLoader());
    }

    private static @Nullable Method getMethod(MethodInfo methodInfo) {
        Class<?> declaringClass =
                Reflection.getClass(methodInfo.getDeclaringClassName(), methodInfo.getLoader());
        if (declaringClass == null) {
            // declaring class is probably a lambda class
            return null;
        }
        Class<?>[] parameterTypes = methodInfo.getParameterTypes()
                .toArray(new Class<?>[methodInfo.getParameterTypes().size()]);
        try {
            return declaringClass.getDeclaredMethod(methodInfo.getName(), parameterTypes);
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
        }
        return null;
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

    private static String getSimpleName(String className) {
        return substringAfterLast(substringAfterLast(className, '.'), '$');
    }

    private static String substringAfterLast(String str, char c) {
        int index = str.lastIndexOf(c);
        if (index == -1) {
            return str;
        } else {
            return str.substring(index + 1);
        }
    }
}

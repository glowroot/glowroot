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
package org.glowroot.agent.plugin.jaxrs;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.MethodInfo;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Reflection;

// TODO, from JAX-RS spec "If a subclass or implementation method has any JAX-RS annotations then
// all of the annotations on the superclass or interface method are ignored"
public class ResourceMethodMeta {

    private static final Logger logger = Logger.getLogger(ResourceMethodMeta.class);

    private final String resourceClassName;
    private final String methodName;
    private final boolean hasHttpMethodAnnotation;

    private final String path;

    private final String altTransactionName;

    private final boolean hasClassPathAnnotation;

    public ResourceMethodMeta(MethodInfo methodInfo) {
        resourceClassName = methodInfo.getDeclaringClassName();
        methodName = methodInfo.getName();
        Class<?> clazz = getClass(methodInfo);
        String classPath = getPath(clazz);
        MethodAnnotations methodAnnotations = getMethodAnnotations(methodInfo, clazz);

        if (methodAnnotations == null) {
            hasHttpMethodAnnotation = false;
            path = combine(classPath, null);
        } else {
            hasHttpMethodAnnotation = methodAnnotations.hasHttpMethodAnnotation;
            path = combine(classPath, methodAnnotations.pathAnnotation);
        }
        altTransactionName = getSimpleName(resourceClassName) + "#" + methodName;

        hasClassPathAnnotation = classPath != null;
    }

    String getResourceClassName() {
        return resourceClassName;
    }

    String getMethodName() {
        return methodName;
    }

    boolean hasHttpMethodAnnotation() {
        return hasHttpMethodAnnotation;
    }

    String getPath() {
        return path;
    }

    String getAltTransactionName() {
        return altTransactionName;
    }

    boolean hasClassPathAnnotation() {
        return hasClassPathAnnotation;
    }

    private static @Nullable String getPath(@Nullable Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        try {
            for (Annotation annotation : clazz.getDeclaredAnnotations()) {
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

    private static @Nullable MethodAnnotations getMethodAnnotations(MethodInfo methodInfo,
            @Nullable Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        MethodAnnotations methodAnnotations = getMethodAnnotations(getMethod(methodInfo, clazz));
        if (methodAnnotations != null) {
            return methodAnnotations;
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            methodAnnotations = getMethodAnnotations(methodInfo, superclass);
            if (methodAnnotations != null) {
                return methodAnnotations;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            methodAnnotations = getMethodAnnotations(methodInfo, iface);
            if (methodAnnotations != null) {
                return methodAnnotations;
            }
        }
        return null;
    }

    private static @Nullable MethodAnnotations getMethodAnnotations(@Nullable Method method) {
        if (method == null) {
            return null;
        }
        try {
            String pathAnnotation = null;
            boolean hasHttpMethodAnnotation = false;
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                Class<?> annotationClass = annotation.annotationType();
                if (annotationClass.getName().equals("javax.ws.rs.Path")) {
                    pathAnnotation = getPathAttribute(annotationClass, annotation, "value");
                } else if (isHttpMethodAnnotation(annotationClass)) {
                    hasHttpMethodAnnotation = true;
                }
            }
            if (pathAnnotation != null || hasHttpMethodAnnotation) {
                return new MethodAnnotations(pathAnnotation, hasHttpMethodAnnotation);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        return null;
    }

    private static boolean isHttpMethodAnnotation(Class<?> annotationClass) {
        for (Annotation annotation : annotationClass.getDeclaredAnnotations()) {
            Class<?> metaClass = annotation.annotationType();
            if (metaClass.getName().equals("javax.ws.rs.HttpMethod")) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String getPathAttribute(Class<?> pathClass, Object path,
            String attributeName) throws Exception {
        Method method = pathClass.getMethod(attributeName);
        return (String) method.invoke(path);
    }

    private static @Nullable Class<?> getClass(MethodInfo methodInfo) {
        return Reflection.getClass(methodInfo.getDeclaringClassName(), methodInfo.getLoader());
    }

    private static @Nullable Method getMethod(MethodInfo methodInfo,
            @Nullable Class<?> declaringClass) {
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

    private static class MethodAnnotations {

        private final @Nullable String pathAnnotation;
        private final boolean hasHttpMethodAnnotation;

        private MethodAnnotations(@Nullable String pathAnnotation,
                boolean hasHttpMethodAnnotation) {
            this.pathAnnotation = pathAnnotation;
            this.hasHttpMethodAnnotation = hasHttpMethodAnnotation;
        }
    }
}

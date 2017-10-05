/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindMethodMeta;
import org.glowroot.agent.plugin.api.weaving.BindMethodName;
import org.glowroot.agent.plugin.api.weaving.BindOptionalReturn;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindParameterArray;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.weaving.Advice.AdviceParameter;
import org.glowroot.agent.weaving.Advice.ParameterKind;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.common.util.Patterns;

import static com.google.common.base.Preconditions.checkNotNull;

class AdviceBuilder {

    private static final ImmutableList<Class<? extends Annotation>> isEnabledBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindParameter.class, BindParameterArray.class,
                    BindMethodName.class, BindClassMeta.class, BindMethodMeta.class);
    private static final ImmutableList<Class<? extends Annotation>> onBeforeBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindParameter.class, BindParameterArray.class,
                    BindMethodName.class, BindClassMeta.class, BindMethodMeta.class);
    private static final ImmutableList<Class<? extends Annotation>> onReturnBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindParameter.class, BindParameterArray.class,
                    BindMethodName.class, BindReturn.class, BindOptionalReturn.class,
                    BindTraveler.class, BindClassMeta.class, BindMethodMeta.class);
    private static final ImmutableList<Class<? extends Annotation>> onThrowBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindParameter.class, BindParameterArray.class,
                    BindMethodName.class, BindThrowable.class, BindTraveler.class,
                    BindClassMeta.class, BindMethodMeta.class);
    private static final ImmutableList<Class<? extends Annotation>> onAfterBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindParameter.class, BindParameterArray.class,
                    BindMethodName.class, BindTraveler.class, BindClassMeta.class,
                    BindMethodMeta.class);

    private static final ImmutableMap<Class<? extends Annotation>, ParameterKind> parameterKindMap =
            new ImmutableMap.Builder<Class<? extends Annotation>, ParameterKind>()
                    .put(BindReceiver.class, ParameterKind.RECEIVER)
                    .put(BindParameter.class, ParameterKind.METHOD_ARG)
                    .put(BindParameterArray.class, ParameterKind.METHOD_ARG_ARRAY)
                    .put(BindMethodName.class, ParameterKind.METHOD_NAME)
                    .put(BindReturn.class, ParameterKind.RETURN)
                    .put(BindOptionalReturn.class, ParameterKind.OPTIONAL_RETURN)
                    .put(BindThrowable.class, ParameterKind.THROWABLE)
                    .put(BindTraveler.class, ParameterKind.TRAVELER)
                    .put(BindClassMeta.class, ParameterKind.CLASS_META)
                    .put(BindMethodMeta.class, ParameterKind.METHOD_META)
                    .build();

    private final ImmutableAdvice.Builder builder = ImmutableAdvice.builder();

    private final @Nullable Class<?> adviceClass;
    private final @Nullable LazyDefinedClass lazyAdviceClass;

    private boolean hasIsEnabledAdvice;
    private boolean hasOnBeforeAdvice;
    private boolean hasOnReturnAdvice;
    private boolean hasOnThrowAdvice;
    private boolean hasOnAfterAdvice;

    AdviceBuilder(Class<?> adviceClass) {
        this.adviceClass = adviceClass;
        this.lazyAdviceClass = null;
        builder.reweavable(false);
    }

    AdviceBuilder(LazyDefinedClass lazyAdviceClass, boolean reweavable) {
        this.adviceClass = null;
        this.lazyAdviceClass = lazyAdviceClass;
        builder.reweavable(reweavable);
    }

    Advice build() throws Exception {
        Class<?> adviceClass = this.adviceClass;
        if (adviceClass == null) {
            // safe check, if adviceClass is null then lazyAdviceClass is non-null
            checkNotNull(lazyAdviceClass);
            ClassLoader tempClassLoader =
                    AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                        @Override
                        public ClassLoader run() {
                            return new URLClassLoader(new URL[0],
                                    AdviceBuilder.class.getClassLoader());
                        }
                    });
            adviceClass = ClassLoaders.defineClass(lazyAdviceClass, tempClassLoader);
        }
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        checkNotNull(pointcut, "Class has no @Pointcut annotation");
        builder.pointcut(pointcut);
        builder.adviceType(Type.getType(adviceClass));
        builder.pointcutClassNamePattern(buildPattern(pointcut.className()));
        builder.pointcutClassAnnotationPattern(buildPattern(pointcut.classAnnotation()));
        builder.pointcutSubTypeRestrictionPattern(buildPattern(pointcut.subTypeRestriction()));
        builder.pointcutSuperTypeRestrictionPattern(buildPattern(pointcut.superTypeRestriction()));
        builder.pointcutMethodNamePattern(buildPattern(pointcut.methodName()));
        builder.pointcutMethodAnnotationPattern(buildPattern(pointcut.methodAnnotation()));
        builder.pointcutMethodParameterTypes(buildPatterns(pointcut.methodParameterTypes()));

        // hasBindThreadContext will be overridden below if needed
        builder.hasBindThreadContext(false);
        // hasBindOptionalThreadContext will be overridden below if needed
        builder.hasBindOptionalThreadContext(false);
        for (java.lang.reflect.Method method : adviceClass.getMethods()) {
            if (method.isAnnotationPresent(IsEnabled.class)) {
                initIsEnabledAdvice(adviceClass, method);
            } else if (method.isAnnotationPresent(OnBefore.class)) {
                initOnBeforeAdvice(adviceClass, method);
            } else if (method.isAnnotationPresent(OnReturn.class)) {
                initOnReturnAdvice(adviceClass, method);
            } else if (method.isAnnotationPresent(OnThrow.class)) {
                initOnThrowAdvice(adviceClass, method);
            } else if (method.isAnnotationPresent(OnAfter.class)) {
                initOnAfterAdvice(adviceClass, method);
            }
        }
        return builder.build();
    }

    private void initIsEnabledAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        checkState(!hasIsEnabledAdvice,
                "@Pointcut '" + adviceClass.getName() + "' has more than one @IsEnabled method");
        Method asmMethod = Method.getMethod(method);
        checkState(asmMethod.getReturnType().getSort() == Type.BOOLEAN,
                "@IsEnabled method must return boolean");
        builder.isEnabledAdvice(asmMethod);
        List<AdviceParameter> parameters = getAdviceParameters(method.getParameterAnnotations(),
                method.getParameterTypes(), isEnabledBindAnnotationTypes, IsEnabled.class);
        builder.addAllIsEnabledParameters(parameters);
        hasIsEnabledAdvice = true;
    }

    private void initOnBeforeAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        checkState(!hasOnBeforeAdvice,
                "@Pointcut '" + adviceClass.getName() + "' has more than one @OnBefore method");
        Method onBeforeAdvice = Method.getMethod(method);
        builder.onBeforeAdvice(onBeforeAdvice);
        List<AdviceParameter> parameters = getAdviceParameters(method.getParameterAnnotations(),
                method.getParameterTypes(), onBeforeBindAnnotationTypes, OnBefore.class);
        builder.addAllOnBeforeParameters(parameters);
        if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
            builder.travelerType(onBeforeAdvice.getReturnType());
        }
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnBeforeAdvice = true;
    }

    private void initOnReturnAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        checkState(!hasOnReturnAdvice,
                "@Pointcut '" + adviceClass.getName() + "' has more than one @OnReturn method");
        List<AdviceParameter> parameters = getAdviceParameters(method.getParameterAnnotations(),
                method.getParameterTypes(), onReturnBindAnnotationTypes, OnReturn.class);
        for (int i = 1; i < parameters.size(); i++) {
            checkState(parameters.get(i).kind() != ParameterKind.RETURN,
                    "@BindReturn must be the first argument to @OnReturn");
            checkState(parameters.get(i).kind() != ParameterKind.OPTIONAL_RETURN,
                    "@BindOptionalReturn must be the first argument to @OnReturn");
        }
        builder.onReturnAdvice(Method.getMethod(method));
        builder.addAllOnReturnParameters(parameters);
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnReturnAdvice = true;
    }

    private void initOnThrowAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        checkState(!hasOnThrowAdvice,
                "@Pointcut '" + adviceClass.getName() + "' has more than one @OnThrow method");
        List<AdviceParameter> parameters = getAdviceParameters(method.getParameterAnnotations(),
                method.getParameterTypes(), onThrowBindAnnotationTypes, OnThrow.class);
        for (int i = 1; i < parameters.size(); i++) {
            checkState(parameters.get(i).kind() != ParameterKind.THROWABLE,
                    "@BindThrowable must be the first argument to @OnThrow");
        }
        Method asmMethod = Method.getMethod(method);
        checkState(asmMethod.getReturnType().getSort() == Type.VOID,
                "@OnThrow method must return void (for now)");
        builder.onThrowAdvice(asmMethod);
        builder.addAllOnThrowParameters(parameters);
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnThrowAdvice = true;
    }

    private void initOnAfterAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        checkState(!hasOnAfterAdvice,
                "@Pointcut '" + adviceClass.getName() + "' has more than one @OnAfter method");
        Method asmMethod = Method.getMethod(method);
        checkState(asmMethod.getReturnType().getSort() == Type.VOID,
                "@OnAfter method must return void");
        builder.onAfterAdvice(asmMethod);
        List<AdviceParameter> parameters = getAdviceParameters(method.getParameterAnnotations(),
                method.getParameterTypes(), onAfterBindAnnotationTypes, OnAfter.class);
        builder.addAllOnAfterParameters(parameters);
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnAfterAdvice = true;
    }

    private void checkForBindThreadContext(List<AdviceParameter> parameters) {
        for (AdviceParameter parameter : parameters) {
            if (parameter.kind() == ParameterKind.THREAD_CONTEXT) {
                builder.hasBindThreadContext(true);
                return;
            }
        }
    }

    private void checkForBindOptionalThreadContext(List<AdviceParameter> parameters) {
        for (AdviceParameter parameter : parameters) {
            if (parameter.kind() == ParameterKind.OPTIONAL_THREAD_CONTEXT) {
                builder.hasBindOptionalThreadContext(true);
                break;
            }
        }
    }

    private static void checkState(boolean condition, String message)
            throws AdviceConstructionException {
        if (!condition) {
            throw new AdviceConstructionException(message);
        }
    }

    private static List<Object> buildPatterns(String[] maybePatterns) {
        List<Object> patterns = Lists.newArrayList();
        for (String maybePattern : maybePatterns) {
            Pattern pattern = buildPattern(maybePattern);
            if (pattern == null) {
                patterns.add(maybePattern);
            } else {
                patterns.add(pattern);
            }
        }
        return patterns;
    }

    private static @Nullable Pattern buildPattern(String maybePattern) {
        if (maybePattern.startsWith("/") && maybePattern.endsWith("/")) {
            // full regex power
            return Pattern.compile(maybePattern.substring(1, maybePattern.length() - 1));
        }
        // limited regex, | and *, should be used whenever possible over full regex since
        // . and $ are common in class names
        if (maybePattern.contains("|")) {
            String[] parts = maybePattern.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = Patterns.buildSimplePattern(parts[i]);
            }
            return Pattern.compile(Joiner.on('|').join(parts));
        }
        if (maybePattern.contains("*")) {
            return Pattern.compile(Patterns.buildSimplePattern(maybePattern));
        }
        return null;
    }

    private static List<AdviceParameter> getAdviceParameters(Annotation[][] parameterAnnotations,
            Class<?>[] parameterTypes, List<Class<? extends Annotation>> validBindAnnotationTypes,
            Class<? extends Annotation> adviceAnnotationType) throws AdviceConstructionException {

        List<AdviceParameter> parameters = Lists.newArrayList();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            if (parameterTypes[i] == ThreadContext.class) {
                parameters.add(ImmutableAdviceParameter.builder()
                        .kind(ParameterKind.THREAD_CONTEXT)
                        .type(Type.getType(ThreadContext.class))
                        .build());
                continue;
            }
            if (parameterTypes[i] == OptionalThreadContext.class) {
                parameters.add(ImmutableAdviceParameter.builder()
                        .kind(ParameterKind.OPTIONAL_THREAD_CONTEXT)
                        .type(Type.getType(OptionalThreadContext.class))
                        .build());
                continue;
            }
            Class<? extends Annotation> validBindAnnotationType =
                    getValidBindAnnotationType(parameterAnnotations[i], validBindAnnotationTypes);
            if (validBindAnnotationType == null) {
                // no valid bind annotations found, provide a good error message
                List<String> validBindAnnotationNames = Lists.newArrayList();
                for (Class<? extends Annotation> annotationType : validBindAnnotationTypes) {
                    validBindAnnotationNames.add("@" + annotationType.getSimpleName());
                }
                throw new AdviceConstructionException("All parameters to @"
                        + adviceAnnotationType.getSimpleName() + " must be annotated with one"
                        + " of " + Joiner.on(", ").join(validBindAnnotationNames));
            }
            parameters.add(getAdviceParameter(validBindAnnotationType, parameterTypes[i]));
        }
        return parameters;
    }

    private static @Nullable Class<? extends Annotation> getValidBindAnnotationType(
            Annotation[] parameterAnnotations,
            List<Class<? extends Annotation>> validBindAnnotationTypes)
            throws AdviceConstructionException {

        Class<? extends Annotation> foundBindAnnotationType = null;
        for (Annotation annotation : parameterAnnotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (!parameterKindMap.containsKey(annotationType)) {
                continue;
            }
            checkState(foundBindAnnotationType == null,
                    "Multiple annotations found on a single parameter");
            checkState(validBindAnnotationTypes.contains(annotationType),
                    "Annotation '" + annotationType.getName() + "' found in an invalid location");
            foundBindAnnotationType = annotationType;
        }
        return foundBindAnnotationType;
    }

    private static AdviceParameter getAdviceParameter(
            Class<? extends Annotation> validBindAnnotationType, Class<?> parameterType)
            throws AdviceConstructionException {

        checkState(
                validBindAnnotationType != BindMethodName.class
                        || parameterType.isAssignableFrom(String.class),
                "@BindMethodName parameter type must be"
                        + " java.lang.String (or super type of java.lang.String)");
        checkState(
                validBindAnnotationType != BindThrowable.class
                        || parameterType.isAssignableFrom(Throwable.class),
                "@BindMethodName parameter type must be"
                        + " java.lang.Throwable (or super type of java.lang.Throwable)");
        ParameterKind parameterKind = parameterKindMap.get(validBindAnnotationType);
        // parameterKind should never be null since all bind annotations have a mapping in
        // parameterKindMap
        checkNotNull(parameterKind,
                "Annotation not found in parameterKindMap: " + validBindAnnotationType.getName());
        return ImmutableAdviceParameter.builder()
                .kind(parameterKind)
                .type(Type.getType(parameterType))
                .build();
    }

    @SuppressWarnings("serial")
    private static class AdviceConstructionException extends Exception {
        private AdviceConstructionException(@Nullable String message) {
            super(message);
        }
    }
}

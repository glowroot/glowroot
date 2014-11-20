/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import org.glowroot.api.weaving.BindClassMeta;
import org.glowroot.api.weaving.BindMethodMeta;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindOptionalReturn;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindParameterArray;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.weaving.Advice.AdviceParameter;
import org.glowroot.weaving.Advice.ParameterKind;

import static com.google.common.base.Preconditions.checkNotNull;

public class AdviceBuilder {

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

    public AdviceBuilder(Class<?> adviceClass, boolean reweavable) {
        this.adviceClass = adviceClass;
        this.lazyAdviceClass = null;
        builder.reweavable(reweavable);
    }

    public AdviceBuilder(LazyDefinedClass lazyAdviceClass, boolean reweavable) {
        this.adviceClass = null;
        this.lazyAdviceClass = lazyAdviceClass;
        builder.reweavable(reweavable);
    }

    public Advice build() throws AdviceConstructionException {
        Class<?> adviceClass = this.adviceClass;
        if (adviceClass == null) {
            // safe check, if adviceClass is null then lazyAdviceClass is non-null
            checkNotNull(lazyAdviceClass);
            ClassLoader tempClassLoader = new URLClassLoader(new URL[0]);
            try {
                adviceClass = ClassLoaders.defineClass(lazyAdviceClass, tempClassLoader);
            } catch (ReflectiveException e) {
                throw new AdviceConstructionException(e);
            }
        }
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut == null) {
            throw new AdviceConstructionException(
                    "Class was generated without @Pointcut annotation");
        }
        builder.pointcut(pointcut);
        builder.adviceType(Type.getType(adviceClass));
        builder.pointcutClassNamePattern(buildPattern(pointcut.className()));
        builder.pointcutMethodNamePattern(buildPattern(pointcut.methodName()));
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
        if (hasIsEnabledAdvice) {
            throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                    + "' has more than one @IsEnabled method");
        }
        Method asmMethod = Method.getMethod(method);
        if (asmMethod.getReturnType().getSort() == Type.BOOLEAN) {
            builder.isEnabledAdvice(asmMethod);
            List<AdviceParameter> parameters = getAdviceParameters(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    isEnabledBindAnnotationTypes, IsEnabled.class);
            builder.addAllIsEnabledParameters(parameters);
            hasIsEnabledAdvice = true;
        } else {
            throw new AdviceConstructionException("@IsEnabled method must return boolean");
        }
    }

    private void initOnBeforeAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        if (hasOnBeforeAdvice) {
            throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                    + "' has more than one @OnBefore method");
        }
        Method onBeforeAdvice = Method.getMethod(method);
        builder.onBeforeAdvice(onBeforeAdvice);
        List<AdviceParameter> parameters = getAdviceParameters(
                method.getParameterAnnotations(), method.getParameterTypes(),
                onBeforeBindAnnotationTypes, OnBefore.class);
        builder.addAllOnBeforeParameters(parameters);
        if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
            builder.travelerType(onBeforeAdvice.getReturnType());
        }
        hasOnBeforeAdvice = true;
    }

    private void initOnReturnAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        if (hasOnReturnAdvice) {
            throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                    + "' has more than one @OnReturn method");
        }
        List<AdviceParameter> parameters = getAdviceParameters(
                method.getParameterAnnotations(), method.getParameterTypes(),
                onReturnBindAnnotationTypes, OnReturn.class);
        for (int i = 1; i < parameters.size(); i++) {
            if (parameters.get(i).kind() == ParameterKind.RETURN) {
                throw new AdviceConstructionException(
                        "@BindReturn must be the first argument to @OnReturn");
            }
            if (parameters.get(i).kind() == ParameterKind.OPTIONAL_RETURN) {
                throw new AdviceConstructionException(
                        "@BindOptionalReturn must be the first argument to @OnReturn");
            }
        }
        builder.onReturnAdvice(Method.getMethod(method));
        builder.addAllOnReturnParameters(parameters);
        hasOnReturnAdvice = true;
    }

    private void initOnThrowAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        if (hasOnThrowAdvice) {
            throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                    + "' has more than one @OnThrow method");
        }
        List<AdviceParameter> parameters = getAdviceParameters(
                method.getParameterAnnotations(), method.getParameterTypes(),
                onThrowBindAnnotationTypes, OnThrow.class);
        for (int i = 1; i < parameters.size(); i++) {
            if (parameters.get(i).kind() == ParameterKind.THROWABLE) {
                throw new AdviceConstructionException(
                        "@BindThrowable must be the first argument to @OnThrow");
            }
        }
        Method asmMethod = Method.getMethod(method);
        if (asmMethod.getReturnType().getSort() != Type.VOID) {
            throw new AdviceConstructionException("@OnThrow method must return void (for now)");
        }
        builder.onThrowAdvice(asmMethod);
        builder.addAllOnThrowParameters(parameters);
        hasOnThrowAdvice = true;
    }

    private void initOnAfterAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
            throws AdviceConstructionException {
        if (hasOnAfterAdvice) {
            throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                    + "' has more than one @OnAfter method");
        }
        Method asmMethod = Method.getMethod(method);
        if (asmMethod.getReturnType().getSort() != Type.VOID) {
            throw new AdviceConstructionException("@OnAfter method must return void");
        }
        builder.onAfterAdvice(asmMethod);
        List<AdviceParameter> parameters = getAdviceParameters(
                method.getParameterAnnotations(), method.getParameterTypes(),
                onAfterBindAnnotationTypes, OnAfter.class);
        builder.addAllOnAfterParameters(parameters);
        hasOnAfterAdvice = true;
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
                parts[i] = buildPatternPart(parts[i]);
            }
            return Pattern.compile(Joiner.on('|').join(parts));
        }
        if (maybePattern.contains("*")) {
            return Pattern.compile(buildPatternPart(maybePattern));
        }
        return null;
    }

    private static String buildPatternPart(String part) {
        // convert * into .* and quote the rest of the text using \Q...\E
        String pattern = "\\Q" + part.replace("*", "\\E.*\\Q") + "\\E";
        // strip off unnecessary \\Q\\E in case * appeared at beginning or end of part
        return pattern.replace("\\Q\\E", "");
    }

    private static List<AdviceParameter> getAdviceParameters(
            Annotation[][] parameterAnnotations, Class<?>[] parameterTypes,
            ImmutableList<Class<? extends Annotation>> validBindAnnotationTypes,
            Class<? extends Annotation> adviceAnnotationType)
            throws AdviceConstructionException {

        List<AdviceParameter> parameters = Lists.newArrayList();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            Class<? extends Annotation> validBindAnnotationType = getValidBindAnnotationType(
                    parameterAnnotations[i], validBindAnnotationTypes);
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
            ImmutableList<Class<? extends Annotation>> validBindAnnotationTypes)
            throws AdviceConstructionException {

        Class<? extends Annotation> foundBindAnnotationType = null;
        for (Annotation annotation : parameterAnnotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (!parameterKindMap.containsKey(annotationType)) {
                continue;
            }
            if (foundBindAnnotationType != null) {
                throw new AdviceConstructionException(
                        "Multiple annotations found on a single parameter");
            }
            if (validBindAnnotationTypes.contains(annotationType)) {
                foundBindAnnotationType = annotationType;
            } else {
                throw new AdviceConstructionException("Annotation '" + annotationType.getName()
                        + "' found in an invalid location");
            }
        }
        return foundBindAnnotationType;
    }

    private static AdviceParameter getAdviceParameter(
            Class<? extends Annotation> validBindAnnotationType,
            Class<?> parameterType) throws AdviceConstructionException {

        if (validBindAnnotationType == BindMethodName.class
                && !parameterType.isAssignableFrom(String.class)) {
            throw new AdviceConstructionException("@BindMethodName parameter type must be"
                    + " java.lang.String (or super type of java.lang.String)");
        }
        if (validBindAnnotationType == BindThrowable.class
                && !parameterType.isAssignableFrom(Throwable.class)) {
            throw new AdviceConstructionException("@BindMethodName parameter type must be"
                    + " java.lang.Throwable (or super type of java.lang.Throwable)");
        }
        ParameterKind parameterKind = parameterKindMap.get(validBindAnnotationType);
        if (parameterKind == null) {
            // not possible since all bind annotations have a mapping in parameterKindMap
            throw new AssertionError("Annotation not found in parameterKindMap: "
                    + validBindAnnotationType.getName());
        }
        return ImmutableAdviceParameter.builder()
                .kind(parameterKind)
                .type(Type.getType(parameterType))
                .build();
    }

    @SuppressWarnings("serial")
    public static class AdviceConstructionException extends Exception {
        private AdviceConstructionException(Throwable cause) {
            super(cause);
        }
        private AdviceConstructionException(@Nullable String message) {
            super(message);
        }
    }
}

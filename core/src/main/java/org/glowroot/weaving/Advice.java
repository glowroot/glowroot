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
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import org.glowroot.api.weaving.BindMethodArg;
import org.glowroot.api.weaving.BindMethodArgArray;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindOptionalReturn;
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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Advice {

    private static final ImmutableList<Class<? extends Annotation>> isEnabledBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class);
    private static final ImmutableList<Class<? extends Annotation>> onBeforeBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class);
    private static final ImmutableList<Class<? extends Annotation>> onReturnBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class, BindReturn.class, BindOptionalReturn.class,
                    BindTraveler.class);
    private static final ImmutableList<Class<? extends Annotation>> onThrowBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class, BindThrowable.class, BindTraveler.class);
    private static final ImmutableList<Class<? extends Annotation>> onAfterBindAnnotationTypes =
            ImmutableList.of(BindReceiver.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class, BindTraveler.class);

    private static final ImmutableMap<Class<? extends Annotation>, ParameterKind> parameterKindMap =
            new ImmutableMap.Builder<Class<? extends Annotation>, ParameterKind>()
                    .put(BindReceiver.class, ParameterKind.RECEIVER)
                    .put(BindMethodArg.class, ParameterKind.METHOD_ARG)
                    .put(BindMethodArgArray.class, ParameterKind.METHOD_ARG_ARRAY)
                    .put(BindMethodName.class, ParameterKind.METHOD_NAME)
                    .put(BindReturn.class, ParameterKind.RETURN)
                    .put(BindOptionalReturn.class, ParameterKind.OPTIONAL_RETURN)
                    .put(BindThrowable.class, ParameterKind.THROWABLE)
                    .put(BindTraveler.class, ParameterKind.TRAVELER)
                    .build();

    private final Pointcut pointcut;
    private final Type adviceType;
    @Nullable
    private final Pattern pointcutTypePattern;
    @Nullable
    private final Pattern pointcutMethodPattern;
    @Nullable
    private final Method isEnabledAdvice;
    @Nullable
    private final Method onBeforeAdvice;
    @Nullable
    private final Method onReturnAdvice;
    @Nullable
    private final Method onThrowAdvice;
    @Nullable
    private final Method onAfterAdvice;
    @Nullable
    private final Type travelerType;

    private final ImmutableList<AdviceParameter> isEnabledParameters;
    private final ImmutableList<AdviceParameter> onBeforeParameters;
    private final ImmutableList<AdviceParameter> onReturnParameters;
    private final ImmutableList<AdviceParameter> onThrowParameters;
    private final ImmutableList<AdviceParameter> onAfterParameters;

    private final Class<?> generatedAdviceFlowClass;
    private final boolean reweavable;

    public static Advice from(Pointcut pointcut, Class<?> adviceClass, boolean reweavable)
            throws AdviceConstructionException {
        return new Builder(pointcut, adviceClass, reweavable).build();
    }

    private Advice(Pointcut pointcut, Type adviceType, @Nullable Pattern pointcutTypePattern,
            @Nullable Pattern pointcutMethodPattern, @Nullable Method isEnabledAdvice,
            @Nullable Method onBeforeAdvice, @Nullable Method onReturnAdvice,
            @Nullable Method onThrowAdvice, @Nullable Method onAfterAdvice,
            @Nullable Type travelerType, List<AdviceParameter> isEnabledParameters,
            List<AdviceParameter> onBeforeParameters, List<AdviceParameter> onReturnParameters,
            List<AdviceParameter> onThrowParameterKinds,
            List<AdviceParameter> onAfterParameterKinds, Class<?> generatedAdviceFlowClass,
            boolean reweavable) {
        this.pointcut = pointcut;
        this.adviceType = adviceType;
        this.pointcutTypePattern = pointcutTypePattern;
        this.pointcutMethodPattern = pointcutMethodPattern;
        this.isEnabledAdvice = isEnabledAdvice;
        this.onBeforeAdvice = onBeforeAdvice;
        this.onReturnAdvice = onReturnAdvice;
        this.onThrowAdvice = onThrowAdvice;
        this.onAfterAdvice = onAfterAdvice;
        this.travelerType = travelerType;
        this.isEnabledParameters = ImmutableList.copyOf(isEnabledParameters);
        this.onBeforeParameters = ImmutableList.copyOf(onBeforeParameters);
        this.onReturnParameters = ImmutableList.copyOf(onReturnParameters);
        this.onThrowParameters = ImmutableList.copyOf(onThrowParameterKinds);
        this.onAfterParameters = ImmutableList.copyOf(onAfterParameterKinds);
        this.generatedAdviceFlowClass = generatedAdviceFlowClass;
        this.reweavable = reweavable;
    }

    Pointcut getPointcut() {
        return pointcut;
    }

    @Nullable
    Pattern getPointcutTypePattern() {
        return pointcutTypePattern;
    }

    @Nullable
    Pattern getPointcutMethodPattern() {
        return pointcutMethodPattern;
    }

    Type getAdviceType() {
        return adviceType;
    }

    @Nullable
    Type getTravelerType() {
        return travelerType;
    }

    @Nullable
    Method getIsEnabledAdvice() {
        return isEnabledAdvice;
    }

    @Nullable
    Method getOnBeforeAdvice() {
        return onBeforeAdvice;
    }

    @Nullable
    Method getOnReturnAdvice() {
        return onReturnAdvice;
    }

    @Nullable
    Method getOnThrowAdvice() {
        return onThrowAdvice;
    }

    @Nullable
    Method getOnAfterAdvice() {
        return onAfterAdvice;
    }

    ImmutableList<AdviceParameter> getIsEnabledParameters() {
        return isEnabledParameters;
    }

    ImmutableList<AdviceParameter> getOnBeforeParameters() {
        return onBeforeParameters;
    }

    ImmutableList<AdviceParameter> getOnReturnParameters() {
        return onReturnParameters;
    }

    ImmutableList<AdviceParameter> getOnThrowParameters() {
        return onThrowParameters;
    }

    ImmutableList<AdviceParameter> getOnAfterParameters() {
        return onAfterParameters;
    }

    Class<?> getGeneratedAdviceFlowClass() {
        return generatedAdviceFlowClass;
    }

    boolean isReweavable() {
        return reweavable;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("pointcut", pointcut)
                .add("adviceType", adviceType)
                .add("pointcutTypePattern", pointcutTypePattern)
                .add("pointcutMethodPattern", pointcutMethodPattern)
                .add("isEnabledAdvice", isEnabledAdvice)
                .add("onBeforeAdvice", onBeforeAdvice)
                .add("onReturnAdvice", onReturnAdvice)
                .add("onThrowAdvice", onThrowAdvice)
                .add("onAfterAdvice", onAfterAdvice)
                .add("travelerType", travelerType)
                .add("isEnabledParameters", isEnabledParameters)
                .add("onBeforeParameters", onBeforeParameters)
                .add("onReturnParameters", onReturnParameters)
                .add("onThrowParameters", onThrowParameters)
                .add("onAfterParameters", onAfterParameters)
                .add("generatedAdviceFlowClass", generatedAdviceFlowClass)
                .add("reweavable", reweavable)
                .toString();
    }

    static class AdviceParameter {
        private final ParameterKind kind;
        private final Class<?> type;
        private AdviceParameter(ParameterKind kind, Class<?> type) {
            this.kind = kind;
            this.type = type;
        }
        ParameterKind getKind() {
            return kind;
        }
        Class<?> getType() {
            return type;
        }
    }

    enum ParameterKind {
        RECEIVER, METHOD_ARG, METHOD_ARG_ARRAY, METHOD_NAME, RETURN, OPTIONAL_RETURN, THROWABLE,
        TRAVELER
    }

    @SuppressWarnings("serial")
    public static class AdviceConstructionException extends Exception {
        AdviceConstructionException(Throwable cause) {
            super(cause);
        }
        private AdviceConstructionException(@Nullable String message) {
            super(message);
        }
    }

    private static class Builder {

        private final Pointcut pointcut;
        private final Class<?> adviceClass;
        private final boolean reweavable;

        /*@MonotonicNonNull*/
        private Type adviceType;
        @Nullable
        private Pattern pointcutTypePattern;
        @Nullable
        private Pattern pointcutMethodPattern;
        /*@MonotonicNonNull*/
        private Method isEnabledAdvice;
        /*@MonotonicNonNull*/
        private Method onBeforeAdvice;
        /*@MonotonicNonNull*/
        private Method onReturnAdvice;
        /*@MonotonicNonNull*/
        private Method onThrowAdvice;
        /*@MonotonicNonNull*/
        private Method onAfterAdvice;
        /*@MonotonicNonNull*/
        private Type travelerType;

        private List<AdviceParameter> isEnabledParameters = Lists.newArrayList();
        private List<AdviceParameter> onBeforeParameters = Lists.newArrayList();
        private List<AdviceParameter> onReturnParameters = Lists.newArrayList();
        private List<AdviceParameter> onThrowParameters = Lists.newArrayList();
        private List<AdviceParameter> onAfterParameters = Lists.newArrayList();

        /*@MonotonicNonNull*/
        private Class<?> generatedAdviceFlowClass;

        private Builder(Pointcut pointcut, Class<?> adviceClass, boolean reweavable) {
            this.pointcut = pointcut;
            this.adviceClass = adviceClass;
            this.reweavable = reweavable;
        }

        private Advice build() throws AdviceConstructionException {
            adviceType = Type.getType(adviceClass);
            pointcutTypePattern = buildPattern(pointcut.typeName());
            pointcutMethodPattern = buildPattern(pointcut.methodName());
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
            if (pointcut.methodName().equals("<init>") && onBeforeAdvice != null) {
                throw new AdviceConstructionException(
                        "@OnBefore is not supported on constructors at this time");
            }
            generatedAdviceFlowClass = AdviceFlowGenerator.generate();
            return new Advice(pointcut, adviceType, pointcutTypePattern, pointcutMethodPattern,
                    isEnabledAdvice, onBeforeAdvice, onReturnAdvice, onThrowAdvice, onAfterAdvice,
                    travelerType, isEnabledParameters, onBeforeParameters, onReturnParameters,
                    onThrowParameters, onAfterParameters, generatedAdviceFlowClass, reweavable);
        }

        private void initIsEnabledAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (isEnabledAdvice != null) {
                throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                        + "' has more than one @IsEnabled method");
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() == Type.BOOLEAN) {
                this.isEnabledAdvice = asmMethod;
                this.isEnabledParameters = getAdviceParameters(method.getParameterAnnotations(),
                        method.getParameterTypes(), isEnabledBindAnnotationTypes, IsEnabled.class);
            } else {
                throw new AdviceConstructionException("@IsEnabled method must return boolean");
            }
        }

        private void initOnBeforeAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onBeforeAdvice != null) {
                throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                        + "' has more than one @OnBefore method");
            }
            onBeforeAdvice = Method.getMethod(method);
            onBeforeParameters = getAdviceParameters(method.getParameterAnnotations(),
                    method.getParameterTypes(), onBeforeBindAnnotationTypes, OnBefore.class);
            if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
                travelerType = onBeforeAdvice.getReturnType();
            }
        }

        private void initOnReturnAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onReturnAdvice != null) {
                throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                        + "' has more than one @OnReturn method");
            }
            List<AdviceParameter> parameters = getAdviceParameters(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    onReturnBindAnnotationTypes, OnReturn.class);
            for (int i = 1; i < parameters.size(); i++) {
                if (parameters.get(i).getKind() == ParameterKind.RETURN) {
                    throw new AdviceConstructionException(
                            "@BindReturn must be the first argument to @OnReturn");
                }
                if (parameters.get(i).getKind() == ParameterKind.OPTIONAL_RETURN) {
                    throw new AdviceConstructionException(
                            "@BindOptionalReturn must be the first argument to @OnReturn");
                }
            }
            this.onReturnAdvice = Method.getMethod(method);
            this.onReturnParameters = parameters;
        }

        private void initOnThrowAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onThrowAdvice != null) {
                throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                        + "' has more than one @OnThrow method");
            }
            List<AdviceParameter> parameters = getAdviceParameters(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    onThrowBindAnnotationTypes, OnThrow.class);
            for (int i = 1; i < parameters.size(); i++) {
                if (parameters.get(i).getKind() == ParameterKind.THROWABLE) {
                    throw new AdviceConstructionException(
                            "@BindThrowable must be the first argument to @OnThrow");
                }
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() != Type.VOID) {
                throw new AdviceConstructionException("@OnThrow method must return void (for now)");
            }
            this.onThrowAdvice = asmMethod;
            this.onThrowParameters = parameters;
        }

        private void initOnAfterAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onAfterAdvice != null) {
                throw new AdviceConstructionException("@Pointcut '" + adviceClass.getName()
                        + "' has more than one @OnAfter method");
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() != Type.VOID) {
                throw new AdviceConstructionException("@OnAfter method must return void");
            }
            this.onAfterAdvice = asmMethod;
            this.onAfterParameters = getAdviceParameters(method.getParameterAnnotations(),
                    method.getParameterTypes(), onAfterBindAnnotationTypes, OnAfter.class);
        }

        @Nullable
        private static Pattern buildPattern(String maybePattern) {
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

        @Nullable
        private static Class<? extends Annotation> getValidBindAnnotationType(
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
            return new AdviceParameter(parameterKind, parameterType);
        }
    }
}

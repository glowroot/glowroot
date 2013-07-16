/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.weaving;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.regex.Pattern;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindMethodArgArray;
import io.informant.api.weaving.BindMethodName;
import io.informant.api.weaving.BindOptionalReturn;
import io.informant.api.weaving.BindReturn;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindThrowable;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Advice {

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(Advice.class);

    private static final ImmutableList<Class<? extends Annotation>> isEnabledBindAnnotationTypes =
            ImmutableList.of(BindTarget.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class);
    private static final ImmutableList<Class<? extends Annotation>> onBeforeBindAnnotationTypes =
            ImmutableList.of(BindTarget.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class);
    private static final ImmutableList<Class<? extends Annotation>> onReturnBindAnnotationTypes =
            ImmutableList.of(BindTarget.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class, BindReturn.class, BindOptionalReturn.class,
                    BindTraveler.class);
    private static final ImmutableList<Class<? extends Annotation>> onThrowBindAnnotationTypes =
            ImmutableList.of(BindTarget.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class, BindThrowable.class, BindTraveler.class);
    private static final ImmutableList<Class<? extends Annotation>> onAfterBindAnnotationTypes =
            ImmutableList.of(BindTarget.class, BindMethodArg.class, BindMethodArgArray.class,
                    BindMethodName.class, BindTraveler.class);

    private static final ImmutableMap<Class<? extends Annotation>, ParameterKind> parameterKindMap =
            new ImmutableMap.Builder<Class<? extends Annotation>, ParameterKind>()
                    .put(BindTarget.class, ParameterKind.TARGET)
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

    private final ImmutableList<ParameterKind> isEnabledParameterKinds;
    private final ImmutableList<ParameterKind> onBeforeParameterKinds;
    private final ImmutableList<ParameterKind> onReturnParameterKinds;
    private final ImmutableList<ParameterKind> onThrowParameterKinds;
    private final ImmutableList<ParameterKind> onAfterParameterKinds;

    private final Class<?> generatedAdviceFlowClass;
    private final boolean dynamic;

    public static Advice from(Pointcut pointcut, Class<?> adviceClass, boolean dynamic)
            throws AdviceConstructionException {
        return new Builder(pointcut, adviceClass, dynamic).build();
    }

    private Advice(Pointcut pointcut, Type adviceType, @Nullable Pattern pointcutTypePattern,
            @Nullable Pattern pointcutMethodPattern, @Nullable Method isEnabledAdvice,
            @Nullable Method onBeforeAdvice, @Nullable Method onReturnAdvice,
            @Nullable Method onThrowAdvice, @Nullable Method onAfterAdvice,
            @Nullable Type travelerType, ImmutableList<ParameterKind> isEnabledParameterKinds,
            ImmutableList<ParameterKind> onBeforeParameterKinds,
            ImmutableList<ParameterKind> onReturnParameterKinds,
            ImmutableList<ParameterKind> onThrowParameterKinds,
            ImmutableList<ParameterKind> onAfterParameterKinds, Class<?> generatedAdviceFlowClass,
            boolean dynamic) {
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
        this.isEnabledParameterKinds = isEnabledParameterKinds;
        this.onBeforeParameterKinds = onBeforeParameterKinds;
        this.onReturnParameterKinds = onReturnParameterKinds;
        this.onThrowParameterKinds = onThrowParameterKinds;
        this.onAfterParameterKinds = onAfterParameterKinds;
        this.generatedAdviceFlowClass = generatedAdviceFlowClass;
        this.dynamic = dynamic;
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

    ImmutableList<ParameterKind> getIsEnabledParameterKinds() {
        return isEnabledParameterKinds;
    }

    ImmutableList<ParameterKind> getOnBeforeParameterKinds() {
        return onBeforeParameterKinds;
    }

    ImmutableList<ParameterKind> getOnReturnParameterKinds() {
        return onReturnParameterKinds;
    }

    ImmutableList<ParameterKind> getOnThrowParameterKinds() {
        return onThrowParameterKinds;
    }

    ImmutableList<ParameterKind> getOnAfterParameterKinds() {
        return onAfterParameterKinds;
    }

    Class<?> getGeneratedAdviceFlowClass() {
        return generatedAdviceFlowClass;
    }

    boolean isDynamic() {
        return dynamic;
    }

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
                .add("isEnabledParameterKinds", isEnabledParameterKinds)
                .add("onBeforeParameterKinds", onBeforeParameterKinds)
                .add("onReturnParameterKinds", onReturnParameterKinds)
                .add("onThrowParameterKinds", onThrowParameterKinds)
                .add("onAfterParameterKinds", onAfterParameterKinds)
                .add("generatedAdviceFlowClass", generatedAdviceFlowClass)
                .add("dynamic", dynamic)
                .toString();
    }

    @Immutable
    enum ParameterKind {
        TARGET, METHOD_ARG, PRIMITIVE_METHOD_ARG, METHOD_ARG_ARRAY, METHOD_NAME, RETURN,
        OPTIONAL_RETURN, PRIMITIVE_RETURN, THROWABLE, TRAVELER
    }

    @SuppressWarnings("serial")
    public static class AdviceConstructionException extends Exception {
        private AdviceConstructionException(@Nullable String message) {
            super(message);
        }
        private AdviceConstructionException(Throwable cause) {
            super(cause);
        }
    }

    private static class Builder {

        private final Pointcut pointcut;
        private final Class<?> adviceClass;
        private final boolean dynamic;

        private Type adviceType;
        @Nullable
        private Pattern pointcutTypePattern;
        @Nullable
        private Pattern pointcutMethodPattern;
        @LazyNonNull
        private Method isEnabledAdvice;
        @LazyNonNull
        private Method onBeforeAdvice;
        @LazyNonNull
        private Method onReturnAdvice;
        @LazyNonNull
        private Method onThrowAdvice;
        @LazyNonNull
        private Method onAfterAdvice;
        @LazyNonNull
        private Type travelerType;

        private ImmutableList<ParameterKind> isEnabledParameterKinds = ImmutableList.of();
        private ImmutableList<ParameterKind> onBeforeParameterKinds = ImmutableList.of();
        private ImmutableList<ParameterKind> onReturnParameterKinds = ImmutableList.of();
        private ImmutableList<ParameterKind> onThrowParameterKinds = ImmutableList.of();
        private ImmutableList<ParameterKind> onAfterParameterKinds = ImmutableList.of();

        private Class<?> generatedAdviceFlowClass;

        private Builder(Pointcut pointcut, Class<?> adviceClass, boolean dynamic) {
            this.pointcut = pointcut;
            this.adviceClass = adviceClass;
            this.dynamic = dynamic;
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
            generatedAdviceFlowClass = buildGeneratedAdviceFlowClass();
            return new Advice(pointcut, adviceType, pointcutTypePattern, pointcutMethodPattern,
                    isEnabledAdvice, onBeforeAdvice, onReturnAdvice, onThrowAdvice, onAfterAdvice,
                    travelerType, isEnabledParameterKinds, onBeforeParameterKinds,
                    onReturnParameterKinds, onThrowParameterKinds, onAfterParameterKinds,
                    generatedAdviceFlowClass, dynamic);
        }

        private Class<?> buildGeneratedAdviceFlowClass() throws AdviceConstructionException {
            try {
                return AdviceFlowGenerator.generate();
            } catch (SecurityException e) {
                logger.error(e.getMessage(), e);
                throw new AdviceConstructionException(e);
            } catch (IllegalArgumentException e) {
                logger.error(e.getMessage(), e);
                throw new AdviceConstructionException(e);
            } catch (NoSuchMethodException e) {
                logger.error(e.getMessage(), e);
                throw new AdviceConstructionException(e);
            } catch (IllegalAccessException e) {
                logger.error(e.getMessage(), e);
                throw new AdviceConstructionException(e);
            } catch (InvocationTargetException e) {
                logger.error(e.getMessage(), e);
                throw new AdviceConstructionException(e);
            }
        }

        private void initIsEnabledAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (isEnabledAdvice != null) {
                logger.warn("@Pointcut '{}' has more than one @IsEnabled method",
                        adviceClass.getName());
                return;
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() == Type.BOOLEAN) {
                this.isEnabledAdvice = asmMethod;
                this.isEnabledParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                        method.getParameterTypes(), isEnabledBindAnnotationTypes, IsEnabled.class);
            } else {
                logger.warn("@IsEnabled method must return boolean");
            }
        }

        private void initOnBeforeAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onBeforeAdvice != null) {
                logger.warn("@Pointcut '{}' has more than one @OnBefore method",
                        adviceClass.getName());
                return;
            }
            onBeforeAdvice = Method.getMethod(method);
            onBeforeParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                    method.getParameterTypes(), onBeforeBindAnnotationTypes, OnBefore.class);
            if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
                travelerType = onBeforeAdvice.getReturnType();
            }
        }

        private void initOnReturnAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onReturnAdvice != null) {
                logger.warn("@Pointcut '{}' has more than one @OnReturn method",
                        adviceClass.getName());
                return;
            }
            ImmutableList<ParameterKind> parameterKinds = getParameterKinds(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    onReturnBindAnnotationTypes, OnReturn.class);
            for (int i = 1; i < parameterKinds.size(); i++) {
                if (parameterKinds.get(i) == ParameterKind.RETURN) {
                    logger.warn("@BindReturn must be the first argument to @OnReturn");
                    return;
                }
                if (parameterKinds.get(i) == ParameterKind.OPTIONAL_RETURN) {
                    logger.warn("@BindOptionalReturn must be the first argument to @OnReturn");
                    return;
                }
            }
            this.onReturnAdvice = Method.getMethod(method);
            this.onReturnParameterKinds = parameterKinds;
        }

        private void initOnThrowAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onThrowAdvice != null) {
                logger.warn("@Pointcut '{}' has more than one @OnThrow method",
                        adviceClass.getName());
                return;
            }
            ImmutableList<ParameterKind> parameterKinds = getParameterKinds(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    onThrowBindAnnotationTypes, OnThrow.class);
            for (int i = 1; i < parameterKinds.size(); i++) {
                if (parameterKinds.get(i) == ParameterKind.THROWABLE) {
                    logger.warn("@BindThrowable must be the first argument to @OnThrow");
                    return;
                }
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() != Type.VOID) {
                // TODO allow @OnThrow methods to suppress the exception or throw a different
                // exception
                logger.warn("@OnThrow method must return void (for now)");
                return;
            }
            this.onThrowAdvice = asmMethod;
            this.onThrowParameterKinds = parameterKinds;
        }

        private void initOnAfterAdvice(Class<?> adviceClass, java.lang.reflect.Method method)
                throws AdviceConstructionException {
            if (onAfterAdvice != null) {
                logger.warn("@Pointcut '{}' has more than one @OnAfter method",
                        adviceClass.getName());
                return;
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() != Type.VOID) {
                logger.warn("@OnAfter method must return void");
                return;
            }
            this.onAfterAdvice = asmMethod;
            this.onAfterParameterKinds = getParameterKinds(method.getParameterAnnotations(),
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

        private static ImmutableList<ParameterKind> getParameterKinds(
                Annotation[][] parameterAnnotations, Class<?>[] parameterTypes,
                @ReadOnly List<Class<? extends Annotation>> validBindAnnotationTypes,
                Class<? extends Annotation> adviceAnnotationType)
                throws AdviceConstructionException {

            ImmutableList.Builder<ParameterKind> parameterKinds = ImmutableList.builder();
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
                parameterKinds.add(getParameterKind(validBindAnnotationType, parameterTypes[i]));
            }
            return parameterKinds.build();
        }

        @Nullable
        private static Class<? extends Annotation> getValidBindAnnotationType(
                Annotation[] parameterAnnotations,
                @ReadOnly List<Class<? extends Annotation>> validBindAnnotationTypes) {
            Class<? extends Annotation> foundBindAnnotationType = null;
            for (Annotation annotation : parameterAnnotations) {
                Class<? extends Annotation> annotationType = annotation.annotationType();
                if (!parameterKindMap.containsKey(annotationType)) {
                    continue;
                }
                if (foundBindAnnotationType != null) {
                    logger.warn("multiple annotations found on a single parameter");
                    break;
                }
                if (validBindAnnotationTypes.contains(annotationType)) {
                    foundBindAnnotationType = annotationType;
                } else {
                    logger.warn("annotation '" + annotationType.getName()
                            + "' found in an invalid location");
                }
            }
            return foundBindAnnotationType;
        }

        private static ParameterKind getParameterKind(
                Class<? extends Annotation> validBindAnnotationType, Class<?> parameterType) {
            if (validBindAnnotationType == BindMethodArg.class
                    && parameterType.isPrimitive()) {
                // special case to track primitive method args for possible autoboxing
                return ParameterKind.PRIMITIVE_METHOD_ARG;
            } else if (validBindAnnotationType == BindReturn.class
                    && parameterType.isPrimitive()) {
                // special case to track primitive return values for possible autoboxing
                return ParameterKind.PRIMITIVE_RETURN;
            } else {
                return parameterKindMap.get(validBindAnnotationType);
            }
        }
    }
}

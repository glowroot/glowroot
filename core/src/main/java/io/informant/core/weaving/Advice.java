/**
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
package io.informant.core.weaving;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.weaving.InjectMethodArg;
import io.informant.api.weaving.InjectMethodArgArray;
import io.informant.api.weaving.InjectMethodName;
import io.informant.api.weaving.InjectReturn;
import io.informant.api.weaving.InjectTarget;
import io.informant.api.weaving.InjectThrowable;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.regex.Pattern;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Advice {

    private static final Logger logger = LoggerFactory.getLogger(Advice.class);

    private static final ImmutableList<ParameterKind> isEnabledValidParameterKinds =
            ImmutableList.of(ParameterKind.TARGET, ParameterKind.METHOD_ARG,
                    ParameterKind.METHOD_ARG_ARRAY, ParameterKind.METHOD_NAME);
    private static final ImmutableList<ParameterKind> onBeforeValidParameterKinds =
            ImmutableList.of(ParameterKind.TARGET, ParameterKind.METHOD_ARG,
                    ParameterKind.METHOD_ARG_ARRAY, ParameterKind.METHOD_NAME);
    private static final ImmutableList<ParameterKind> onReturnValidParameterKinds =
            ImmutableList.of(ParameterKind.TARGET, ParameterKind.METHOD_ARG,
                    ParameterKind.METHOD_ARG_ARRAY, ParameterKind.METHOD_NAME,
                    ParameterKind.RETURN, ParameterKind.TRAVELER);
    private static final ImmutableList<ParameterKind> onThrowValidParameterKinds =
            ImmutableList.of(ParameterKind.TARGET, ParameterKind.METHOD_ARG,
                    ParameterKind.METHOD_ARG_ARRAY, ParameterKind.METHOD_NAME,
                    ParameterKind.THROWABLE, ParameterKind.TRAVELER);
    private static final ImmutableList<ParameterKind> onAfterValidParameterKinds =
            ImmutableList.of(ParameterKind.TARGET, ParameterKind.METHOD_ARG,
                    ParameterKind.METHOD_ARG_ARRAY, ParameterKind.METHOD_NAME,
                    ParameterKind.TRAVELER);

    private static final ImmutableMap<Class<? extends Annotation>, ParameterKind> parameterKindMap =
            new ImmutableMap.Builder<Class<? extends Annotation>, ParameterKind>()
                    .put(InjectTarget.class, ParameterKind.TARGET)
                    .put(InjectMethodArg.class, ParameterKind.METHOD_ARG)
                    .put(InjectMethodArgArray.class, ParameterKind.METHOD_ARG_ARRAY)
                    .put(InjectMethodName.class, ParameterKind.METHOD_NAME)
                    .put(InjectReturn.class, ParameterKind.RETURN)
                    .put(InjectThrowable.class, ParameterKind.THROWABLE)
                    .put(InjectTraveler.class, ParameterKind.TRAVELER).build();

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

    public static Advice from(Pointcut pointcut, Class<?> adviceClass) {
        return new Builder(pointcut, adviceClass).build();
    }

    private Advice(Pointcut pointcut, Type adviceType, @Nullable Pattern pointcutTypePattern,
            @Nullable Pattern pointcutMethodPattern, @Nullable Method isEnabledAdvice,
            @Nullable Method onBeforeAdvice, @Nullable Method onReturnAdvice,
            @Nullable Method onThrowAdvice, @Nullable Method onAfterAdvice,
            @Nullable Type travelerType, ImmutableList<ParameterKind> isEnabledParameterKinds,
            ImmutableList<ParameterKind> onBeforeParameterKinds,
            ImmutableList<ParameterKind> onReturnParameterKinds,
            ImmutableList<ParameterKind> onThrowParameterKinds,
            ImmutableList<ParameterKind> onAfterParameterKinds) {
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
                .toString();
    }

    @Immutable
    enum ParameterKind {
        TARGET, METHOD_ARG, PRIMITIVE_METHOD_ARG, METHOD_ARG_ARRAY, METHOD_NAME, RETURN,
        PRIMITIVE_RETURN, THROWABLE, TRAVELER;
    }

    private static class Builder {

        private final Pointcut pointcut;
        private final Type adviceType;
        @Nullable
        private final Pattern pointcutTypePattern;
        @Nullable
        private final Pattern pointcutMethodPattern;
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

        private Builder(Pointcut pointcut, Class<?> adviceClass) {
            this.pointcut = pointcut;
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
        }

        @Nullable
        private Pattern buildPattern(String maybePattern) {
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

        private String buildPatternPart(String part) {
            // convert * into .* and quote the rest of the text using \Q...\E
            String pattern = "\\Q" + part.replace("*", "\\E.*\\Q") + "\\E";
            // strip off unnecessary \\Q\\E in case * appeared at beginning or end of part
            return pattern.replace("\\Q\\E", "");
        }

        private void initIsEnabledAdvice(Class<?> adviceClass, java.lang.reflect.Method method) {
            if (isEnabledAdvice != null) {
                logger.error("@Pointcut '{}' has more than one @IsEnabled method",
                        adviceClass.getName());
                return;
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() == Type.BOOLEAN) {
                this.isEnabledAdvice = asmMethod;
                this.isEnabledParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                        method.getParameterTypes(), isEnabledValidParameterKinds);
            } else {
                logger.error("@IsEnabled method must return boolean");
            }
        }

        private void initOnBeforeAdvice(Class<?> adviceClass, java.lang.reflect.Method method) {
            if (onBeforeAdvice != null) {
                logger.error("@Pointcut '{}' has more than one @OnBefore method",
                        adviceClass.getName());
                return;
            }
            onBeforeAdvice = Method.getMethod(method);
            onBeforeParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                    method.getParameterTypes(), onBeforeValidParameterKinds);
            if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
                travelerType = onBeforeAdvice.getReturnType();
            }
        }

        private void initOnReturnAdvice(Class<?> adviceClass, java.lang.reflect.Method method) {
            if (onReturnAdvice != null) {
                logger.error("@Pointcut '{}' has more than one @OnReturn method",
                        adviceClass.getName());
                return;
            }
            ImmutableList<ParameterKind> parameterKinds = getParameterKinds(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    onReturnValidParameterKinds);
            for (int i = 1; i < parameterKinds.size(); i++) {
                if (parameterKinds.get(i) == ParameterKind.RETURN) {
                    logger.error("@InjectReturn must be the first argument to @OnReturn");
                    return;
                }
            }
            this.onReturnAdvice = Method.getMethod(method);
            this.onReturnParameterKinds = parameterKinds;
        }

        private void initOnThrowAdvice(Class<?> adviceClass, java.lang.reflect.Method method) {
            if (onThrowAdvice != null) {
                logger.error("@Pointcut '{}' has more than one @OnThrow method",
                        adviceClass.getName());
                return;
            }
            ImmutableList<ParameterKind> parameterKinds = getParameterKinds(
                    method.getParameterAnnotations(), method.getParameterTypes(),
                    onThrowValidParameterKinds);
            for (int i = 1; i < parameterKinds.size(); i++) {
                if (parameterKinds.get(i) == ParameterKind.THROWABLE) {
                    logger.error("@InjectThrowable must be the first argument to"
                            + " @OnThrow");
                    return;
                }
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() != Type.VOID) {
                // TODO allow @OnThrow methods to suppress the exception or throw a different
                // exception
                logger.error("@OnThrow method must return void (for now)");
                return;
            }
            this.onThrowAdvice = asmMethod;
            this.onThrowParameterKinds = parameterKinds;
        }

        private void initOnAfterAdvice(Class<?> adviceClass, java.lang.reflect.Method method) {
            if (onAfterAdvice != null) {
                logger.error("@Pointcut '{}' has more than one @OnAfter method",
                        adviceClass.getName());
                return;
            }
            Method asmMethod = Method.getMethod(method);
            if (asmMethod.getReturnType().getSort() != Type.VOID) {
                logger.error("@OnAfter method must return void");
                return;
            }
            this.onAfterAdvice = asmMethod;
            this.onAfterParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                    method.getParameterTypes(), onAfterValidParameterKinds);
        }

        private Advice build() {
            return new Advice(pointcut, adviceType, pointcutTypePattern, pointcutMethodPattern,
                    isEnabledAdvice, onBeforeAdvice, onReturnAdvice, onThrowAdvice, onAfterAdvice,
                    travelerType, isEnabledParameterKinds, onBeforeParameterKinds,
                    onReturnParameterKinds, onThrowParameterKinds, onAfterParameterKinds);
        }

        private static ImmutableList<ParameterKind> getParameterKinds(
                Annotation[][] parameterAnnotations, Class<?>[] parameterTypes,
                @ReadOnly List<ParameterKind> validParameterKinds) {

            ImmutableList.Builder<ParameterKind> parameterKinds = ImmutableList.builder();
            for (int i = 0; i < parameterAnnotations.length; i++) {
                ParameterKind parameterKind = findParam(parameterAnnotations[i],
                        validParameterKinds);
                if (parameterKind == null) {
                    // no applicable annotations found
                    parameterKind = ParameterKind.METHOD_ARG;
                }
                if (parameterKind == ParameterKind.METHOD_ARG
                        && parameterTypes[i].isPrimitive()) {
                    // special case to track primitive method args for possible autoboxing
                    parameterKind = ParameterKind.PRIMITIVE_METHOD_ARG;
                }
                if (parameterKind == ParameterKind.RETURN && parameterTypes[i].isPrimitive()) {
                    // special case to track primitive return values for possible autoboxing
                    parameterKind = ParameterKind.PRIMITIVE_RETURN;
                }
                parameterKinds.add(parameterKind);
            }
            return parameterKinds.build();
        }

        @Nullable
        private static ParameterKind findParam(Annotation[] parameterAnnotations,
                @ReadOnly List<ParameterKind> validArgTypes) {
            ParameterKind foundParameterKind = null;
            for (Annotation annotation : parameterAnnotations) {
                ParameterKind parameterKind = parameterKindMap.get(annotation.annotationType());
                if (parameterKind == null) {
                    continue;
                }
                if (foundParameterKind != null) {
                    logger.error("multiple annotations found on a single parameter");
                }
                if (validArgTypes.contains(parameterKind)) {
                    foundParameterKind = parameterKind;
                } else {
                    logger.error("annotation '" + annotation.annotationType().getName()
                            + "' found in an invalid location");
                }
            }
            return foundParameterKind;
        }
    }
}

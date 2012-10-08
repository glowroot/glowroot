/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.weaving;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.api.weaving.InjectMethodArg;
import org.informantproject.api.weaving.InjectMethodName;
import org.informantproject.api.weaving.InjectReturn;
import org.informantproject.api.weaving.InjectTarget;
import org.informantproject.api.weaving.InjectThrowable;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.OnReturn;
import org.informantproject.api.weaving.OnThrow;
import org.informantproject.api.weaving.Pointcut;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

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

    private static final Collection<ParameterKind> isEnabledValidParameterKinds = ImmutableList.of(
            ParameterKind.TARGET, ParameterKind.METHOD_ARG, ParameterKind.METHOD_NAME);
    private static final Collection<ParameterKind> onBeforeValidParameterKinds = ImmutableList.of(
            ParameterKind.TARGET, ParameterKind.METHOD_ARG, ParameterKind.METHOD_NAME);
    private static final Collection<ParameterKind> onReturnValidParameterKinds = ImmutableList.of(
            ParameterKind.TARGET, ParameterKind.METHOD_ARG, ParameterKind.METHOD_NAME,
            ParameterKind.RETURN, ParameterKind.TRAVELER);
    private static final Collection<ParameterKind> onThrowValidParameterKinds = ImmutableList.of(
            ParameterKind.TARGET, ParameterKind.METHOD_ARG, ParameterKind.METHOD_NAME,
            ParameterKind.THROWABLE, ParameterKind.TRAVELER);
    private static final Collection<ParameterKind> onAfterValidParameterKinds = ImmutableList.of(
            ParameterKind.TARGET, ParameterKind.METHOD_ARG, ParameterKind.METHOD_NAME,
            ParameterKind.TRAVELER);

    private static final Map<Class<? extends Annotation>, ParameterKind> parameterKindMap =
            new ImmutableMap.Builder<Class<? extends Annotation>, ParameterKind>()
                    .put(InjectTarget.class, ParameterKind.TARGET)
                    .put(InjectMethodArg.class, ParameterKind.METHOD_ARG)
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

    private final ParameterKind[] isEnabledParameterKinds;
    private final ParameterKind[] onBeforeParameterKinds;
    private final ParameterKind[] onReturnParameterKinds;
    private final ParameterKind[] onThrowParameterKinds;
    private final ParameterKind[] onAfterParameterKinds;

    public static Advice from(Pointcut pointcut, Class<?> adviceClass) {
        return new Builder(pointcut, adviceClass).build();
    }

    private Advice(Pointcut pointcut, Type adviceType, @Nullable Pattern pointcutTypePattern,
            @Nullable Pattern pointcutMethodPattern, @Nullable Method isEnabledAdvice,
            @Nullable Method onBeforeAdvice, @Nullable Method onReturnAdvice,
            @Nullable Method onThrowAdvice, @Nullable Method onAfterAdvice,
            @Nullable Type travelerType, ParameterKind[] isEnabledParameterKinds,
            ParameterKind[] onBeforeParameterKinds, ParameterKind[] onReturnParameterKinds,
            ParameterKind[] onThrowParameterKinds, ParameterKind[] onAfterParameterKinds) {

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

    ParameterKind[] getIsEnabledParameterKinds() {
        return isEnabledParameterKinds;
    }

    ParameterKind[] getOnBeforeParameterKinds() {
        return onBeforeParameterKinds;
    }

    ParameterKind[] getOnReturnParameterKinds() {
        return onReturnParameterKinds;
    }

    ParameterKind[] getOnThrowParameterKinds() {
        return onThrowParameterKinds;
    }

    ParameterKind[] getOnAfterParameterKinds() {
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

    enum ParameterKind {
        TARGET, METHOD_ARG, PRIMITIVE_METHOD_ARG, METHOD_NAME, RETURN, THROWABLE, TRAVELER;
    }

    private static class Builder {

        private final Pointcut pointcut;
        private final Type adviceType;
        @Nullable
        private final Pattern pointcutTypePattern;
        @Nullable
        private final Pattern pointcutMethodPattern;
        @Nullable
        private Method isEnabledAdvice;
        @Nullable
        private Method onBeforeAdvice;
        @Nullable
        private Method onReturnAdvice;
        @Nullable
        private Method onThrowAdvice;
        @Nullable
        private Method onAfterAdvice;
        @Nullable
        private Type travelerType;

        private ParameterKind[] isEnabledParameterKinds = new ParameterKind[0];
        private ParameterKind[] onBeforeParameterKinds = new ParameterKind[0];
        private ParameterKind[] onReturnParameterKinds = new ParameterKind[0];
        private ParameterKind[] onThrowParameterKinds = new ParameterKind[0];
        private ParameterKind[] onAfterParameterKinds = new ParameterKind[0];

        // TODO use builder to construct, then can use final fields
        private Builder(Pointcut pointcut, Class<?> adviceClass) {
            this.pointcut = pointcut;
            adviceType = Type.getType(adviceClass);
            pointcutTypePattern = buildPattern(pointcut.typeName());
            pointcutMethodPattern = buildPattern(pointcut.methodName());
            initFromClass(adviceClass);
        }

        private Pattern buildPattern(String maybePattern) {
            if (maybePattern.startsWith("/") && maybePattern.endsWith("/")) {
                return Pattern.compile(maybePattern.substring(1, maybePattern.length() - 1));
            } else {
                return null;
            }
        }

        private void initFromClass(Class<?> adviceClass) {
            for (java.lang.reflect.Method method : adviceClass.getMethods()) {
                if (method.isAnnotationPresent(IsEnabled.class)) {
                    if (isEnabledAdvice != null) {
                        logger.error("@Pointcut '{}' has more than one @IsEnabled method",
                                adviceClass.getName());
                    } else {
                        isEnabledAdvice = Method.getMethod(method);
                        isEnabledParameterKinds = getParameterKinds(
                                method.getParameterAnnotations(),
                                method.getParameterTypes(), isEnabledValidParameterKinds);
                        if (isEnabledAdvice.getReturnType().getSort() != Type.BOOLEAN) {
                            logger.error("@IsEnabled method must return boolean");
                            isEnabledAdvice = null;
                            isEnabledParameterKinds = new ParameterKind[0];
                        }
                    }
                } else if (method.isAnnotationPresent(OnBefore.class)) {
                    if (onBeforeAdvice != null) {
                        logger.error("@Pointcut '{}' has more than one @OnBefore method",
                                adviceClass.getName());
                    } else {
                        onBeforeAdvice = Method.getMethod(method);
                        onBeforeParameterKinds = getParameterKinds(
                                method.getParameterAnnotations(),
                                method.getParameterTypes(), onBeforeValidParameterKinds);
                        if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
                            if (onBeforeAdvice.getReturnType().getSort() < Type.ARRAY) {
                                logger.error("primitive types are not supported (yet) as the"
                                        + " @OnBefore return type (and for subsequent"
                                        + " @InjectTraveler)");
                            } else {
                                travelerType = onBeforeAdvice.getReturnType();
                            }
                        }
                    }
                } else if (method.isAnnotationPresent(OnReturn.class)) {
                    if (onReturnAdvice != null) {
                        logger.error("@Pointcut '{}' has more than one @OnSucces method",
                                adviceClass.getName());
                    } else {
                        onReturnAdvice = Method.getMethod(method);
                        onReturnParameterKinds = getParameterKinds(
                                method.getParameterAnnotations(),
                                method.getParameterTypes(), onReturnValidParameterKinds);
                        for (int i = 1; i < onReturnParameterKinds.length; i++) {
                            if (onReturnParameterKinds[i] == ParameterKind.RETURN) {
                                logger.error("@InjectReturn must be the first argument to"
                                        + " @OnReturn");
                                onReturnAdvice = null;
                                onReturnParameterKinds = new ParameterKind[0];
                                break;
                            }
                        }
                    }
                } else if (method.isAnnotationPresent(OnThrow.class)) {
                    if (onThrowAdvice != null) {
                        logger.error("@Pointcut '{}' has more than one @OnThrow method",
                                adviceClass.getName());
                    } else {
                        onThrowAdvice = Method.getMethod(method);
                        onThrowParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                                method.getParameterTypes(), onThrowValidParameterKinds);
                        for (int i = 1; i < onThrowParameterKinds.length; i++) {
                            if (onThrowParameterKinds[i] == ParameterKind.THROWABLE) {
                                logger.error("@InjectThrowable must be the first argument to"
                                        + " @OnThrow");
                                onThrowAdvice = null;
                                onThrowParameterKinds = new ParameterKind[0];
                                break;
                            }
                        }
                        if (onThrowAdvice != null
                                && onThrowAdvice.getReturnType().getSort() != Type.VOID) {
                            logger.error("@OnThrow method must return void (for now)");
                            onThrowAdvice = null;
                            onThrowParameterKinds = new ParameterKind[0];
                        }
                    }
                } else if (method.isAnnotationPresent(OnAfter.class)) {
                    if (onAfterAdvice != null) {
                        logger.error("@Pointcut '{}' has more than one @OnAfter method",
                                adviceClass.getName());
                    } else {
                        onAfterAdvice = Method.getMethod(method);
                        onAfterParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                                method.getParameterTypes(), onAfterValidParameterKinds);
                        if (onAfterAdvice.getReturnType().getSort() != Type.VOID) {
                            logger.error("@OnAfter method must return void");
                            onAfterAdvice = null;
                            onAfterParameterKinds = new ParameterKind[0];
                        }
                    }
                }
            }
        }

        private Advice build() {
            return new Advice(pointcut, adviceType, pointcutTypePattern, pointcutMethodPattern,
                    isEnabledAdvice, onBeforeAdvice, onReturnAdvice, onThrowAdvice, onAfterAdvice,
                    travelerType, isEnabledParameterKinds, onBeforeParameterKinds,
                    onReturnParameterKinds, onThrowParameterKinds, onAfterParameterKinds);
        }

        private static ParameterKind[] getParameterKinds(Annotation[][] parameterAnnotations,
                Class<?>[] parameterTypes, Collection<ParameterKind> validArgTypes) {

            ParameterKind[] parameterKinds = new ParameterKind[parameterAnnotations.length];
            for (int i = 0; i < parameterAnnotations.length; i++) {
                boolean found = false;
                for (Annotation annotation : parameterAnnotations[i]) {
                    ParameterKind parameterKind = parameterKindMap.get(annotation.annotationType());
                    if (parameterKind == null) {
                        continue;
                    }
                    if (found) {
                        logger.error("multiple annotations found on a single parameter");
                    }
                    if (validArgTypes.contains(parameterKind)) {
                        parameterKinds[i] = parameterKind;
                        found = true;
                    } else {
                        logger.error("annotation '" + annotation.annotationType().getName()
                                + "' found in an invalid location");
                    }
                }
                if (!found) {
                    // no applicable annotations found
                    parameterKinds[i] = ParameterKind.METHOD_ARG;
                }
                if (parameterKinds[i] == ParameterKind.METHOD_ARG
                        && parameterTypes[i].isPrimitive()) {
                    // special case to track primitive method args for possible autoboxing
                    parameterKinds[i] = ParameterKind.PRIMITIVE_METHOD_ARG;
                }
            }
            return parameterKinds;
        }
    }
}

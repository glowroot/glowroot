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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
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
    private final Pattern pointcutMethodPattern;
    private Method isEnabledAdvice;
    private Method onBeforeAdvice;
    private Method onReturnAdvice;
    private Method onThrowAdvice;
    private Method onAfterAdvice;
    private Type travelerType;

    private ParameterKind[] isEnabledParameterKinds;
    private ParameterKind[] onBeforeParameterKinds;
    private ParameterKind[] onReturnParameterKinds;
    private ParameterKind[] onThrowParameterKinds;
    private ParameterKind[] onAfterParameterKinds;

    Advice(Pointcut pointcut, Class<?> adviceClass) {
        this.pointcut = pointcut;
        adviceType = Type.getType(adviceClass);
        if (pointcut.methodName().startsWith("/") && pointcut.methodName().endsWith("/")) {
            pointcutMethodPattern = Pattern.compile(pointcut.methodName().substring(1,
                    pointcut.methodName().length() - 1));
        } else {
            pointcutMethodPattern = null;
        }
        initFromClass(adviceClass);
    }

    private void initFromClass(Class<?> adviceClass) {
        for (java.lang.reflect.Method method : adviceClass.getMethods()) {
            if (method.isAnnotationPresent(IsEnabled.class)) {
                if (isEnabledAdvice != null) {
                    logger.error("Advice '{}' has more than one @IsEnabled method",
                            adviceClass.getName());
                } else {
                    isEnabledAdvice = Method.getMethod(method);
                    isEnabledParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                            method.getParameterTypes(), isEnabledValidParameterKinds);
                    if (isEnabledAdvice.getReturnType().getSort() != Type.BOOLEAN) {
                        logger.error("@IsEnabled method must return boolean");
                        isEnabledAdvice = null;
                        isEnabledParameterKinds = null;
                    }
                }
            } else if (method.isAnnotationPresent(OnBefore.class)) {
                if (onBeforeAdvice != null) {
                    logger.error("Advice '{}' has more than one @OnBefore method",
                            adviceClass.getName());
                } else {
                    onBeforeAdvice = Method.getMethod(method);
                    onBeforeParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                            method.getParameterTypes(), onBeforeValidParameterKinds);
                    if (onBeforeAdvice.getReturnType().getSort() != Type.VOID) {
                        if (onBeforeAdvice.getReturnType().getSort() < Type.ARRAY) {
                            logger.error("primitive types are not supported (yet) as the @OnBefore"
                                    + " return type (and for subsequent @InjectTraveler)");
                        } else {
                            travelerType = onBeforeAdvice.getReturnType();
                        }
                    }
                }
            } else if (method.isAnnotationPresent(OnReturn.class)) {
                if (onReturnAdvice != null) {
                    logger.error("Advice '{}' has more than one @OnSucces method",
                            adviceClass.getName());
                } else {
                    onReturnAdvice = Method.getMethod(method);
                    onReturnParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                            method.getParameterTypes(), onReturnValidParameterKinds);
                    for (int i = 1; i < onReturnParameterKinds.length; i++) {
                        if (onReturnParameterKinds[i] == ParameterKind.RETURN) {
                            logger.error("@InjectReturn must be the first argument to @OnReturn");
                            onReturnAdvice = null;
                            onReturnParameterKinds = null;
                            break;
                        }
                    }
                }
            } else if (method.isAnnotationPresent(OnThrow.class)) {
                if (onThrowAdvice != null) {
                    logger.error("Advice '{}' has more than one @OnThrow method",
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
                            onThrowParameterKinds = null;
                            break;
                        }
                    }
                    if (onThrowAdvice != null
                            && onThrowAdvice.getReturnType().getSort() != Type.VOID) {
                        logger.error("@OnThrow method must return void (for now)");
                        onThrowAdvice = null;
                        onThrowParameterKinds = null;
                    }
                }
            } else if (method.isAnnotationPresent(OnAfter.class)) {
                if (onAfterAdvice != null) {
                    logger.error("Advice '{}' has more than one @OnAfter method",
                            adviceClass.getName());
                } else {
                    onAfterAdvice = Method.getMethod(method);
                    onAfterParameterKinds = getParameterKinds(method.getParameterAnnotations(),
                            method.getParameterTypes(), onAfterValidParameterKinds);
                    if (onAfterAdvice.getReturnType().getSort() != Type.VOID) {
                        logger.error("@OnAfter method must return void");
                        onAfterAdvice = null;
                        onAfterParameterKinds = null;
                    }
                }
            }
        }
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
                    if (parameterKind == ParameterKind.METHOD_ARG
                            && parameterTypes[i].isPrimitive()) {
                        // special case to track primitive method args for possible autoboxing
                        parameterKinds[i] = ParameterKind.PRIMITIVE_METHOD_ARG;
                    } else {
                        parameterKinds[i] = parameterKind;
                    }
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
        }
        return parameterKinds;
    }

    Pointcut getPointcut() {
        return pointcut;
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

    enum ParameterKind {
        TARGET, METHOD_ARG, PRIMITIVE_METHOD_ARG, METHOD_NAME, RETURN, THROWABLE, TRAVELER;
    }
}

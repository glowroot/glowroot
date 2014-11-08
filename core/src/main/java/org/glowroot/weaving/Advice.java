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
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
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
import org.glowroot.markers.Immutable;

import static com.google.common.base.Preconditions.checkNotNull;

@Immutable
public class Advice {

    static final Ordering<Advice> orderingByMetricName = new Ordering<Advice>() {
        @Override
        public int compare(@Nullable Advice left, @Nullable Advice right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.pointcut.metricName().compareToIgnoreCase(right.pointcut.metricName());
        }
    };

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

    private final Pointcut pointcut;
    private final Type adviceType;
    @Nullable
    private final Pattern pointcutClassNamePattern;
    @Nullable
    private final Pattern pointcutMethodNamePattern;
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

    private final boolean reweavable;

    private final ImmutableSet<Type> classMetaTypes;
    private final ImmutableSet<Type> methodMetaTypes;

    public static Advice from(Class<?> adviceClass, boolean reweavable)
            throws AdviceConstructionException {
        return new Builder(adviceClass, reweavable).build();
    }

    public static Advice from(LazyDefinedClass adviceClass, boolean reweavable)
            throws AdviceConstructionException {
        return new Builder(adviceClass, reweavable).build();
    }

    private Advice(Pointcut pointcut, Type adviceType, @Nullable Pattern pointcutClassNamePattern,
            @Nullable Pattern pointcutMethodNamePattern, @Nullable Method isEnabledAdvice,
            @Nullable Method onBeforeAdvice, @Nullable Method onReturnAdvice,
            @Nullable Method onThrowAdvice, @Nullable Method onAfterAdvice,
            @Nullable Type travelerType, List<AdviceParameter> isEnabledParameters,
            List<AdviceParameter> onBeforeParameters, List<AdviceParameter> onReturnParameters,
            List<AdviceParameter> onThrowParameterKinds,
            List<AdviceParameter> onAfterParameterKinds, boolean reweavable) {
        this.pointcut = pointcut;
        this.adviceType = adviceType;
        this.pointcutClassNamePattern = pointcutClassNamePattern;
        this.pointcutMethodNamePattern = pointcutMethodNamePattern;
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
        this.reweavable = reweavable;
        Set<Type> classMetas = Sets.newHashSet();
        classMetas.addAll(getClassMetas(isEnabledParameters));
        classMetas.addAll(getClassMetas(onBeforeParameters));
        classMetas.addAll(getClassMetas(onReturnParameters));
        classMetas.addAll(getClassMetas(onThrowParameterKinds));
        classMetas.addAll(getClassMetas(onAfterParameterKinds));
        this.classMetaTypes = ImmutableSet.copyOf(classMetas);
        Set<Type> methodMetas = Sets.newHashSet();
        methodMetas.addAll(getMethodMetas(isEnabledParameters));
        methodMetas.addAll(getMethodMetas(onBeforeParameters));
        methodMetas.addAll(getMethodMetas(onReturnParameters));
        methodMetas.addAll(getMethodMetas(onThrowParameterKinds));
        methodMetas.addAll(getMethodMetas(onAfterParameterKinds));
        this.methodMetaTypes = ImmutableSet.copyOf(methodMetas);
    }

    Pointcut getPointcut() {
        return pointcut;
    }

    Type getAdviceType() {
        return adviceType;
    }

    @Nullable
    Pattern getPointcutClassNamePattern() {
        return pointcutClassNamePattern;
    }

    @Nullable
    Pattern getPointcutMethodNamePattern() {
        return pointcutMethodNamePattern;
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

    boolean isReweavable() {
        return reweavable;
    }

    Set<Type> getClassMetaTypes() {
        return classMetaTypes;
    }

    Set<Type> getMethodMetaTypes() {
        return methodMetaTypes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("pointcut", pointcut)
                .add("adviceType", adviceType)
                .add("pointcutClassNamePattern", pointcutClassNamePattern)
                .add("pointcutMethodNamePattern", pointcutMethodNamePattern)
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
                .add("reweavable", reweavable)
                .add("classMetas", classMetaTypes)
                .add("methodMetas", methodMetaTypes)
                .toString();
    }

    private static Set<Type> getClassMetas(List<AdviceParameter> parameters) {
        Set<Type> types = Sets.newHashSet();
        for (AdviceParameter parameter : parameters) {
            if (parameter.getKind() == ParameterKind.CLASS_META) {
                types.add(parameter.getType());
            }
        }
        return types;
    }

    private static Set<Type> getMethodMetas(List<AdviceParameter> parameters) {
        Set<Type> types = Sets.newHashSet();
        for (AdviceParameter parameter : parameters) {
            if (parameter.getKind() == ParameterKind.METHOD_META) {
                types.add(parameter.getType());
            }
        }
        return types;
    }

    static class AdviceParameter {
        private final ParameterKind kind;
        private final Type type;
        private AdviceParameter(ParameterKind kind, Type type) {
            this.kind = kind;
            this.type = type;
        }
        ParameterKind getKind() {
            return kind;
        }
        Type getType() {
            return type;
        }
    }

    enum ParameterKind {
        RECEIVER, METHOD_ARG, METHOD_ARG_ARRAY, METHOD_NAME, RETURN, OPTIONAL_RETURN, THROWABLE,
        TRAVELER, CLASS_META, METHOD_META
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

        @Nullable
        private final Class<?> adviceClass;
        @Nullable
        private final LazyDefinedClass lazyAdviceClass;
        private final boolean reweavable;

        @MonotonicNonNull
        private Type adviceType;
        @Nullable
        private Pattern pointcutClassNamePattern;
        @Nullable
        private Pattern pointcutMethodNamePattern;
        @MonotonicNonNull
        private Method isEnabledAdvice;
        @MonotonicNonNull
        private Method onBeforeAdvice;
        @MonotonicNonNull
        private Method onReturnAdvice;
        @MonotonicNonNull
        private Method onThrowAdvice;
        @MonotonicNonNull
        private Method onAfterAdvice;
        @MonotonicNonNull
        private Type travelerType;

        private List<AdviceParameter> isEnabledParameters = Lists.newArrayList();
        private List<AdviceParameter> onBeforeParameters = Lists.newArrayList();
        private List<AdviceParameter> onReturnParameters = Lists.newArrayList();
        private List<AdviceParameter> onThrowParameters = Lists.newArrayList();
        private List<AdviceParameter> onAfterParameters = Lists.newArrayList();

        private Builder(Class<?> adviceClass, boolean reweavable) {
            this.adviceClass = adviceClass;
            this.lazyAdviceClass = null;
            this.reweavable = reweavable;
        }

        private Builder(LazyDefinedClass lazyAdviceClass, boolean reweavable) {
            this.adviceClass = null;
            this.lazyAdviceClass = lazyAdviceClass;
            this.reweavable = reweavable;
        }

        private Advice build() throws AdviceConstructionException {
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
            adviceType = Type.getType(adviceClass);
            pointcutClassNamePattern = buildPattern(pointcut.className());
            pointcutMethodNamePattern = buildPattern(pointcut.methodName());
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
            return new Advice(pointcut, adviceType, pointcutClassNamePattern,
                    pointcutMethodNamePattern, isEnabledAdvice, onBeforeAdvice, onReturnAdvice,
                    onThrowAdvice, onAfterAdvice, travelerType, isEnabledParameters,
                    onBeforeParameters, onReturnParameters, onThrowParameters, onAfterParameters,
                    reweavable);
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
            return new AdviceParameter(parameterKind, Type.getType(parameterType));
        }
    }
}

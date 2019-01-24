/*
 * Copyright 2012-2019 the original author or authors.
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

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
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
import org.glowroot.agent.util.MaybePatterns;
import org.glowroot.agent.weaving.Advice.AdviceParameter;
import org.glowroot.agent.weaving.Advice.ParameterKind;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.agent.weaving.PluginDetail.PointcutClass;
import org.glowroot.agent.weaving.PluginDetail.PointcutMethod;

import static com.google.common.base.Preconditions.checkNotNull;

class AdviceBuilder {

    private static final Type IsEnabledType = Type.getType(IsEnabled.class);
    private static final Type OnBeforeType = Type.getType(OnBefore.class);
    private static final Type OnReturnType = Type.getType(OnReturn.class);
    private static final Type OnThrowType = Type.getType(OnThrow.class);
    private static final Type OnAfterType = Type.getType(OnAfter.class);

    private static final Type ThreadContextType = Type.getType(ThreadContext.class);
    private static final Type OptionalThreadContextType = Type.getType(OptionalThreadContext.class);

    private static final Type BindReceiverType = Type.getType(BindReceiver.class);
    private static final Type BindParameterType = Type.getType(BindParameter.class);
    private static final Type BindParameterArrayType = Type.getType(BindParameterArray.class);
    private static final Type BindMethodNameType = Type.getType(BindMethodName.class);
    private static final Type BindReturnType = Type.getType(BindReturn.class);
    private static final Type BindOptionalReturnType = Type.getType(BindOptionalReturn.class);
    private static final Type BindThrowableType = Type.getType(BindThrowable.class);
    private static final Type BindTravelerType = Type.getType(BindTraveler.class);
    private static final Type BindClassMetaType = Type.getType(BindClassMeta.class);
    private static final Type BindMethodMetaType = Type.getType(BindMethodMeta.class);

    private static final Type StringType = Type.getType(String.class);
    private static final Type ThrowableType = Type.getType(Throwable.class);

    private static final ImmutableList<Type> isEnabledBindAnnotationTypes =
            ImmutableList.of(BindReceiverType, BindParameterType, BindParameterArrayType,
                    BindMethodNameType, BindClassMetaType, BindMethodMetaType);
    private static final ImmutableList<Type> onBeforeBindAnnotationTypes =
            ImmutableList.of(BindReceiverType, BindParameterType, BindParameterArrayType,
                    BindMethodNameType, BindClassMetaType, BindMethodMetaType);
    private static final ImmutableList<Type> onReturnBindAnnotationTypes =
            ImmutableList.of(BindReceiverType, BindParameterType, BindParameterArrayType,
                    BindMethodNameType, BindReturnType, BindOptionalReturnType, BindTravelerType,
                    BindClassMetaType, BindMethodMetaType);
    private static final ImmutableList<Type> onThrowBindAnnotationTypes =
            ImmutableList.of(BindReceiverType, BindParameterType, BindParameterArrayType,
                    BindMethodNameType, BindThrowableType, BindTravelerType, BindClassMetaType,
                    BindMethodMetaType);
    private static final ImmutableList<Type> onAfterBindAnnotationTypes =
            ImmutableList.of(BindReceiverType, BindParameterType, BindParameterArrayType,
                    BindMethodNameType, BindTravelerType, BindClassMetaType, BindMethodMetaType);

    private static final ImmutableMap<Type, ParameterKind> parameterKindMap =
            new ImmutableMap.Builder<Type, ParameterKind>()
                    .put(BindReceiverType, ParameterKind.RECEIVER)
                    .put(BindParameterType, ParameterKind.METHOD_ARG)
                    .put(BindParameterArrayType, ParameterKind.METHOD_ARG_ARRAY)
                    .put(BindMethodNameType, ParameterKind.METHOD_NAME)
                    .put(BindReturnType, ParameterKind.RETURN)
                    .put(BindOptionalReturnType, ParameterKind.OPTIONAL_RETURN)
                    .put(BindThrowableType, ParameterKind.THROWABLE)
                    .put(BindTravelerType, ParameterKind.TRAVELER)
                    .put(BindClassMetaType, ParameterKind.CLASS_META)
                    .put(BindMethodMetaType, ParameterKind.METHOD_META)
                    .build();

    private final ImmutableAdvice.Builder builder = ImmutableAdvice.builder();

    private final @Nullable PointcutClass adviceClass;
    private final @Nullable LazyDefinedClass lazyAdviceClass;

    private boolean hasIsEnabledAdvice;
    private boolean hasOnBeforeAdvice;
    private boolean hasOnReturnAdvice;
    private boolean hasOnThrowAdvice;
    private boolean hasOnAfterAdvice;

    AdviceBuilder(PointcutClass adviceClass) {
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
        PointcutClass adviceClass = this.adviceClass;
        if (adviceClass == null) {
            // safe check, if adviceClass is null then lazyAdviceClass is non-null
            checkNotNull(lazyAdviceClass);
            adviceClass = PluginDetailBuilder.buildAdviceClass(lazyAdviceClass.bytes());
        }
        Pointcut pointcut = adviceClass.pointcut();
        checkNotNull(pointcut, "Class has no @Pointcut annotation");
        builder.pointcut(pointcut);
        builder.adviceType(adviceClass.type());
        builder.pointcutClassNamePattern(MaybePatterns.buildPattern(pointcut.className()));
        builder.pointcutClassAnnotationPattern(
                MaybePatterns.buildPattern(pointcut.classAnnotation()));
        builder.pointcutSubTypeRestrictionPattern(
                MaybePatterns.buildPattern(pointcut.subTypeRestriction()));
        builder.pointcutSuperTypeRestrictionPattern(
                MaybePatterns.buildPattern(pointcut.superTypeRestriction()));
        builder.pointcutMethodNamePattern(MaybePatterns.buildPattern(pointcut.methodName()));
        builder.pointcutMethodAnnotationPattern(
                MaybePatterns.buildPattern(pointcut.methodAnnotation()));
        builder.pointcutMethodParameterTypes(buildPatterns(pointcut.methodParameterTypes()));

        // hasBindThreadContext will be overridden below if needed
        builder.hasBindThreadContext(false);
        // hasBindOptionalThreadContext will be overridden below if needed
        builder.hasBindOptionalThreadContext(false);
        for (PointcutMethod adviceMethod : adviceClass.methods()) {
            if (adviceMethod.annotationTypes().contains(IsEnabledType)) {
                initIsEnabledAdvice(adviceClass, adviceMethod);
            } else if (adviceMethod.annotationTypes().contains(OnBeforeType)) {
                initOnBeforeAdvice(adviceClass, adviceMethod);
            } else if (adviceMethod.annotationTypes().contains(OnReturnType)) {
                initOnReturnAdvice(adviceClass, adviceMethod);
            } else if (adviceMethod.annotationTypes().contains(OnThrowType)) {
                initOnThrowAdvice(adviceClass, adviceMethod);
            } else if (adviceMethod.annotationTypes().contains(OnAfterType)) {
                initOnAfterAdvice(adviceClass, adviceMethod);
            }
        }
        if (adviceClass.collocateInClassLoader()) {
            PluginClassRenamer pluginClassRenamer = new PluginClassRenamer(adviceClass);
            builder.nonBootstrapLoaderAdviceClass(
                    pluginClassRenamer.buildNonBootstrapLoaderAdviceClass());
            builder.nonBootstrapLoaderAdvice(
                    pluginClassRenamer.buildNonBootstrapLoaderAdvice(builder.build()));
        }
        Advice advice = builder.build();
        if (pointcut.methodName().equals("<init>") && advice.onBeforeAdvice() != null
                && advice.hasBindOptionalThreadContext()) {
            // this is because of the way @OnBefore advice is handled on constructors,
            // see WeavingMethodVisitory.invokeOnBefore()
            throw new IllegalStateException("@BindOptionalThreadContext is not allowed in a"
                    + " @Pointcut with methodName \"<init>\" that has an @OnBefore method");
        }
        if (pointcut.methodName().equals("<init>") && advice.isEnabledAdvice() != null) {
            for (AdviceParameter parameter : advice.isEnabledParameters()) {
                if (parameter.kind() == ParameterKind.RECEIVER) {
                    // @IsEnabled is called before the super constructor is called, so "this" is not
                    // available yet
                    throw new IllegalStateException("@BindReceiver is not allowed on @IsEnabled for"
                            + " a @Pointcut with methodName \"<init>\"");
                }
            }
        }
        return advice;
    }

    private void initIsEnabledAdvice(PointcutClass adviceClass, PointcutMethod adviceMethod)
            throws AdviceConstructionException {
        checkState(!hasIsEnabledAdvice, "@Pointcut '" + adviceClass.type().getClassName()
                + "' has more than one @IsEnabled method");
        Method asmMethod = adviceMethod.toAsmMethod();
        checkState(asmMethod.getReturnType().getSort() == Type.BOOLEAN,
                "@IsEnabled method must return boolean");
        builder.isEnabledAdvice(asmMethod);
        List<AdviceParameter> parameters =
                getAdviceParameters(adviceMethod.parameterAnnotationTypes(),
                        asmMethod.getArgumentTypes(), isEnabledBindAnnotationTypes, IsEnabledType);
        builder.addAllIsEnabledParameters(parameters);
        hasIsEnabledAdvice = true;
    }

    private void initOnBeforeAdvice(PointcutClass adviceClass, PointcutMethod adviceMethod)
            throws AdviceConstructionException {
        checkState(!hasOnBeforeAdvice, "@Pointcut '" + adviceClass.type().getClassName()
                + "' has more than one @OnBefore method");
        Method asmMethod = adviceMethod.toAsmMethod();
        builder.onBeforeAdvice(asmMethod);
        List<AdviceParameter> parameters =
                getAdviceParameters(adviceMethod.parameterAnnotationTypes(),
                        asmMethod.getArgumentTypes(), onBeforeBindAnnotationTypes, OnBeforeType);
        builder.addAllOnBeforeParameters(parameters);
        if (asmMethod.getReturnType().getSort() != Type.VOID) {
            builder.travelerType(asmMethod.getReturnType());
        }
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnBeforeAdvice = true;
    }

    private void initOnReturnAdvice(PointcutClass adviceClass, PointcutMethod adviceMethod)
            throws AdviceConstructionException {
        checkState(!hasOnReturnAdvice, "@Pointcut '" + adviceClass.type().getClassName()
                + "' has more than one @OnReturn method");
        Method asmMethod = adviceMethod.toAsmMethod();
        List<AdviceParameter> parameters =
                getAdviceParameters(adviceMethod.parameterAnnotationTypes(),
                        asmMethod.getArgumentTypes(), onReturnBindAnnotationTypes, OnReturnType);
        for (int i = 1; i < parameters.size(); i++) {
            checkState(parameters.get(i).kind() != ParameterKind.RETURN,
                    "@BindReturn must be the first argument to @OnReturn");
            checkState(parameters.get(i).kind() != ParameterKind.OPTIONAL_RETURN,
                    "@BindOptionalReturn must be the first argument to @OnReturn");
        }
        builder.onReturnAdvice(asmMethod);
        builder.addAllOnReturnParameters(parameters);
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnReturnAdvice = true;
    }

    private void initOnThrowAdvice(PointcutClass adviceClass, PointcutMethod adviceMethod)
            throws AdviceConstructionException {
        checkState(!hasOnThrowAdvice, "@Pointcut '" + adviceClass.type().getClassName()
                + "' has more than one @OnThrow method");
        Method asmMethod = adviceMethod.toAsmMethod();
        List<AdviceParameter> parameters =
                getAdviceParameters(adviceMethod.parameterAnnotationTypes(),
                        asmMethod.getArgumentTypes(), onThrowBindAnnotationTypes, OnThrowType);
        for (int i = 1; i < parameters.size(); i++) {
            checkState(parameters.get(i).kind() != ParameterKind.THROWABLE,
                    "@BindThrowable must be the first argument to @OnThrow");
        }
        checkState(asmMethod.getReturnType().getSort() == Type.VOID,
                "@OnThrow method must return void (for now)");
        builder.onThrowAdvice(asmMethod);
        builder.addAllOnThrowParameters(parameters);
        checkForBindThreadContext(parameters);
        checkForBindOptionalThreadContext(parameters);
        hasOnThrowAdvice = true;
    }

    private void initOnAfterAdvice(PointcutClass adviceClass, PointcutMethod adviceMethod)
            throws AdviceConstructionException {
        checkState(!hasOnAfterAdvice, "@Pointcut '" + adviceClass.type().getClassName()
                + "' has more than one @OnAfter method");
        Method asmMethod = adviceMethod.toAsmMethod();
        checkState(asmMethod.getReturnType().getSort() == Type.VOID,
                "@OnAfter method must return void");
        builder.onAfterAdvice(asmMethod);
        List<AdviceParameter> parameters =
                getAdviceParameters(adviceMethod.parameterAnnotationTypes(),
                        asmMethod.getArgumentTypes(), onAfterBindAnnotationTypes, OnAfterType);
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
            Pattern pattern = MaybePatterns.buildPattern(maybePattern);
            if (pattern == null) {
                patterns.add(maybePattern);
            } else {
                patterns.add(pattern);
            }
        }
        return patterns;
    }

    private static List<AdviceParameter> getAdviceParameters(
            Map<Integer, List<Type>> parameterAnnotationTypes, Type[] parameterTypes,
            List<Type> validBindAnnotationTypes, Type adviceAnnotationType)
            throws AdviceConstructionException {

        List<AdviceParameter> parameters = Lists.newArrayList();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(ThreadContextType)) {
                parameters.add(ImmutableAdviceParameter.builder()
                        .kind(ParameterKind.THREAD_CONTEXT)
                        .type(ThreadContextType)
                        .build());
                continue;
            }
            if (parameterTypes[i].equals(OptionalThreadContextType)) {
                parameters.add(ImmutableAdviceParameter.builder()
                        .kind(ParameterKind.OPTIONAL_THREAD_CONTEXT)
                        .type(OptionalThreadContextType)
                        .build());
                continue;
            }
            Type validBindAnnotationType = getValidBindAnnotationType(
                    checkNotNull(parameterAnnotationTypes.get(i)), validBindAnnotationTypes);
            if (validBindAnnotationType == null) {
                // no valid bind annotations found, provide a good error message
                List<String> validBindAnnotationNames = Lists.newArrayList();
                for (Type annotationType : validBindAnnotationTypes) {
                    validBindAnnotationNames.add("@" + annotationType.getClassName());
                }
                throw new AdviceConstructionException("All parameters to @"
                        + adviceAnnotationType.getClassName() + " must be annotated with one"
                        + " of " + Joiner.on(", ").join(validBindAnnotationNames));
            }
            parameters.add(getAdviceParameter(validBindAnnotationType, parameterTypes[i]));
        }
        return parameters;
    }

    private static @Nullable Type getValidBindAnnotationType(List<Type> parameterAnnotationTypes,
            List<Type> validBindAnnotationTypes) throws AdviceConstructionException {
        Type foundBindAnnotationType = null;
        for (Type annotationType : parameterAnnotationTypes) {
            if (!parameterKindMap.containsKey(annotationType)) {
                continue;
            }
            checkState(foundBindAnnotationType == null,
                    "Multiple annotations found on a single parameter");
            checkState(validBindAnnotationTypes.contains(annotationType), "Annotation '"
                    + annotationType.getClassName() + "' found in an invalid location");
            foundBindAnnotationType = annotationType;
        }
        return foundBindAnnotationType;
    }

    private static AdviceParameter getAdviceParameter(Type validBindAnnotationType,
            Type parameterType) throws AdviceConstructionException {
        checkState(
                !validBindAnnotationType.equals(BindMethodNameType)
                        || parameterType.equals(StringType),
                "@BindMethodName parameter type must be java.lang.String");
        checkState(
                !validBindAnnotationType.equals(BindThrowableType)
                        || parameterType.equals(ThrowableType),
                "@BindMethodName parameter type must be java.lang.Throwable");
        ParameterKind parameterKind = parameterKindMap.get(validBindAnnotationType);
        // parameterKind should never be null since all bind annotations have a mapping in
        // parameterKindMap
        checkNotNull(parameterKind, "Annotation not found in parameterKindMap: "
                + validBindAnnotationType.getClassName());
        return ImmutableAdviceParameter.builder()
                .kind(parameterKind)
                .type(parameterType)
                .build();
    }

    @SuppressWarnings("serial")
    private static class AdviceConstructionException extends Exception {
        private AdviceConstructionException(@Nullable String message) {
            super(message);
        }
    }
}

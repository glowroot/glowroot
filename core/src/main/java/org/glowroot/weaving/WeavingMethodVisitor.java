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
import java.lang.reflect.Modifier;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.markers.UsedByGeneratedBytecode;
import org.glowroot.weaving.Advice.AdviceParameter;
import org.glowroot.weaving.Advice.ParameterKind;
import org.glowroot.weaving.AdviceFlowOuterHolder.AdviceFlowHolder;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingMethodVisitor extends PatchedAdviceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WeavingMethodVisitor.class);

    private static final Type adviceFlowOuterHolderType = Type.getType(AdviceFlowOuterHolder.class);
    private static final Type adviceFlowHolderType = Type.getType(AdviceFlowHolder.class);

    private final int access;
    private final String name;
    private final Type owner;
    private final ImmutableList<Advice> advisors;
    private final Type[] argumentTypes;
    private final Type returnType;
    @Nullable
    private final String metaHolderInternalName;
    @Nullable
    private final Integer methodMetaGroupUniqueNum;
    private final boolean bootstrapClassLoader;
    private final boolean needsTryCatch;
    @Nullable
    private final MethodVisitor outerMethodVisitor;

    private final Map<Advice, Integer> adviceFlowHolderLocals = Maps.newHashMap();
    // the adviceFlow stores the value in the holder at the beginning of the advice so the holder
    // can be reset at the end of the advice
    private final Map<Advice, Integer> originalAdviceFlowLocals = Maps.newHashMap();
    private final Map<Advice, Integer> enabledLocals = Maps.newHashMap();
    private final Map<Advice, Integer> travelerLocals = Maps.newHashMap();

    @MonotonicNonNull
    private Label methodStartLabel;
    @MonotonicNonNull
    private Label catchStartLabel;
    private boolean visitedLocalVariableThis;

    WeavingMethodVisitor(MethodVisitor mv, int access, String name, String desc, Type owner,
            Iterable<Advice> advisors, @Nullable String metaHolderInternalName,
            @Nullable Integer methodMetaGroupUniqueNum, boolean bootstrapClassLoader,
            @Nullable MethodVisitor outerMethodVisitor) {
        super(ASM5, mv, access, name, desc);
        this.access = access;
        this.name = name;
        this.owner = owner;
        this.advisors = ImmutableList.copyOf(advisors);
        argumentTypes = Type.getArgumentTypes(desc);
        returnType = Type.getReturnType(desc);
        this.metaHolderInternalName = metaHolderInternalName;
        this.methodMetaGroupUniqueNum = methodMetaGroupUniqueNum;
        this.bootstrapClassLoader = bootstrapClassLoader;
        boolean needsTryCatch = false;
        for (Advice advice : advisors) {
            if (advice.getPointcut().ignoreSelfNested() || advice.getOnThrowAdvice() != null
                    || advice.getOnAfterAdvice() != null) {
                needsTryCatch = true;
                break;
            }
        }
        this.needsTryCatch = needsTryCatch;
        this.outerMethodVisitor = outerMethodVisitor;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (outerMethodVisitor != null) {
            return outerMethodVisitor.visitAnnotation(desc, visible);
        }
        return super.visitAnnotation(desc, visible);
    }

    @Override
    protected void onMethodEnter() {
        methodStartLabel = newLabel();
        visitLabel(methodStartLabel);
        // enabled and traveler locals must be defined outside of the try block so they will be
        // accessible in the catch block
        for (Advice advice : advisors) {
            defineAndEvaluateEnabledLocalVar(advice);
            defineTravelerLocalVar(advice);
        }
        // all advice should be executed outside of the try/catch, otherwise a programming error in
        // the advice will trigger @OnThrow which is confusing at best
        for (Advice advice : advisors) {
            invokeOnBefore(advice, travelerLocals.get(advice));
        }
        if (needsTryCatch) {
            catchStartLabel = newLabel();
            visitLabel(catchStartLabel);
        }
    }

    @Override
    public void visitLocalVariable(String name, String desc, @Nullable String signature,
            Label start, Label end, int index) {
        checkNotNull(methodStartLabel, "Call to onMethodEnter() is required");
        // the JSRInlinerAdapter writes the local variable "this" across different label ranges
        // so visitedLocalVariableThis is checked and updated to ensure this block is only executed
        // once per method
        if (name.equals("this") && !visitedLocalVariableThis) {
            visitedLocalVariableThis = true;
            // this is only so that eclipse debugger will not display <unknown receiving type>
            // inside code when inside of code before the previous method start label
            // (the debugger asks for "this", which is not otherwise available in the new code
            // inserted at the beginning of the method)
            //
            // ClassReader always visits local variables at the very end (just prior to visitMaxs)
            // so this is a good place to put the outer end label for the local variable 'this'
            Label outerEndLabel = newLabel();
            visitLabel(outerEndLabel);
            super.visitLocalVariable(name, desc, signature, methodStartLabel, outerEndLabel, index);
            // at the same time, may as well define local vars for enabled and traveler as
            // applicable
            for (int i = 0; i < advisors.size(); i++) {
                Advice advice = advisors.get(i);
                Integer adviceFlowHolderLocalIndex = adviceFlowHolderLocals.get(advice);
                if (adviceFlowHolderLocalIndex != null) {
                    super.visitLocalVariable("glowroot$advice$flow$holder$" + i,
                            adviceFlowHolderType.getDescriptor(), null, methodStartLabel,
                            outerEndLabel, adviceFlowHolderLocalIndex);
                }
                Integer adviceFlowLocalIndex = originalAdviceFlowLocals.get(advice);
                if (adviceFlowLocalIndex != null) {
                    super.visitLocalVariable("glowroot$advice$flow$" + i,
                            Type.BOOLEAN_TYPE.getDescriptor(), null, methodStartLabel,
                            outerEndLabel, adviceFlowLocalIndex);
                }
                Integer enabledLocalIndex = enabledLocals.get(advice);
                if (enabledLocalIndex != null) {
                    super.visitLocalVariable("glowroot$enabled$" + i,
                            Type.BOOLEAN_TYPE.getDescriptor(), null, methodStartLabel,
                            outerEndLabel, enabledLocalIndex);
                }
                Integer travelerLocalIndex = travelerLocals.get(advice);
                if (travelerLocalIndex != null) {
                    Type travelerType = advice.getTravelerType();
                    if (travelerType == null) {
                        logger.error("visitLocalVariable(): traveler local index is not null,"
                                + " but traveler type is null");
                    } else {
                        super.visitLocalVariable("glowroot$traveler$" + i,
                                travelerType.getDescriptor(), null, methodStartLabel,
                                outerEndLabel, travelerLocalIndex);
                    }
                }
            }
        } else {
            super.visitLocalVariable(name, desc, signature, start, end, index);
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        if (needsTryCatch) {
            checkNotNull(catchStartLabel, "Call to onMethodEnter() is required");
            Label catchEndLabel = newLabel();
            Label catchHandlerLabel2 = newLabel();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchEndLabel,
                    "org/glowroot/weaving/WeavingMethodVisitor$MarkerException");
            visitLabel(catchEndLabel);
            invokeVirtual(MarkerException.TYPE, MarkerException.GET_CAUSE_METHOD);
            throwException();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchHandlerLabel2,
                    "java/lang/Throwable");
            visitLabel(catchHandlerLabel2);
            visitOnThrowAdvice();
            for (Advice advice : Lists.reverse(advisors)) {
                visitOnAfterAdvice(advice);
            }
            resetAdviceFlowIfNecessary();
            throwException();
        }
        super.visitMaxs(maxStack, maxLocals);
    }

    @Override
    protected void onMethodExit(int opcode) {
        // instructions to catch throws will be written (if necessary) in visitMaxs
        if (opcode != ATHROW) {
            if (needsTryCatch) {
                // if an exception occurs inside @OnReturn or @OnAfter advice, it will be caught by
                // the overall try/catch and it will trigger @OnThrow and @OnAfter
                // to prevent this, any exception that occurs inside @OnReturn or @OnAfter is caught
                // and wrapped in a marker exception, and then unwrapped and re-thrown inside
                // the overall try/catch block
                Label innerCatchStartLabel = newLabel();
                Label continueLabel = newLabel();
                Label innerCatchEndLabel = newLabel();

                visitLabel(innerCatchStartLabel);
                for (Advice advice : Lists.reverse(advisors)) {
                    visitOnReturnAdvice(advice, opcode);
                    visitOnAfterAdvice(advice);
                }
                resetAdviceFlowIfNecessary();
                goTo(continueLabel);

                visitTryCatchBlock(innerCatchStartLabel, innerCatchEndLabel, innerCatchEndLabel,
                        "java/lang/Throwable");
                visitLabel(innerCatchEndLabel);
                invokeStatic(MarkerException.TYPE, MarkerException.STATIC_FACTORY_METHOD);
                throwException();

                visitLabel(continueLabel);
            } else {
                for (Advice advice : Lists.reverse(advisors)) {
                    visitOnReturnAdvice(advice, opcode);
                    visitOnAfterAdvice(advice);
                }
                resetAdviceFlowIfNecessary();
            }
        }
    }

    private void defineAndEvaluateEnabledLocalVar(Advice advice) {
        Integer enabledLocal = null;
        Method isEnabledAdvice = advice.getIsEnabledAdvice();
        if (isEnabledAdvice != null) {
            loadMethodParameters(advice.getIsEnabledParameters(), 0, -1, advice.getAdviceType(),
                    IsEnabled.class);
            invokeStatic(advice.getAdviceType(), isEnabledAdvice);
            enabledLocal = newLocal(Type.BOOLEAN_TYPE);
            enabledLocals.put(advice, enabledLocal);
            storeLocal(enabledLocal);
        }
        if (advice.getPointcut().ignoreSelfNested()) {
            // originalAdviceFlowLocal must be defined/initialized outside of any code branches
            // since it is referenced later on in resetAdviceFlowIfNecessary()
            int adviceFlowHolderLocal = newLocal(adviceFlowHolderType);
            adviceFlowHolderLocals.put(advice, adviceFlowHolderLocal);
            visitInsn(ACONST_NULL);
            storeLocal(adviceFlowHolderLocal);

            int originalAdviceFlowLocal = newLocal(Type.BOOLEAN_TYPE);
            originalAdviceFlowLocals.put(advice, originalAdviceFlowLocal);
            push(false);
            storeLocal(originalAdviceFlowLocal);

            Label setAdviceFlowBlockEnd = newLabel();
            if (enabledLocal != null) {
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
            } else {
                enabledLocal = newLocal(Type.BOOLEAN_TYPE);
                enabledLocals.put(advice, enabledLocal);
                // it will be initialized below
            }
            getStatic(advice.getAdviceType(), "glowroot$advice$flow$outer$holder",
                    adviceFlowOuterHolderType);
            invokeVirtual(adviceFlowOuterHolderType, Method.getMethod(
                    AdviceFlowHolder.class.getName() + " getInnerHolder()"));
            dup();
            storeLocal(adviceFlowHolderLocal);
            invokeVirtual(adviceFlowHolderType, Method.getMethod("boolean isTop()"));
            Label isTopBlockStart = newLabel();
            dup();
            storeLocal(originalAdviceFlowLocal);
            visitJumpInsn(IFNE, isTopBlockStart);
            // !isTop()
            push(false);
            storeLocal(enabledLocal);
            goTo(setAdviceFlowBlockEnd);
            // enabled
            visitLabel(isTopBlockStart);
            loadLocal(adviceFlowHolderLocal);
            push(false);
            // note that setTop() is only called if enabled is true, so it only needs to be reset
            // at the end of the advice if enabled is true
            invokeVirtual(adviceFlowHolderType, Method.getMethod("void setTop(boolean)"));
            push(true);
            storeLocal(enabledLocal);
            visitLabel(setAdviceFlowBlockEnd);
        }
    }

    private void defineTravelerLocalVar(Advice advice) {
        Method onBeforeAdvice = advice.getOnBeforeAdvice();
        if (onBeforeAdvice == null) {
            return;
        }
        Type travelerType = advice.getTravelerType();
        if (travelerType == null) {
            return;
        }
        // have to initialize it with a value, otherwise it won't be defined in the outer scope
        int travelerLocal = newLocal(travelerType);
        pushDefault(travelerType);
        storeLocal(travelerLocal);
        travelerLocals.put(advice, travelerLocal);
    }

    private void invokeOnBefore(Advice advice, @Nullable Integer travelerLocal) {
        Method onBeforeAdvice = advice.getOnBeforeAdvice();
        if (onBeforeAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onBeforeBlockEnd = null;
        if (enabledLocal != null) {
            onBeforeBlockEnd = newLabel();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onBeforeBlockEnd);
        }
        loadMethodParameters(advice.getOnBeforeParameters(), 0, -1, advice.getAdviceType(),
                OnBefore.class);
        invokeStatic(advice.getAdviceType(), onBeforeAdvice);
        if (travelerLocal != null) {
            storeLocal(travelerLocal);
        }
        if (onBeforeBlockEnd != null) {
            visitLabel(onBeforeBlockEnd);
        }
    }

    private void visitOnReturnAdvice(Advice advice, int opcode) {
        Method onReturnAdvice = advice.getOnReturnAdvice();
        if (onReturnAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onReturnBlockEnd = null;
        if (enabledLocal != null) {
            onReturnBlockEnd = newLabel();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onReturnBlockEnd);
        }
        weaveOnReturnAdvice(opcode, advice, onReturnAdvice);
        if (onReturnBlockEnd != null) {
            visitLabel(onReturnBlockEnd);
        }
    }

    private void weaveOnReturnAdvice(int opcode, Advice advice, Method onReturnAdvice) {
        if (onReturnAdvice.getArgumentTypes().length > 0) {
            // @BindReturn must be the first argument to @OnReturn (if present)
            int startIndex = 0;
            AdviceParameter parameter = advice.getOnReturnParameters().get(0);
            switch (parameter.getKind()) {
                case RETURN:
                    loadNonOptionalReturnValue(opcode, parameter);
                    startIndex = 1;
                    break;
                case OPTIONAL_RETURN:
                    loadOptionalReturnValue(opcode);
                    startIndex = 1;
                    break;
                default:
                    // first argument is not @BindReturn
                    break;
            }
            loadMethodParameters(advice.getOnReturnParameters(), startIndex,
                    travelerLocals.get(advice), advice.getAdviceType(), OnReturn.class);
        }
        int sort = onReturnAdvice.getReturnType().getSort();
        if (sort == Type.LONG || sort == Type.DOUBLE) {
            pop2();
        } else if (sort != Type.VOID) {
            pop();
        }
        invokeStatic(advice.getAdviceType(), onReturnAdvice);
    }

    private void loadNonOptionalReturnValue(int opcode, AdviceParameter parameter) {
        if (opcode == RETURN) {
            logger.warn("cannot use @BindReturn on a @Pointcut returning void");
            pushDefault(parameter.getType());
        } else {
            boolean primitive = parameter.getType().getSort() < Type.ARRAY;
            loadReturnValue(opcode, !primitive);
        }
    }

    private void loadOptionalReturnValue(int opcode) {
        if (opcode == RETURN) {
            // void
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/weaving/VoidReturn",
                    "getInstance", "()Lorg/glowroot/api/OptionalReturn;", false);
        } else {
            loadReturnValue(opcode, true);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/weaving/NonVoidReturn", "create",
                    "(Ljava/lang/Object;)Lorg/glowroot/api/OptionalReturn;", false);
        }
    }

    private void loadReturnValue(int opcode, boolean autobox) {
        if (opcode == ARETURN || opcode == ATHROW) {
            dup();
        } else {
            if (opcode == LRETURN || opcode == DRETURN) {
                dup2();
            } else {
                dup();
            }
            if (autobox) {
                box(returnType);
            }
        }
    }

    private void visitOnThrowAdvice() {
        for (Advice advice : Lists.reverse(advisors)) {
            Method onThrowAdvice = advice.getOnThrowAdvice();
            if (onThrowAdvice == null) {
                continue;
            }
            Integer enabledLocal = enabledLocals.get(advice);
            Label onThrowBlockEnd = null;
            if (enabledLocal != null) {
                onThrowBlockEnd = newLabel();
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, onThrowBlockEnd);
            }
            if (onThrowAdvice.getArgumentTypes().length == 0) {
                invokeStatic(advice.getAdviceType(), onThrowAdvice);
            } else {
                int startIndex = 0;
                if (advice.getOnThrowParameters().get(0).getKind() == ParameterKind.THROWABLE) {
                    // @BindThrowable must be the first argument to @OnThrow (if present)
                    dup();
                    startIndex++;
                }
                loadMethodParameters(advice.getOnThrowParameters(), startIndex,
                        travelerLocals.get(advice), advice.getAdviceType(), OnThrow.class);
                invokeStatic(advice.getAdviceType(), onThrowAdvice);
            }
            if (onThrowBlockEnd != null) {
                visitLabel(onThrowBlockEnd);
            }
        }
    }

    private void visitOnAfterAdvice(Advice advice) {
        Method onAfterAdvice = advice.getOnAfterAdvice();
        if (onAfterAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onAfterBlockEnd = null;
        if (enabledLocal != null) {
            onAfterBlockEnd = newLabel();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onAfterBlockEnd);
        }
        loadMethodParameters(advice.getOnAfterParameters(), 0, travelerLocals.get(advice),
                advice.getAdviceType(), OnAfter.class);
        invokeStatic(advice.getAdviceType(), onAfterAdvice);
        if (onAfterBlockEnd != null) {
            visitLabel(onAfterBlockEnd);
        }
    }

    private void resetAdviceFlowIfNecessary() {
        for (Advice advice : advisors) {
            if (advice.getPointcut().ignoreSelfNested()) {
                Integer enabledLocal = enabledLocals.get(advice);
                Integer originalAdviceFlowLocal = originalAdviceFlowLocals.get(advice);
                Integer adviceFlowHolderLocal = adviceFlowHolderLocals.get(advice);
                // enabledLocal is non-null for all advice
                checkNotNull(enabledLocal, "enabledLocal is null");
                // adviceFlowLocal is non-null for all advice with ignoreSelfNested = true
                // (same condition as tested above)
                checkNotNull(originalAdviceFlowLocal, "originalAdviceFlowLocal is null");
                // adviceFlowHolderLocal is non-null for all advice with ignoreSelfNested = true
                // (same condition as tested above)
                checkNotNull(adviceFlowHolderLocal, "adviceFlowHolderLocal is null");

                Label setAdviceFlowBlockEnd = newLabel();
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
                loadLocal(originalAdviceFlowLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
                // isTop was true at the beginning of the advice, need to reset it now
                loadLocal(adviceFlowHolderLocal);
                push(true);
                invokeVirtual(adviceFlowHolderType, Method.getMethod("void setTop(boolean)"));
                visitLabel(setAdviceFlowBlockEnd);
            }
        }
    }

    private void loadMethodParameters(ImmutableList<AdviceParameter> parameters, int startIndex,
            @Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType) {

        int argIndex = 0;
        for (int i = startIndex; i < parameters.size(); i++) {
            AdviceParameter parameter = parameters.get(i);
            switch (parameter.getKind()) {
                case RECEIVER:
                    loadTarget();
                    break;
                case METHOD_ARG:
                    loadMethodParameters(adviceType, annotationType, argIndex++, parameter);
                    break;
                case METHOD_ARG_ARRAY:
                    loadArgArray();
                    break;
                case METHOD_NAME:
                    loadMethodName();
                    break;
                case TRAVELER:
                    loadTraveler(travelerLocal, adviceType, annotationType, parameter);
                    break;
                case CLASS_META:
                    checkNotNull(metaHolderInternalName);
                    loadClassMeta(parameter);
                    break;
                case METHOD_META:
                    checkNotNull(metaHolderInternalName);
                    checkNotNull(methodMetaGroupUniqueNum);
                    loadMethodMeta(parameter);
                    break;
                default:
                    // this should have been caught during Advice construction, but just in case:
                    logger.warn("the @{} method in {} has an unexpected parameter kind {} at index"
                            + " {}", annotationType.getSimpleName(), adviceType.getClassName(),
                            parameter.getKind(), i);
                    pushDefault(parameter.getType());
                    break;
            }
        }
    }

    private void loadTarget() {
        if (!Modifier.isStatic(access)) {
            loadThis();
        } else {
            // cannot use push(Type) since .class constants are not supported in classes
            // that were compiled to jdk 1.4
            push(owner.getClassName());
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false);
        }
    }

    private void loadMethodParameters(Type adviceType, Class<? extends Annotation> annotationType,
            int argIndex, AdviceParameter parameter) {
        if (argIndex >= argumentTypes.length) {
            logger.warn("the @{} method in {} has more @{} arguments than the number of args in"
                    + " the target method", annotationType.getSimpleName(),
                    adviceType.getClassName(), BindParameter.class.getSimpleName());
            pushDefault(parameter.getType());
            return;
        }
        loadArg(argIndex);
        boolean primitive = parameter.getType().getSort() < Type.ARRAY;
        if (!primitive) {
            // autobox
            box(argumentTypes[argIndex]);
        }
    }

    private void loadMethodName() {
        if (name.contains("$glowroot$metric$")) {
            // strip off internal metric identifier from method name
            push(name.substring(0, name.indexOf("$glowroot$metric$")));
        } else {
            push(name);
        }
    }

    private void loadTraveler(@Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType, AdviceParameter parameter) {
        if (travelerLocal == null) {
            logger.warn("the @{} method in {} requested @{} but @{} returns void",
                    annotationType.getSimpleName(), adviceType.getClassName(),
                    BindTraveler.class.getSimpleName(), OnBefore.class.getSimpleName());
            pushDefault(parameter.getType());
        } else {
            loadLocal(travelerLocal);
        }
    }

    @RequiresNonNull("metaHolderInternalName")
    private void loadClassMeta(AdviceParameter parameter) {
        Type classMetaFieldType = parameter.getType();
        String classMetaFieldName = "glowroot$class$meta$"
                + classMetaFieldType.getInternalName().replace('/', '$');
        if (bootstrapClassLoader) {
            int index = BootstrapMetaHolders.reserveClassMetaHolderIndex(metaHolderInternalName,
                    classMetaFieldName);
            push(index);
            Method method = Method.getMethod("Object getClassMeta(int)");
            invokeStatic(Type.getType(BootstrapMetaHolders.class), method);
        } else {
            getStatic(Type.getObjectType(metaHolderInternalName), classMetaFieldName,
                    classMetaFieldType);
        }
    }

    @RequiresNonNull({"metaHolderInternalName", "methodMetaGroupUniqueNum"})
    private void loadMethodMeta(AdviceParameter parameter) {
        Type methodMetaFieldType = parameter.getType();
        String methodMetaFieldName = "glowroot$method$meta$" + methodMetaGroupUniqueNum + '$'
                + methodMetaFieldType.getInternalName().replace('/', '$');
        if (bootstrapClassLoader) {
            int index = BootstrapMetaHolders.reserveMethodMetaHolderIndex(metaHolderInternalName,
                    methodMetaFieldName);
            push(index);
            Method method = Method.getMethod("Object getMethodMeta(int)");
            invokeStatic(Type.getType(BootstrapMetaHolders.class), method);
        } else {
            getStatic(Type.getObjectType(metaHolderInternalName), methodMetaFieldName,
                    methodMetaFieldType);
        }
    }

    private void pushDefault(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                push(false);
                break;
            case Type.CHAR:
                push(0);
                break;
            case Type.BYTE:
                push(0);
                break;
            case Type.SHORT:
                push(0);
                break;
            case Type.INT:
                push(0);
                break;
            case Type.FLOAT:
                push(0f);
                break;
            case Type.LONG:
                push(0L);
                break;
            case Type.DOUBLE:
                push(0.0);
                break;
            default:
                visitInsn(ACONST_NULL);
                break;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("access", access)
                .add("name", name)
                .add("owner", owner)
                .add("advisors", advisors)
                .add("argumentTypes", argumentTypes)
                .add("adviceFlowHolderLocals", adviceFlowHolderLocals)
                .add("enabledLocals", enabledLocals)
                .add("travelerLocals", travelerLocals)
                .add("needsTryCatch", needsTryCatch)
                .add("catchStartLabel", catchStartLabel)
                .add("methodStartLabel", methodStartLabel)
                .toString();
    }

    // this is used to wrap exceptions that occur inside of @OnReturn and @OnAfter when the
    // exception would otherwise be caught by the overall try/catch triggering @OnThrow and @OnAfter
    //
    // needs to be public since it is accessed from bytecode injected into other packages
    @UsedByGeneratedBytecode
    @SuppressWarnings("serial")
    public static class MarkerException extends RuntimeException {
        private static final Type TYPE = Type.getType(MarkerException.class);
        private static final Method STATIC_FACTORY_METHOD;
        private static final Method GET_CAUSE_METHOD;
        static {
            try {
                STATIC_FACTORY_METHOD = Method.getMethod(Reflections.getMethod(
                        MarkerException.class, "from", Throwable.class));
                GET_CAUSE_METHOD =
                        Method.getMethod(Reflections.getMethod(Throwable.class, "getCause"));
            } catch (ReflectiveException e) {
                // unrecoverable error
                throw new AssertionError(e);
            }
        }
        // static methods are easier to call via bytecode than constructors
        @UsedByGeneratedBytecode
        public static MarkerException from(Throwable cause) {
            return new MarkerException(cause);
        }
        private MarkerException(Throwable cause) {
            super(cause);
        }
    }
}

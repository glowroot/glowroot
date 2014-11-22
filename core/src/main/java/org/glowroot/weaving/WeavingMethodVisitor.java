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
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
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
import org.glowroot.markers.UsedByGeneratedBytecode;
import org.glowroot.weaving.Advice.AdviceParameter;
import org.glowroot.weaving.Advice.ParameterKind;
import org.glowroot.weaving.AdviceFlowOuterHolder.AdviceFlowHolder;

import static com.google.common.base.Preconditions.checkNotNull;

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
    private final @Nullable String metaHolderInternalName;
    private final @Nullable Integer methodMetaGroupUniqueNum;
    private final boolean bootstrapClassLoader;
    private final boolean needsTryCatch;
    private final @Nullable MethodVisitor outerMethodVisitor;

    private final Map<Advice, Integer> adviceFlowHolderLocals = Maps.newHashMap();
    // the adviceFlow stores the value in the holder at the beginning of the advice so the holder
    // can be reset at the end of the advice
    private final Map<Advice, Integer> originalAdviceFlowLocals = Maps.newHashMap();
    private final Map<Advice, Integer> enabledLocals = Maps.newHashMap();
    private final Map<Advice, Integer> travelerLocals = Maps.newHashMap();

    private @MonotonicNonNull Label methodStartLabel;
    private @MonotonicNonNull Label catchStartLabel;
    private boolean visitedLocalVariableThis;

    private Object/*@MonotonicNonNull*/[] implicitFrame;

    WeavingMethodVisitor(MethodVisitor mv, int access, String name, String desc, Type owner,
            Iterable<Advice> advisors, @Nullable String metaHolderInternalName,
            @Nullable Integer methodMetaGroupUniqueNum, boolean bootstrapClassLoader,
            @Nullable MethodVisitor outerMethodVisitor) {
        super(ASM5, new FrameDeduppingMethodVisitor(mv), access, name, desc);
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
            if (advice.pointcut().ignoreSelfNested() || advice.onThrowAdvice() != null
                    || advice.onAfterAdvice() != null) {
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
        methodStartLabel = new Label();
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
            catchStartLabel = new Label();
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
            Label outerEndLabel = new Label();
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
                    Type travelerType = advice.travelerType();
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
            Label catchEndLabel = new Label();
            Label catchHandlerLabel2 = new Label();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchEndLabel,
                    "org/glowroot/weaving/WeavingMethodVisitor$MarkerException");
            visitLabel(catchEndLabel);
            visitImplicitFramePlus("org/glowroot/weaving/WeavingMethodVisitor$MarkerException");
            visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/weaving/WeavingMethodVisitor$MarkerException",
                    "getCause", "()Ljava/lang/Throwable;", false);
            visitInsn(ATHROW);
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchHandlerLabel2,
                    "java/lang/Throwable");
            visitImplicitFramePlus("java/lang/Throwable");
            visitLabel(catchHandlerLabel2);
            visitOnThrowAdvice();
            for (Advice advice : Lists.reverse(advisors)) {
                visitOnAfterAdvice(advice, true);
            }
            resetAdviceFlowIfNecessary(true);
            visitInsn(ATHROW);
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
                Label innerCatchStartLabel = new Label();
                Label continueLabel = new Label();
                Label innerCatchEndLabel = new Label();

                visitLabel(innerCatchStartLabel);
                for (Advice advice : Lists.reverse(advisors)) {
                    visitOnReturnAdvice(advice, opcode);
                    visitOnAfterAdvice(advice, false);
                }
                resetAdviceFlowIfNecessary(false);
                visitJumpInsn(GOTO, continueLabel);

                visitTryCatchBlock(innerCatchStartLabel, innerCatchEndLabel, innerCatchEndLabel,
                        "java/lang/Throwable");
                visitLabel(innerCatchEndLabel);
                visitImplicitFramePlus("java/lang/Throwable");
                visitMethodInsn(INVOKESTATIC,
                        "org/glowroot/weaving/WeavingMethodVisitor$MarkerException",
                        "from", "(Ljava/lang/Throwable;)" + MarkerException.TYPE.getDescriptor(),
                        false);
                visitInsn(ATHROW);

                visitLabel(continueLabel);
                if (returnType.getSort() == Type.VOID) {
                    visitImplicitFramePlus();
                } else {
                    visitImplicitFramePlus(returnType);
                }
            } else {
                for (Advice advice : Lists.reverse(advisors)) {
                    visitOnReturnAdvice(advice, opcode);
                    visitOnAfterAdvice(advice, false);
                }
                resetAdviceFlowIfNecessary(false);
            }
        }
    }

    private void defineAndEvaluateEnabledLocalVar(Advice advice) {
        Integer enabledLocal = null;
        Method isEnabledAdvice = advice.isEnabledAdvice();
        if (isEnabledAdvice != null) {
            loadMethodParameters(advice.isEnabledParameters(), 0, -1, advice.adviceType(),
                    IsEnabled.class);
            visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                    isEnabledAdvice.getName(), isEnabledAdvice.getDescriptor(), false);
            enabledLocal = newLocal(Type.BOOLEAN_TYPE);
            enabledLocals.put(advice, enabledLocal);
            storeLocal(enabledLocal);
        }
        if (advice.pointcut().ignoreSelfNested()) {
            // originalAdviceFlowLocal must be defined/initialized outside of any code branches
            // since it is referenced later on in resetAdviceFlowIfNecessary()
            int adviceFlowHolderLocal = newLocal(adviceFlowHolderType);
            adviceFlowHolderLocals.put(advice, adviceFlowHolderLocal);
            visitInsn(ACONST_NULL);
            storeLocal(adviceFlowHolderLocal);

            int originalAdviceFlowLocal = newLocal(Type.BOOLEAN_TYPE);
            originalAdviceFlowLocals.put(advice, originalAdviceFlowLocal);
            visitInsn(ICONST_0);
            storeLocal(originalAdviceFlowLocal);

            Label setAdviceFlowBlockEnd = new Label();
            if (enabledLocal != null) {
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
            } else {
                enabledLocal = newLocal(Type.BOOLEAN_TYPE);
                enabledLocals.put(advice, enabledLocal);
                // temporary initial value to help with Java 7 stack frames
                visitInsn(ICONST_0);
                storeLocal(enabledLocal);
            }
            visitFieldInsn(GETSTATIC, advice.adviceType().getInternalName(),
                    "glowroot$advice$flow$outer$holder", adviceFlowOuterHolderType.getDescriptor());
            visitMethodInsn(INVOKEVIRTUAL, adviceFlowOuterHolderType.getInternalName(),
                    "getInnerHolder", "()" + adviceFlowHolderType.getDescriptor(), false);
            visitInsn(DUP);
            storeLocal(adviceFlowHolderLocal);
            visitMethodInsn(INVOKEVIRTUAL, adviceFlowHolderType.getInternalName(),
                    "isTop", "()Z", false);
            Label isTopBlockStart = new Label();
            visitInsn(DUP);
            storeLocal(originalAdviceFlowLocal);
            visitJumpInsn(IFNE, isTopBlockStart);
            // !isTop()
            visitInsn(ICONST_0);
            storeLocal(enabledLocal);
            visitJumpInsn(GOTO, setAdviceFlowBlockEnd);
            // enabled
            visitLabel(isTopBlockStart);
            visitImplicitFramePlus();
            loadLocal(adviceFlowHolderLocal);
            visitInsn(ICONST_0);
            // note that setTop() is only called if enabled is true, so it only needs to be reset
            // at the end of the advice if enabled is true
            visitMethodInsn(INVOKEVIRTUAL, adviceFlowHolderType.getInternalName(),
                    "setTop", "(Z)V", false);
            visitInsn(ICONST_1);
            storeLocal(enabledLocal);
            visitLabel(setAdviceFlowBlockEnd);
            visitImplicitFramePlus();
        }
    }

    private void defineTravelerLocalVar(Advice advice) {
        Method onBeforeAdvice = advice.onBeforeAdvice();
        if (onBeforeAdvice == null) {
            return;
        }
        Type travelerType = advice.travelerType();
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
        Method onBeforeAdvice = advice.onBeforeAdvice();
        if (onBeforeAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onBeforeBlockEnd = null;
        if (enabledLocal != null) {
            onBeforeBlockEnd = new Label();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onBeforeBlockEnd);
        }
        loadMethodParameters(advice.onBeforeParameters(), 0, -1, advice.adviceType(),
                OnBefore.class);
        visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                onBeforeAdvice.getName(), onBeforeAdvice.getDescriptor(), false);
        if (travelerLocal != null) {
            storeLocal(travelerLocal);
        }
        if (onBeforeBlockEnd != null) {
            visitLabel(onBeforeBlockEnd);
            visitImplicitFramePlus();
        }
    }

    private void visitOnReturnAdvice(Advice advice, int opcode) {
        Method onReturnAdvice = advice.onReturnAdvice();
        if (onReturnAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onReturnBlockEnd = null;
        if (enabledLocal != null) {
            onReturnBlockEnd = new Label();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onReturnBlockEnd);
        }
        weaveOnReturnAdvice(opcode, advice, onReturnAdvice);
        if (onReturnBlockEnd != null) {
            visitLabel(onReturnBlockEnd);
            if (returnType.getSort() == Type.VOID) {
                visitImplicitFramePlus();
            } else {
                visitImplicitFramePlus(returnType);
            }
        }
    }

    private void weaveOnReturnAdvice(int opcode, Advice advice, Method onReturnAdvice) {
        if (onReturnAdvice.getArgumentTypes().length > 0) {
            // @BindReturn must be the first argument to @OnReturn (if present)
            int startIndex = 0;
            AdviceParameter parameter = advice.onReturnParameters().get(0);
            switch (parameter.kind()) {
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
            loadMethodParameters(advice.onReturnParameters(), startIndex,
                    travelerLocals.get(advice), advice.adviceType(), OnReturn.class);
        }
        int sort = onReturnAdvice.getReturnType().getSort();
        if (sort == Type.LONG || sort == Type.DOUBLE) {
            visitInsn(POP2);
        } else if (sort != Type.VOID) {
            visitInsn(POP);
        }
        visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                onReturnAdvice.getName(), onReturnAdvice.getDescriptor(), false);
    }

    private void loadNonOptionalReturnValue(int opcode, AdviceParameter parameter) {
        if (opcode == RETURN) {
            logger.warn("cannot use @BindReturn on a @Pointcut returning void");
            pushDefault(parameter.type());
        } else {
            boolean primitive = parameter.type().getSort() < Type.ARRAY;
            loadReturnValue(opcode, !primitive);
        }
    }

    private void loadOptionalReturnValue(int opcode) {
        if (opcode == RETURN) {
            // void
            visitMethodInsn(INVOKESTATIC, "org/glowroot/weaving/VoidReturn",
                    "getInstance", "()Lorg/glowroot/api/OptionalReturn;", false);
        } else {
            loadReturnValue(opcode, true);
            visitMethodInsn(INVOKESTATIC, "org/glowroot/weaving/NonVoidReturn", "create",
                    "(Ljava/lang/Object;)Lorg/glowroot/api/OptionalReturn;", false);
        }
    }

    private void loadReturnValue(int opcode, boolean autobox) {
        if (opcode == ARETURN || opcode == ATHROW) {
            visitInsn(DUP);
        } else {
            if (opcode == LRETURN || opcode == DRETURN) {
                visitInsn(DUP2);
            } else {
                visitInsn(DUP);
            }
            if (autobox) {
                box(returnType);
            }
        }
    }

    private void visitOnThrowAdvice() {
        for (Advice advice : Lists.reverse(advisors)) {
            Method onThrowAdvice = advice.onThrowAdvice();
            if (onThrowAdvice == null) {
                continue;
            }
            Integer enabledLocal = enabledLocals.get(advice);
            Label onThrowBlockEnd = null;
            if (enabledLocal != null) {
                onThrowBlockEnd = new Label();
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, onThrowBlockEnd);
            }
            if (onThrowAdvice.getArgumentTypes().length == 0) {
                visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                        onThrowAdvice.getName(), onThrowAdvice.getDescriptor(), false);
            } else {
                int startIndex = 0;
                if (advice.onThrowParameters().get(0).kind() == ParameterKind.THROWABLE) {
                    // @BindThrowable must be the first argument to @OnThrow (if present)
                    visitInsn(DUP);
                    startIndex++;
                }
                loadMethodParameters(advice.onThrowParameters(), startIndex,
                        travelerLocals.get(advice), advice.adviceType(), OnThrow.class);
                visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                        onThrowAdvice.getName(), onThrowAdvice.getDescriptor(), false);
            }
            if (onThrowBlockEnd != null) {
                visitLabel(onThrowBlockEnd);
                visitImplicitFramePlus("java/lang/Throwable");
            }
        }
    }

    private void visitOnAfterAdvice(Advice advice, boolean insideOverallCatchHandler) {
        Method onAfterAdvice = advice.onAfterAdvice();
        if (onAfterAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onAfterBlockEnd = null;
        if (enabledLocal != null) {
            onAfterBlockEnd = new Label();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onAfterBlockEnd);
        }
        loadMethodParameters(advice.onAfterParameters(), 0, travelerLocals.get(advice),
                advice.adviceType(), OnAfter.class);
        visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                onAfterAdvice.getName(), onAfterAdvice.getDescriptor(), false);
        if (onAfterBlockEnd != null) {
            visitLabel(onAfterBlockEnd);
            if (insideOverallCatchHandler) {
                visitImplicitFramePlus("java/lang/Throwable");
            } else if (returnType.getSort() == Type.VOID) {
                visitImplicitFramePlus();
            } else {
                visitImplicitFramePlus(returnType);
            }
        }
    }

    private void resetAdviceFlowIfNecessary(boolean insideOverallCatchHandler) {
        for (Advice advice : advisors) {
            if (advice.pointcut().ignoreSelfNested()) {
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

                Label setAdviceFlowBlockEnd = new Label();
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
                loadLocal(originalAdviceFlowLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
                // isTop was true at the beginning of the advice, need to reset it now
                loadLocal(adviceFlowHolderLocal);
                visitInsn(ICONST_1);
                visitMethodInsn(INVOKEVIRTUAL, adviceFlowHolderType.getInternalName(),
                        "setTop", "(Z)V", false);
                visitLabel(setAdviceFlowBlockEnd);
                if (insideOverallCatchHandler) {
                    visitImplicitFramePlus("java/lang/Throwable");
                } else if (returnType.getSort() == Type.VOID) {
                    visitImplicitFramePlus();
                } else {
                    visitImplicitFramePlus(returnType);
                }
            }
        }
    }

    private void loadMethodParameters(List<AdviceParameter> parameters, int startIndex,
            @Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType) {

        int argIndex = 0;
        for (int i = startIndex; i < parameters.size(); i++) {
            AdviceParameter parameter = parameters.get(i);
            switch (parameter.kind()) {
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
                            parameter.kind(), i);
                    pushDefault(parameter.type());
                    break;
            }
        }
    }

    private void loadTarget() {
        if (!Modifier.isStatic(access)) {
            visitVarInsn(ALOAD, 0);
        } else {
            // cannot use push(Type) since .class constants are not supported in classes
            // that were compiled to jdk 1.4
            visitLdcInsn(owner.getClassName());
            visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false);
        }
    }

    private void loadMethodParameters(Type adviceType, Class<? extends Annotation> annotationType,
            int argIndex, AdviceParameter parameter) {
        if (argIndex >= argumentTypes.length) {
            logger.warn("the @{} method in {} has more @{} arguments than the number of args in"
                    + " the target method", annotationType.getSimpleName(),
                    adviceType.getClassName(), BindParameter.class.getSimpleName());
            pushDefault(parameter.type());
            return;
        }
        loadArg(argIndex);
        boolean primitive = parameter.type().getSort() < Type.ARRAY;
        if (!primitive) {
            // autobox
            box(argumentTypes[argIndex]);
        }
    }

    private void loadMethodName() {
        if (name.contains("$glowroot$metric$")) {
            // strip off internal metric identifier from method name
            visitLdcInsn(name.substring(0, name.indexOf("$glowroot$metric$")));
        } else {
            visitLdcInsn(name);
        }
    }

    private void loadTraveler(@Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType, AdviceParameter parameter) {
        if (travelerLocal == null) {
            logger.warn("the @{} method in {} requested @{} but @{} returns void",
                    annotationType.getSimpleName(), adviceType.getClassName(),
                    BindTraveler.class.getSimpleName(), OnBefore.class.getSimpleName());
            pushDefault(parameter.type());
        } else {
            loadLocal(travelerLocal);
        }
    }

    @RequiresNonNull("metaHolderInternalName")
    private void loadClassMeta(AdviceParameter parameter) {
        Type classMetaFieldType = parameter.type();
        String classMetaFieldName = "glowroot$class$meta$"
                + classMetaFieldType.getInternalName().replace('/', '$');
        if (bootstrapClassLoader) {
            int index = BootstrapMetaHolders.reserveClassMetaHolderIndex(metaHolderInternalName,
                    classMetaFieldName);
            push(index);
            visitMethodInsn(INVOKESTATIC, "org/glowroot/weaving/BootstrapMetaHolders",
                    "getClassMeta", "(I)Ljava/lang/Object;", false);
        } else {
            visitFieldInsn(GETSTATIC, metaHolderInternalName, classMetaFieldName,
                    classMetaFieldType.getDescriptor());
        }
    }

    @RequiresNonNull({"metaHolderInternalName", "methodMetaGroupUniqueNum"})
    private void loadMethodMeta(AdviceParameter parameter) {
        Type methodMetaFieldType = parameter.type();
        String methodMetaFieldName = "glowroot$method$meta$" + methodMetaGroupUniqueNum + '$'
                + methodMetaFieldType.getInternalName().replace('/', '$');
        if (bootstrapClassLoader) {
            int index = BootstrapMetaHolders.reserveMethodMetaHolderIndex(metaHolderInternalName,
                    methodMetaFieldName);
            push(index);
            visitMethodInsn(INVOKESTATIC, "org/glowroot/weaving/BootstrapMetaHolders",
                    "getMethodMeta", "(I)Ljava/lang/Object;", false);
        } else {
            visitFieldInsn(GETSTATIC, metaHolderInternalName, methodMetaFieldName,
                    methodMetaFieldType.getDescriptor());
        }
    }

    private void visitImplicitFramePlus() {
        visitImplicitFramePlus(null);
    }

    private void visitImplicitFramePlus(@Nullable Object stack) {
        if (implicitFrame == null) {
            createImplicitFrame();
        }
        if (stack == null) {
            visitFrame(F_NEW, implicitFrame.length, implicitFrame, 0, null);
        } else if (stack instanceof String) {
            visitFrame(F_NEW, implicitFrame.length, implicitFrame, 1, new Object[] {stack});
        } else if (stack instanceof Type) {
            visitFrame(F_NEW, implicitFrame.length, implicitFrame, 1,
                    new Object[] {getStackFrameVarType((Type) stack)});
        } else {
            throw new AssertionError("Unexpected argument type: " + stack.getClass().getName());
        }
    }

    @EnsuresNonNull("implicitFrame")
    private void createImplicitFrame() {
        int i;
        if (Modifier.isStatic(access)) {
            implicitFrame = new Object[argumentTypes.length];
            i = 0;
        } else {
            implicitFrame = new Object[argumentTypes.length + 1];
            implicitFrame[0] = owner.getInternalName();
            i = 1;
        }
        for (Type argumentType : argumentTypes) {
            implicitFrame[i++] = getStackFrameVarType(argumentType);
        }
    }

    private Object getStackFrameVarType(Type type) throws AssertionError {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return INTEGER;
            case Type.FLOAT:
                return FLOAT;
            case Type.LONG:
                return LONG;
            case Type.DOUBLE:
                return DOUBLE;
            case Type.ARRAY:
            case Type.OBJECT:
            case Type.METHOD:
                return type.getInternalName();
            default:
                throw new AssertionError("Unexpected type: " + type);
        }
    }

    private void pushDefault(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                push(false);
                break;
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                visitInsn(ICONST_0);
                break;
            case Type.FLOAT:
                visitInsn(FCONST_0);
                break;
            case Type.LONG:
                visitInsn(LCONST_0);
                break;
            case Type.DOUBLE:
                visitInsn(DCONST_0);
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

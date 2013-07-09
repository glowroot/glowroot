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
import java.util.List;
import java.util.Map;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.markers.UsedByGeneratedBytecode;
import io.informant.weaving.Advice.ParameterKind;
import io.informant.weaving.AdviceFlowOuterHolder.AdviceFlowHolder;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingMethodVisitor extends AdviceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WeavingMethodVisitor.class);

    private static final Type adviceFlowOuterHolderType = Type.getType(AdviceFlowOuterHolder.class);
    private static final Type adviceFlowHolderType = Type.getType(AdviceFlowHolder.class);

    private final int access;
    private final String name;
    private final Type owner;
    private final ImmutableList<Advice> advisors;
    private final Type[] argumentTypes;
    private final Type returnType;

    private final Map<Advice, Integer> adviceFlowHolderLocals = Maps.newHashMap();
    // the adviceFlow stores the value in the holder at the beginning of the advice so the holder
    // can be reset at the end of the advice
    private final Map<Advice, Integer> adviceFlowLocals = Maps.newHashMap();
    private final Map<Advice, Integer> enabledLocals = Maps.newHashMap();
    private final Map<Advice, Integer> travelerLocals = Maps.newHashMap();

    private boolean needsTryCatch;
    @LazyNonNull
    private Label methodStartLabel;
    @LazyNonNull
    private Label catchStartLabel;
    private boolean visitedLocalVariableThis;

    WeavingMethodVisitor(MethodVisitor mv, int access, String name, String desc, Type owner,
            @ReadOnly List<Advice> advisors) {
        super(ASM4, mv, access, name, desc);
        this.access = access;
        this.name = name;
        this.owner = owner;
        this.advisors = ImmutableList.copyOf(advisors);
        argumentTypes = Type.getArgumentTypes(desc);
        returnType = Type.getReturnType(desc);
        for (Advice advice : advisors) {
            if (!advice.getPointcut().captureNested() || advice.getOnThrowAdvice() != null
                    || advice.getOnAfterAdvice() != null) {
                needsTryCatch = true;
                break;
            }
        }
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
        assertNonNull(methodStartLabel, "Call to onMethodEnter() is required");
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
                    super.visitLocalVariable("informant$adviceFlowHolder$" + i,
                            adviceFlowHolderType.getDescriptor(), null, methodStartLabel,
                            outerEndLabel, adviceFlowHolderLocalIndex);
                }
                Integer adviceFlowLocalIndex = adviceFlowLocals.get(advice);
                if (adviceFlowLocalIndex != null) {
                    super.visitLocalVariable("informant$adviceFlow" + i,
                            Type.BOOLEAN_TYPE.getDescriptor(), null, methodStartLabel,
                            outerEndLabel, adviceFlowLocalIndex);
                }
                Integer enabledLocalIndex = enabledLocals.get(advice);
                if (enabledLocalIndex != null) {
                    super.visitLocalVariable("informant$enabled$" + i,
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
                        super.visitLocalVariable("informant$traveler$" + i,
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
            assertNonNull(catchStartLabel, "Call to onMethodEnter() is required");
            Label catchEndLabel = newLabel();
            Label catchHandlerLabel2 = newLabel();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchEndLabel,
                    "io/informant/weaving/WeavingMethodVisitor$MarkerException");
            visitLabel(catchEndLabel);
            invokeVirtual(MarkerException.TYPE, MarkerException.GET_CAUSE_METHOD);
            throwException();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchHandlerLabel2,
                    "java/lang/Throwable");
            visitLabel(catchHandlerLabel2);
            visitOnThrowAdvice();
            visitOnAfterAdvice();
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
                visitOnReturnAdvice(opcode);
                visitOnAfterAdvice();
                resetAdviceFlowIfNecessary();
                goTo(continueLabel);

                visitTryCatchBlock(innerCatchStartLabel, innerCatchEndLabel, innerCatchEndLabel,
                        "java/lang/Throwable");
                visitLabel(innerCatchEndLabel);
                invokeStatic(MarkerException.TYPE, MarkerException.STATIC_FACTORY_METHOD);
                throwException();

                visitLabel(continueLabel);
            } else {
                visitOnReturnAdvice(opcode);
                visitOnAfterAdvice();
                resetAdviceFlowIfNecessary();
            }
        }
    }

    private void defineAndEvaluateEnabledLocalVar(Advice advice) {
        Integer enabledLocal = null;
        Method isEnabledAdvice = advice.getIsEnabledAdvice();
        if (isEnabledAdvice != null) {
            loadMethodArgs(advice.getIsEnabledParameterKinds(), 0, -1, advice.getAdviceType(),
                    IsEnabled.class);
            invokeStatic(advice.getAdviceType(), isEnabledAdvice);
            enabledLocal = newLocal(Type.BOOLEAN_TYPE);
            enabledLocals.put(advice, enabledLocal);
            storeLocal(enabledLocal);
        }
        if (!advice.getPointcut().captureNested()) {
            // topFlowLocal must be defined/initialized outside of any code branches since it is
            // referenced later on in resetAdviceFlowIfNecessary()
            int adviceFlowHolderLocal = newLocal(adviceFlowHolderType);
            adviceFlowHolderLocals.put(advice, adviceFlowHolderLocal);
            visitInsn(ACONST_NULL);
            storeLocal(adviceFlowHolderLocal);

            int adviceFlowLocal = newLocal(Type.BOOLEAN_TYPE);
            adviceFlowLocals.put(advice, adviceFlowLocal);
            push(false);
            storeLocal(adviceFlowLocal);

            Label setAdviceFlowBlockEnd = newLabel();
            if (enabledLocal != null) {
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
            } else {
                enabledLocal = newLocal(Type.BOOLEAN_TYPE);
                enabledLocals.put(advice, enabledLocal);
                // it will be initialized below
            }
            getStatic(Type.getType(advice.getGeneratedAdviceFlowClass()), "adviceFlow",
                    adviceFlowOuterHolderType);
            invokeVirtual(adviceFlowOuterHolderType, Method.getMethod(
                    AdviceFlowHolder.class.getName() + " getInnerHolder()"));
            // and dup one more time for the subsequent conditional
            dup();
            // store the boolean into both informant$topFlow$i which stores the prior state and is
            // needed at method exit to reset the static thread local adviceFlow appropriately
            storeLocal(adviceFlowHolderLocal);
            invokeVirtual(adviceFlowHolderType, Method.getMethod("boolean isTop()"));
            Label isTopBlockStart = newLabel();
            dup();
            storeLocal(adviceFlowLocal);
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
        loadMethodArgs(advice.getOnBeforeParameterKinds(), 0, -1, advice.getAdviceType(),
                OnBefore.class);
        invokeStatic(advice.getAdviceType(), onBeforeAdvice);
        if (travelerLocal != null) {
            storeLocal(travelerLocal);
        }
        if (onBeforeBlockEnd != null) {
            visitLabel(onBeforeBlockEnd);
        }
    }

    private void visitOnReturnAdvice(int opcode) {
        for (Advice advice : Lists.reverse(advisors)) {
            Method onReturnAdvice = advice.getOnReturnAdvice();
            if (onReturnAdvice == null) {
                continue;
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
    }

    private void weaveOnReturnAdvice(int opcode, Advice advice, Method onReturnAdvice) {
        if (onReturnAdvice.getArgumentTypes().length > 0) {
            int startIndex = 0;
            if (advice.getOnReturnParameterKinds().get(0) == ParameterKind.RETURN) {
                // @BindReturn must be the first argument to @OnReturn (if present)
                if (opcode == RETURN) {
                    logger.warn("cannot use @BindReturn on a @Pointcut returning void");
                    visitInsn(ACONST_NULL);
                } else {
                    loadReturnValue(opcode, false);
                }
                startIndex = 1;
            }
            if (advice.getOnReturnParameterKinds().get(0) == ParameterKind.OPTIONAL_RETURN) {
                // @BindOptionalReturn must be the first argument to @OnReturn (if present)
                if (opcode == RETURN) {
                    // void
                    mv.visitMethodInsn(INVOKESTATIC, "io/informant/api/internal/VoidReturn",
                            "getInstance", "()Lio/informant/api/OptionalReturn;");
                } else {
                    loadReturnValue(opcode, false);
                    mv.visitMethodInsn(INVOKESTATIC, "io/informant/api/internal/NonVoidReturn",
                            "create", "(Ljava/lang/Object;)Lio/informant/api/OptionalReturn;");
                }
                startIndex = 1;
            }
            if (advice.getOnReturnParameterKinds().get(0) == ParameterKind.PRIMITIVE_RETURN) {
                // @BindReturn must be the first argument to @OnReturn (if present)
                if (opcode == RETURN) {
                    logger.warn("cannot use @BindReturn on a @Pointcut returning void");
                    switch (opcode) {
                        case IRETURN:
                            visitInsn(ICONST_0);
                            break;
                        case LRETURN:
                            visitInsn(LCONST_0);
                            break;
                        case FRETURN:
                            visitInsn(FCONST_0);
                            break;
                        case DRETURN:
                            visitInsn(DCONST_0);
                            break;
                        default:
                            logger.error("no fallback for unexpected opcode: {}", opcode);
                    }
                } else {
                    loadReturnValue(opcode, true);
                }
                startIndex = 1;
            }
            loadMethodArgs(advice.getOnReturnParameterKinds(), startIndex,
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

    private void loadReturnValue(int opcode, boolean primitive) {
        if (opcode == ARETURN || opcode == ATHROW) {
            dup();
        } else {
            if (opcode == LRETURN || opcode == DRETURN) {
                dup2();
            } else {
                dup();
            }
            if (!primitive) {
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
                if (advice.getOnThrowParameterKinds().get(0) == ParameterKind.THROWABLE) {
                    // @BindThrowable must be the first argument to @OnThrow (if present)
                    dup();
                    startIndex++;
                }
                loadMethodArgs(advice.getOnThrowParameterKinds(), startIndex,
                        travelerLocals.get(advice), advice.getAdviceType(), OnThrow.class);
                invokeStatic(advice.getAdviceType(), onThrowAdvice);
            }
            if (onThrowBlockEnd != null) {
                visitLabel(onThrowBlockEnd);
            }
        }
    }

    private void visitOnAfterAdvice() {
        for (Advice advice : Lists.reverse(advisors)) {
            Method onAfterAdvice = advice.getOnAfterAdvice();
            if (onAfterAdvice == null) {
                continue;
            }
            Integer enabledLocal = enabledLocals.get(advice);
            Label onAfterBlockEnd = null;
            if (enabledLocal != null) {
                onAfterBlockEnd = newLabel();
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, onAfterBlockEnd);
            }
            loadMethodArgs(advice.getOnAfterParameterKinds(), 0, travelerLocals.get(advice),
                    advice.getAdviceType(), OnAfter.class);
            invokeStatic(advice.getAdviceType(), onAfterAdvice);
            if (onAfterBlockEnd != null) {
                visitLabel(onAfterBlockEnd);
            }
        }
    }

    private void resetAdviceFlowIfNecessary() {
        for (Advice advice : advisors) {
            if (!advice.getPointcut().captureNested()) {
                Label setAdviceFlowBlockEnd = newLabel();
                loadLocal(enabledLocals.get(advice));
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
                loadLocal(adviceFlowLocals.get(advice));
                visitJumpInsn(IFEQ, setAdviceFlowBlockEnd);
                // isTop was true at the beginning of the advice, need to reset it now
                loadLocal(adviceFlowHolderLocals.get(advice));
                push(true);
                invokeVirtual(adviceFlowHolderType, Method.getMethod("void setTop(boolean)"));
                visitLabel(setAdviceFlowBlockEnd);
            }
        }
    }

    private void loadMethodArgs(@ReadOnly List<ParameterKind> parameterKinds, int startIndex,
            @Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType) {

        int argIndex = 0;
        for (int i = startIndex; i < parameterKinds.size(); i++) {
            ParameterKind parameterType = parameterKinds.get(i);
            if (parameterType == ParameterKind.TARGET) {
                loadTarget();
            } else if (parameterType == ParameterKind.METHOD_ARG) {
                loadNonPrimitiveMethodArg(adviceType, annotationType, argIndex++);
            } else if (parameterType == ParameterKind.PRIMITIVE_METHOD_ARG) {
                // no autobox
                loadArg(argIndex++);
            } else if (parameterType == ParameterKind.METHOD_ARG_ARRAY) {
                loadArgArray();
            } else if (parameterType == ParameterKind.METHOD_NAME) {
                loadMethodName();
            } else if (parameterType == ParameterKind.TRAVELER) {
                loadTraveler(travelerLocal, adviceType, annotationType);
            } else {
                // TODO better warning message
                logger.warn("unexpected parameter type {} at index {}", parameterType, i);
            }
        }
    }

    private void loadTarget() {
        if ((access & ACC_STATIC) == 0) {
            loadThis();
        } else {
            // cannot use push(Type) since .class constants are not supported in classes
            // that were compiled to jdk 1.4
            push(owner.getClassName());
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
        }
    }

    private void loadNonPrimitiveMethodArg(Type adviceType,
            Class<? extends Annotation> annotationType, int argIndex) {
        if (argIndex >= argumentTypes.length) {
            logger.warn("the @" + annotationType.getSimpleName() + " method in "
                    + adviceType.getClassName() + " has more @"
                    + BindMethodArg.class.getSimpleName() + " arguments than the number of args"
                    + " in the target method");
            visitInsn(ACONST_NULL);
            return;
        }
        loadArg(argIndex);
        // autobox
        box(argumentTypes[argIndex]);
    }

    private void loadMethodName() {
        if (name.contains("$informant$metric$")) {
            // strip off internal metric identifier from method name
            push(name.substring(0, name.indexOf("$informant$metric$")));
        } else {
            push(name);
        }
    }

    private void loadTraveler(@Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType) {
        if (travelerLocal == null) {
            logger.error("the @" + annotationType.getSimpleName() + " method in "
                    + adviceType.getClassName() + " requested @"
                    + BindTraveler.class.getSimpleName() + " but @"
                    + OnBefore.class.getSimpleName() + " returns void");
            // try to pass null, but this will fail anyways if @BindTraveler is primitive arg
            // TODO handle primitive args, then reduce logger from error to warn
            visitInsn(ACONST_NULL);
        } else {
            loadLocal(travelerLocal);
        }
    }

    private void pushDefault(Type travelerType) {
        switch (travelerType.getSort()) {
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
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("access", access)
                .add("name", name)
                .add("owner", owner)
                .add("advisors", advisors)
                .add("argumentTypes", argumentTypes)
                .add("topFlowLocals", adviceFlowHolderLocals)
                .add("enabledLocals", enabledLocals)
                .add("travelerLocals", travelerLocals)
                .add("needsTryCatch", needsTryCatch)
                .add("outerCatchStartLabel", catchStartLabel)
                .add("outerStartLabel", methodStartLabel)
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
                STATIC_FACTORY_METHOD = Method.getMethod(MarkerException.class.getDeclaredMethod(
                        "from", Throwable.class));
                GET_CAUSE_METHOD = Method.getMethod(Throwable.class.getMethod("getCause"));
            } catch (SecurityException e) {
                throw new IllegalStateException("Unrecoverable error", e);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Unrecoverable error", e);
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

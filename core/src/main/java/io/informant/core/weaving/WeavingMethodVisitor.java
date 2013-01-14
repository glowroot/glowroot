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
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.core.weaving.Advice.ParameterKind;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingMethodVisitor extends AdviceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WeavingMethodVisitor.class);

    private static final Type adviceFlowType = Type.getType(AdviceFlowThreadLocal.class);

    private final int access;
    private final String name;
    private final Type owner;
    private final List<Advice> advisors;
    private final Map<Advice, Integer> adviceFlowThreadLocalNums;
    private final Type[] argumentTypes;

    private final Map<Advice, Integer> topFlowLocals = Maps.newHashMap();
    private final Map<Advice, Integer> enabledLocals = Maps.newHashMap();
    private final Map<Advice, Integer> travelerLocals = Maps.newHashMap();

    private boolean needsTryCatch;
    @Nullable
    private Label catchStartLabel;
    @Nullable
    private Label outerStartLabel;

    protected WeavingMethodVisitor(MethodVisitor mv, int access, String name, String desc,
            Type owner, List<Advice> advisors, Map<Advice, Integer> adviceFlowThreadLocalNums) {

        super(Opcodes.ASM4, mv, access, name, desc);
        this.access = access;
        this.name = name;
        this.owner = owner;
        this.advisors = advisors;
        this.adviceFlowThreadLocalNums = adviceFlowThreadLocalNums;
        argumentTypes = Type.getArgumentTypes(desc);
        for (Advice advice : advisors) {
            if (!advice.getPointcut().captureNested() || advice.getOnThrowAdvice() != null
                    || advice.getOnAfterAdvice() != null) {
                needsTryCatch = true;
                break;
            }
        }
    }

    @Override
    public void visitLocalVariable(String name, String desc, @Nullable String signature,
            Label start, Label end, int index) {

        if (name.equals("this")) {
            // this is only so that eclipse debugger will not display <unknown receiving type>
            // inside code when inside of code before the previous method start label
            // (the debugger asks for "this", which is not otherwise available in the new code
            // inserted at the beginning of the method)
            //
            // ClassReader always visits local variables at the very end (just prior to visitMaxs)
            // so this is a good place to put the outer end label for the local variable 'this'
            Label outerEndLabel = newLabel();
            visitLabel(outerEndLabel);
            super.visitLocalVariable(name, desc, signature, outerStartLabel, outerEndLabel, index);
            // at the same time, may as well define local vars for enabled and traveler as
            // applicable
            for (int i = 0; i < advisors.size(); i++) {
                Advice advice = advisors.get(i);
                Integer topFlowLocalIndex = topFlowLocals.get(advice);
                if (topFlowLocalIndex != null) {
                    super.visitLocalVariable("informant$topFlow$" + i,
                            Type.BOOLEAN_TYPE.getDescriptor(), null, outerStartLabel,
                            outerEndLabel, topFlowLocalIndex);
                }
                Integer enabledLocalIndex = enabledLocals.get(advice);
                if (enabledLocalIndex != null) {
                    super.visitLocalVariable("informant$enabled$" + i,
                            Type.BOOLEAN_TYPE.getDescriptor(), null, outerStartLabel,
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
                                travelerType.getDescriptor(), null, outerStartLabel, outerEndLabel,
                                travelerLocalIndex);
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
            Label catchEndLabel = newLabel();
            Label catchHandlerLabel2 = newLabel();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchEndLabel,
                    "io/informant/core/weaving/WeavingMethodVisitor$MarkerException");
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
    protected void onMethodEnter() {
        outerStartLabel = newLabel();
        visitLabel(outerStartLabel);
        // enabled and traveler locals must be defined outside of the try block so they will be
        // accessible in the catch block
        for (int i = 0; i < advisors.size(); i++) {
            Advice advice = advisors.get(i);
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
    protected void onMethodExit(int opcode) {
        // instructions to catch throws will be written (if necessary) in visitMaxs
        if (opcode != ATHROW) {
            if (needsTryCatch) {
                // if an exception occurs inside @OnReturn or @OnAfter advice, it will be caught by
                // the overall try/catch and it will trigger @OnThrow and @OnAfter
                // to prevent this, any exception that occurs inside @OnReturn or @OnAfter is caught
                // and wrapped in a marker exception, and then unwrapped and re-thrown inside
                // the overall try/catch block
                Label catchStartLabel = newLabel();
                Label continueLabel = newLabel();
                Label catchEndLabel = newLabel();

                visitLabel(catchStartLabel);
                visitOnReturnAdvice(opcode);
                visitOnAfterAdvice();
                resetAdviceFlowIfNecessary();
                goTo(continueLabel);

                visitTryCatchBlock(catchStartLabel, catchEndLabel, catchEndLabel,
                        "java/lang/Throwable");
                visitLabel(catchEndLabel);
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
        if (!advice.getPointcut().captureNested()) {
            // this index is based on the list of advisors that match the class which could be a
            // larger set than the list of advisors that match this method
            int i = adviceFlowThreadLocalNums.get(advice);
            getStatic(owner, "informant$adviceFlow$" + i, adviceFlowType);
            invokeVirtual(adviceFlowType, Method.getMethod("boolean isTop()"));
            // store the boolean into both informant$topFlow$i and informant$enabled$i
            // informant$topFlow$i stores the prior state and is needed at method exit to reset
            // the static thread local informant$adviceFlow$i appropriately
            dup();
            // and dup one more time for the subsequent conditional
            dup();
            int topFlowLocal = newLocal(Type.BOOLEAN_TYPE);
            topFlowLocals.put(advice, topFlowLocal);
            storeLocal(topFlowLocal);
            enabledLocal = newLocal(Type.BOOLEAN_TYPE);
            enabledLocals.put(advice, enabledLocal);
            storeLocal(enabledLocal);
            Label setAdviceFlowBlockEnd = newLabel();
            visitJumpInsn(Opcodes.IFEQ, setAdviceFlowBlockEnd);
            getStatic(owner, "informant$adviceFlow$" + i, adviceFlowType);
            push(false);
            invokeVirtual(adviceFlowType, Method.getMethod("void setTop(boolean)"));
            visitLabel(setAdviceFlowBlockEnd);
        }
        Method isEnabledAdvice = advice.getIsEnabledAdvice();
        if (isEnabledAdvice != null) {
            Label enabledAdviceBlockEnd = null;
            if (enabledLocal != null) {
                enabledAdviceBlockEnd = newLabel();
                loadLocal(enabledLocal);
                visitJumpInsn(Opcodes.IFEQ, enabledAdviceBlockEnd);
            }
            loadMethodArgs(advice.getIsEnabledParameterKinds(), 0, -1, advice.getAdviceType(),
                    IsEnabled.class);
            invokeStatic(advice.getAdviceType(), isEnabledAdvice);
            if (enabledLocal == null) {
                enabledLocal = newLocal(Type.BOOLEAN_TYPE);
                enabledLocals.put(advice, enabledLocal);
            }
            storeLocal(enabledLocal);
            if (enabledAdviceBlockEnd != null) {
                visitLabel(enabledAdviceBlockEnd);
            }
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
        // have to initialize it with a value, otherwise it won't be defined in the
        // outer scope
        // at this time only nullable types are supported (Type.ARRAY, Type.OBJECT,
        // Type.METHOD) for traveler types, so initialize it with null
        // (see validation for supported traveler types in Advice)
        if (travelerType.getSort() >= Type.ARRAY) {
            int travelerLocal = newLocal(travelerType);
            visitInsn(Opcodes.ACONST_NULL);
            storeLocal(travelerLocal);
            travelerLocals.put(advice, travelerLocal);
        } else {
            logger.error("primitive types are not supported (yet) as traveler types");
            return;
        }
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
            visitJumpInsn(Opcodes.IFEQ, onBeforeBlockEnd);
        }
        loadMethodArgs(advice.getOnBeforeParameterKinds(), 0, -1, advice.getAdviceType(),
                OnBefore.class);
        invokeStatic(advice.getAdviceType(), onBeforeAdvice);
        if (travelerLocal != null) {
            storeLocal(travelerLocal);
        }
        if (enabledLocal != null) {
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
                visitJumpInsn(Opcodes.IFEQ, onReturnBlockEnd);
            }
            int sort = onReturnAdvice.getReturnType().getSort();
            if (onReturnAdvice.getArgumentTypes().length == 0) {
                if (sort == Type.LONG || sort == Type.DOUBLE) {
                    pop2();
                } else if (sort != Type.VOID) {
                    pop();
                }
                invokeStatic(advice.getAdviceType(), onReturnAdvice);
            } else {
                int startIndex = 0;
                if (advice.getOnReturnParameterKinds()[0] == ParameterKind.RETURN) {
                    // @InjectReturn must be the first argument to @OnReturn (if present)
                    if (opcode == RETURN) {
                        logger.error("cannot @InjectReturn on a @Pointcut returning void");
                        // try to pass null (TODO handle primitive types also)
                        visitInsn(ACONST_NULL);
                    } else if (opcode == ARETURN || opcode == ATHROW) {
                        dup();
                    } else {
                        if (opcode == LRETURN || opcode == DRETURN) {
                            dup2();
                        } else {
                            dup();
                        }
                    }
                    startIndex++;
                }
                loadMethodArgs(advice.getOnReturnParameterKinds(), startIndex,
                        travelerLocals.get(advice), advice.getAdviceType(), OnReturn.class);
                if (sort == Type.LONG || sort == Type.DOUBLE) {
                    pop2();
                } else if (sort != Type.VOID) {
                    pop();
                }
                invokeStatic(advice.getAdviceType(), onReturnAdvice);
            }
            if (enabledLocal != null) {
                visitLabel(onReturnBlockEnd);
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
                visitJumpInsn(Opcodes.IFEQ, onThrowBlockEnd);
            }
            if (onThrowAdvice.getArgumentTypes().length == 0) {
                invokeStatic(advice.getAdviceType(), onThrowAdvice);
            } else {
                int startIndex = 0;
                if (advice.getOnThrowParameterKinds()[0] == ParameterKind.THROWABLE) {
                    // @InjectThrowable must be the first argument to @OnThrow (if present)
                    dup();
                    startIndex++;
                }
                loadMethodArgs(advice.getOnThrowParameterKinds(), startIndex,
                        travelerLocals.get(advice), advice.getAdviceType(), OnThrow.class);
                invokeStatic(advice.getAdviceType(), onThrowAdvice);
            }
            if (enabledLocal != null) {
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
                visitJumpInsn(Opcodes.IFEQ, onAfterBlockEnd);
            }
            loadMethodArgs(advice.getOnAfterParameterKinds(), 0, travelerLocals.get(advice),
                    advice.getAdviceType(), OnAfter.class);
            invokeStatic(advice.getAdviceType(), onAfterAdvice);
            if (enabledLocal != null) {
                visitLabel(onAfterBlockEnd);
            }
        }
    }

    private void resetAdviceFlowIfNecessary() {
        for (int i = 0; i < advisors.size(); i++) {
            Advice advice = advisors.get(i);
            if (!advice.getPointcut().captureNested()) {
                Label setAdviceFlowBlockEnd = newLabel();
                loadLocal(topFlowLocals.get(advice));
                visitJumpInsn(Opcodes.IFEQ, setAdviceFlowBlockEnd);
                int j = adviceFlowThreadLocalNums.get(advice);
                getStatic(owner, "informant$adviceFlow$" + j, adviceFlowType);
                push(true);
                invokeVirtual(adviceFlowType, Method.getMethod("void setTop(boolean)"));
                visitLabel(setAdviceFlowBlockEnd);
            }
        }
    }

    private void loadMethodArgs(ParameterKind[] parameterTypes, int startIndex,
            @Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType) {

        int argIndex = 0;
        for (int i = startIndex; i < parameterTypes.length; i++) {
            ParameterKind parameterType = parameterTypes[i];
            if (parameterType == ParameterKind.TARGET) {
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    loadThis();
                } else {
                    // cannot use push(Type) since .class constants are not supported in classes
                    // that were compiled to jdk 1.4
                    push(owner.getClassName());
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                            "(Ljava/lang/String;)Ljava/lang/Class;");
                }
            } else if (parameterType == ParameterKind.METHOD_ARG) {
                if (argIndex < argumentTypes.length) {
                    loadArg(argIndex);
                    // autobox
                    box(argumentTypes[argIndex++]);
                } else {
                    logger.error("the @" + annotationType.getSimpleName() + " method in "
                            + adviceType.getClassName() + " has more @"
                            + InjectMethodArg.class.getSimpleName() + " arguments than the number"
                            + " of args in the target method", new Throwable());
                }
            } else if (parameterType == ParameterKind.METHOD_ARG_ARRAY) {
                loadArgArray();
            } else if (parameterType == ParameterKind.PRIMITIVE_METHOD_ARG) {
                // no autobox
                loadArg(argIndex++);
            } else if (parameterType == ParameterKind.METHOD_NAME) {
                if (name.contains("$informant$metric$")) {
                    // strip off internal metric identifier from method name
                    push(name.substring(0, name.indexOf("$informant$metric$")));
                } else {
                    push(name);
                }
            } else if (parameterType == ParameterKind.TRAVELER) {
                if (travelerLocal == null) {
                    logger.error("the @" + annotationType.getSimpleName() + " method in "
                            + adviceType.getClassName() + " requested @"
                            + InjectTraveler.class.getSimpleName() + " but @"
                            + OnBefore.class.getSimpleName() + " returns void");
                    // try to pass null (TODO handle primitive types also)
                    visitInsn(ACONST_NULL);
                } else {
                    loadLocal(travelerLocal);
                }
            } else {
                logger.error("unexpected parameter type {} at index {}", parameterType, i);
            }
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("access", access)
                .add("name", name)
                .add("owner", owner)
                .add("advisors", advisors)
                .add("adviceFlowThreadLocalNums", adviceFlowThreadLocalNums)
                .add("argumentTypes", argumentTypes)
                .add("topFlowLocals", topFlowLocals)
                .add("enabledLocals", enabledLocals)
                .add("travelerLocals", travelerLocals)
                .add("needsTryCatch", needsTryCatch)
                .add("catchStartLabel", catchStartLabel)
                .add("outerStartLabel", outerStartLabel)
                .toString();
    }

    // this is used to wrap exceptions that occur inside of @OnReturn and @OnAfter when the
    // exception would otherwise be caught by the overall try/catch triggering @OnThrow and @OnAfter
    //
    // needs to be public since it is accessed from bytecode injected into other packages
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
        // needs to be public since it is accessed from bytecode injected into other packages
        public static MarkerException from(Throwable cause) {
            return new MarkerException(cause);
        }
        private MarkerException(Throwable cause) {
            super(cause);
        }
    }
}

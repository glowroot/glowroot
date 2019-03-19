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

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.Bytecode;
import org.glowroot.agent.bytecode.api.ThreadContextPlus;
import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.plugin.api.ParameterHolder;
import org.glowroot.agent.plugin.api.internal.ParameterHolderImpl;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.weaving.Advice.AdviceParameter;
import org.glowroot.agent.weaving.Advice.ParameterKind;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

class WeavingMethodVisitor extends AdviceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WeavingMethodVisitor.class);

    private static final Type objectType = Type.getType(Object.class);

    private static final Type fastThreadContextThreadLocalHolderType =
            Type.getType(ThreadContextThreadLocal.Holder.class);
    private static final Type threadContextPlusType = Type.getType(ThreadContextPlus.class);

    private static final Type bytecodeType = Type.getType(Bytecode.class);

    private static final Type parameterHolderType = Type.getType(ParameterHolder.class);
    private static final Type parameterHolderImplType = Type.getType(ParameterHolderImpl.class);

    private static final Method parameterHolderImplCreateMethod = new Method("create",
            "(Ljava/lang/Object;)Lorg/glowroot/agent/plugin/api/internal/ParameterHolderImpl;");

    private static final Method parameterHolderImplGetMethod =
            new Method("get", "()Ljava/lang/Object;");

    // starts at 1 since 0 is used for "no nesting group"
    private static final AtomicInteger nestingGroupIdCounter = new AtomicInteger(1);
    // starts at 1 since 0 is used for "no suppression key"
    private static final AtomicInteger suppressionKeyIdCounter = new AtomicInteger(1);

    private static final ConcurrentMap<String, Integer> nestingGroupIds =
            new ConcurrentHashMap<String, Integer>();
    private static final ConcurrentMap<String, Integer> suppressionKeyIds =
            new ConcurrentHashMap<String, Integer>();

    private final boolean frames;
    private final int access;
    private final String name;
    private final Type owner;
    private final ImmutableList<Advice> advisors;
    private final Type[] argumentTypes;
    private final Type returnType;
    private final @Nullable String metaHolderInternalName;
    private final @Nullable Integer methodMetaGroupUniqueNum;
    private final boolean bootstrapClassLoader;
    private final boolean needsOnReturn;
    private final boolean needsOnThrow;
    private final Object[] implicitFrameLocals;

    private final Map<Advice, Integer> enabledLocals = Maps.newHashMap();
    private final Map<Advice, Integer> travelerLocals = Maps.newHashMap();
    private final Map<Advice, Integer> prevNestingGroupIdLocals = Maps.newHashMap();
    private final Map<Advice, Integer> prevSuppressionKeyIdLocals = Maps.newHashMap();

    private final Map<Advice, Map<Integer, Integer>> parameterHolderLocals = Maps.newHashMap();

    // don't need map of thread context locals since all advice can share the same
    // threadContextLocal
    private @MonotonicNonNull Integer threadContextLocal;
    private @MonotonicNonNull Integer threadContextHolderLocal;

    private final List<CatchHandler> catchHandlers = Lists.newArrayList();

    private @MonotonicNonNull Integer returnOpcode;

    private @MonotonicNonNull Label methodStartLabel;
    private @MonotonicNonNull Label onReturnLabel;
    private @MonotonicNonNull Label catchStartLabel;
    private boolean visitedLocalVariableThis;

    private int[] savedArgLocals = new int[0];

    WeavingMethodVisitor(MethodVisitor mv, boolean frames, int access, String name,
            String descriptor, Type owner, List<Advice> advisors,
            @Nullable String metaHolderInternalName, @Nullable Integer methodMetaGroupUniqueNum,
            boolean bootstrapClassLoader) {
        super(ASM7, new FrameDeduppingMethodVisitor(mv), access, name, descriptor);
        this.frames = frames;
        this.access = access;
        this.name = name;
        this.owner = owner;
        this.advisors = ImmutableList.copyOf(advisors);
        argumentTypes = Type.getArgumentTypes(descriptor);
        returnType = Type.getReturnType(descriptor);
        this.metaHolderInternalName = metaHolderInternalName;
        this.methodMetaGroupUniqueNum = methodMetaGroupUniqueNum;
        this.bootstrapClassLoader = bootstrapClassLoader;
        boolean needsOnReturn = false;
        boolean needsOnThrow = false;
        for (Advice advice : advisors) {
            if (!advice.pointcut().nestingGroup().isEmpty()
                    || !advice.pointcut().suppressionKey().isEmpty()
                    || advice.onAfterAdvice() != null) {
                needsOnReturn = true;
                needsOnThrow = true;
                break;
            }
            if (advice.onReturnAdvice() != null) {
                needsOnReturn = true;
            }
            if (advice.onThrowAdvice() != null) {
                needsOnThrow = true;
            }
        }
        this.needsOnReturn = needsOnReturn;
        this.needsOnThrow = needsOnThrow;

        int nImplicitFrameLocals = argumentTypes.length;
        boolean needsReceiver = !Modifier.isStatic(access);
        if (needsReceiver) {
            nImplicitFrameLocals++;
        }
        Object[] implicitFrameLocals = new Object[nImplicitFrameLocals];
        int i = 0;
        if (needsReceiver) {
            if (name.equals("<init>")) {
                // need explicit import due to same static variable name in super class
                implicitFrameLocals[i++] = Opcodes.UNINITIALIZED_THIS;
            } else {
                implicitFrameLocals[i++] = owner.getInternalName();
            }
        }
        for (Type argumentType : argumentTypes) {
            implicitFrameLocals[i++] = convert(argumentType);
        }
        this.implicitFrameLocals = implicitFrameLocals;
    }

    @Override
    protected void onMethodPreEnter() {
        stackFrameTracking = false;
        try {
            onMethodPreEnterInternal();
        } finally {
            stackFrameTracking = true;
        }
    }

    @Override
    protected void onMethodEnter() {
        stackFrameTracking = false;
        try {
            if (name.equals("<init>")) {
                implicitFrameLocals[0] = owner.getInternalName();
            }
            onMethodEnterInternal();
        } finally {
            stackFrameTracking = true;
        }
    }

    @Override
    public void visitInsn(int opcode) {
        if (needsOnReturn && isReturnOpcode(opcode)) {
            // ATHROW not included, instructions to catch throws will be written (if necessary) in
            // visitMaxs
            checkNotNull(onReturnLabel, "Call to onMethodEnter() is required");
            returnOpcode = opcode;
            stackFrameTracking = false;
            try {
                cleanUpStackIfNeeded(opcode);
            } finally {
                stackFrameTracking = true;
            }
            visitJumpInsn(GOTO, onReturnLabel);
        } else {
            super.visitInsn(opcode);
        }
    }

    @Override
    public void visitLocalVariable(String name, String descriptor, @Nullable String signature,
            Label start, Label end, int index) {
        // the JSRInlinerAdapter writes the local variable "this" across different label ranges
        // so visitedLocalVariableThis is checked and updated to ensure this block is only executed
        // once per method
        //
        if (!name.equals("this") || visitedLocalVariableThis) {
            super.visitLocalVariable(name, descriptor, signature, start, end, index);
            return;
        }
        visitedLocalVariableThis = true;
        checkNotNull(methodStartLabel, "Call to onMethodEnter() is required");
        // this is only so that eclipse debugger will not display <unknown receiving type>
        // inside code when inside of code before the previous method start label
        // (the debugger asks for "this", which is not otherwise available in the new code
        // inserted at the beginning of the method)
        //
        // ClassReader always visits local variables at the very end (just prior to visitMaxs)
        // so this is a good place to put the outer end label for the local variable 'this'
        Label outerEndLabel = new Label();
        visitLabel(outerEndLabel);
        super.visitLocalVariable(name, descriptor, signature, methodStartLabel, outerEndLabel,
                index);
        // at the same time, may as well define local vars for enabled and traveler as
        // applicable
        for (int i = 0; i < advisors.size(); i++) {
            Advice advice = advisors.get(i);
            Integer enabledLocalIndex = enabledLocals.get(advice);
            if (enabledLocalIndex != null) {
                super.visitLocalVariable("glowroot$enabled$" + i, Type.BOOLEAN_TYPE.getDescriptor(),
                        null, methodStartLabel, outerEndLabel, enabledLocalIndex);
            }
            Integer travelerLocalIndex = travelerLocals.get(advice);
            if (travelerLocalIndex != null) {
                Type travelerType = advice.travelerType();
                if (travelerType == null) {
                    logger.error("visitLocalVariable(): traveler local index is not null,"
                            + " but traveler type is null");
                } else {
                    super.visitLocalVariable("glowroot$traveler$" + i, travelerType.getDescriptor(),
                            null, methodStartLabel, outerEndLabel, travelerLocalIndex);
                }
            }
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        stackFrameTracking = false;
        // catch end should not precede @OnReturn and @OnAfter
        Label catchEndLabel = new Label();
        if (needsOnThrow) {
            visitLabel(catchEndLabel);
        }
        // returnOpCode can be null if only ATHROW in method in which case method doesn't need
        // onReturn advice
        if (needsOnReturn && returnOpcode != null) {
            checkNotNull(onReturnLabel, "Call to onMethodEnter() is required");
            visitLabel(onReturnLabel);
            if (returnType.getSort() == Type.VOID) {
                visitImplicitFrame();
            } else {
                visitImplicitFrame(convert(returnType));
            }
            for (Advice advice : Lists.reverse(advisors)) {
                visitOnReturnAdvice(advice, returnOpcode);
                visitOnAfterAdvice(advice, false);
            }
            resetCurrentNestingGroupIfNeeded(false);
            // need to call super.visitInsn() in order to avoid infinite loop
            // could call mv.visitInsn(), but that would bypass special constructor handling in
            // AdviceAdapter.visitInsn()
            super.visitInsn(returnOpcode);
        }
        if (needsOnThrow) {
            visitCatchHandlers(catchEndLabel);
        }
        super.visitMaxs(maxStack, maxLocals);
    }

    private void onMethodPreEnterInternal() {
        methodStartLabel = new Label();
        visitLabel(methodStartLabel);
        // enabled and traveler locals must be defined outside of the try block so they will be
        // accessible in the catch block
        for (Advice advice : advisors) {
            defineLocalVars(advice);
            defineTravelerLocalVar(advice);
            defineParameterHolderVars(advice);
        }
        if (name.equals("<init>")) {
            // this is to solve a super special case with advice on constructors that have local
            // variables defined before calling super (which is only possible in bytecode, e.g.
            // jacoco does this)
            // see WeaverTest.shouldExecuteSingleJumpAdviceOnHackedConstructorBytecode()
            for (Advice advice : advisors) {
                evaluateLocalVars(advice);
                invokeOnBefore(advice, travelerLocals.get(advice));
            }
        }
        saveArgsForMethodExit();
    }

    private void onMethodEnterInternal() {
        for (int i = 0; i < advisors.size(); i++) {
            Advice advice = advisors.get(i);
            if (!name.equals("<init>")) {
                // see comment above in onMethodPreEnterInternal() why this is skipped for <init>
                evaluateLocalVars(advice);
                invokeOnBefore(advice, travelerLocals.get(advice));
            }
            if (advice.onAfterAdvice() != null || advice.onThrowAdvice() != null) {
                Label catchStartLabel = new Label();
                visitLabel(catchStartLabel);
                catchHandlers
                        .add(ImmutableCatchHandler.of(catchStartLabel, advisors.subList(0, i + 1)));
            }
        }
        if (needsOnReturn) {
            onReturnLabel = new Label();
        }
        if (needsOnThrow && catchHandlers.isEmpty()) {
            // need catch for resetting thread locals
            catchStartLabel = new Label();
            visitLabel(catchStartLabel);
        }
    }

    private void visitCatchHandlers(Label catchEndLabel) {
        if (catchHandlers.isEmpty()) {
            checkNotNull(catchStartLabel, "Call to onMethodEnter() is required");
            Label catchHandlerLabel = new Label();
            visitTryCatchBlock(catchStartLabel, catchEndLabel, catchHandlerLabel,
                    "java/lang/Throwable");
            visitLabel(catchHandlerLabel);
            visitImplicitFrame("java/lang/Throwable");
            resetCurrentNestingGroupIfNeeded(true);
            visitInsn(ATHROW);
        } else {
            for (CatchHandler catchHandler : Lists.reverse(catchHandlers)) {
                Label catchHandlerLabel = new Label();
                visitTryCatchBlock(catchHandler.catchStartLabel(), catchEndLabel, catchHandlerLabel,
                        "java/lang/Throwable");
                visitLabel(catchHandlerLabel);
                visitImplicitFrame("java/lang/Throwable");
                for (Advice advice : Lists.reverse(catchHandler.advisors())) {
                    visitOnThrowAdvice(advice);
                }
                for (Advice advice : Lists.reverse(catchHandler.advisors())) {
                    visitOnAfterAdvice(advice, true);
                }
                resetCurrentNestingGroupIfNeeded(true);
                visitInsn(ATHROW);
            }
        }
    }

    private void defineLocalVars(Advice advice) {
        Integer enabledLocal = null;
        Method isEnabledAdvice = advice.isEnabledAdvice();
        String nestingGroup = advice.pointcut().nestingGroup();
        String suppressibleUsingKey = advice.pointcut().suppressibleUsingKey();
        if (isEnabledAdvice != null
                || hasOtherEnabledFactors(advice, nestingGroup, suppressibleUsingKey)) {
            enabledLocal = newLocal(Type.BOOLEAN_TYPE);
            enabledLocals.put(advice, enabledLocal);
            // temporary initial value needed for Java 7 stack map frames
            visitInsn(ICONST_0);
            storeLocal(enabledLocal);
        }
        String suppressionKey = advice.pointcut().suppressionKey();
        boolean needThreadContextHolderLocal = !nestingGroup.isEmpty() || !suppressionKey.isEmpty()
                || !suppressibleUsingKey.isEmpty() || advice.hasBindThreadContext()
                || advice.hasBindOptionalThreadContext();
        if (needThreadContextHolderLocal && threadContextHolderLocal == null) {
            // need to define thread context local var outside of any branches,
            // but also don't want to load ThreadContext if enabledLocal exists and is false
            threadContextHolderLocal = newLocal(fastThreadContextThreadLocalHolderType);
            visitInsn(ACONST_NULL);
            storeLocal(threadContextHolderLocal);
            threadContextLocal = newLocal(threadContextPlusType);
            visitInsn(ACONST_NULL);
            storeLocal(threadContextLocal);
        }
        Integer prevNestingGroupIdLocal = null;
        if (!nestingGroup.isEmpty()) {
            // need to define thread context local var outside of any branches
            // but also don't want to load ThreadContext if enabledLocal exists and is false
            prevNestingGroupIdLocal = newLocal(Type.INT_TYPE);
            prevNestingGroupIdLocals.put(advice, prevNestingGroupIdLocal);
            visitInsn(ICONST_M1);
            storeLocal(prevNestingGroupIdLocal);
        }
        Integer prevSuppressionKeyIdLocal = null;
        if (!suppressionKey.isEmpty()) {
            // need to define thread context local var outside of any branches
            // but also don't want to load ThreadContext if enabledLocal exists and is false
            prevSuppressionKeyIdLocal = newLocal(Type.INT_TYPE);
            prevSuppressionKeyIdLocals.put(advice, prevSuppressionKeyIdLocal);
            visitInsn(ICONST_M1);
            storeLocal(prevSuppressionKeyIdLocal);
        }
    }

    private void evaluateLocalVars(Advice advice) {
        Method isEnabledAdvice = advice.isEnabledAdvice();
        String nestingGroup = advice.pointcut().nestingGroup();
        String suppressionKey = advice.pointcut().suppressionKey();
        String suppressibleUsingKey = advice.pointcut().suppressibleUsingKey();
        Label otherEnabledFactorsDisabledEnd = null;
        if (hasOtherEnabledFactors(advice, nestingGroup, suppressibleUsingKey)) {
            otherEnabledFactorsDisabledEnd = new Label();
        }
        if (isEnabledAdvice != null) {
            loadMethodParameters(advice.isEnabledParameters(), 0, null, advice.adviceType(),
                    IsEnabled.class, false, null, nestingGroup, suppressionKey);
            visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                    isEnabledAdvice.getName(), isEnabledAdvice.getDescriptor(), false);
            if (otherEnabledFactorsDisabledEnd == null) {
                // guaranteed to be non-null via defineLocalVars() above
                int enabledLocal = checkNotNull(enabledLocals.get(advice));
                storeLocal(enabledLocal);
            } else {
                visitJumpInsn(IFEQ, otherEnabledFactorsDisabledEnd);
            }
        }
        if (otherEnabledFactorsDisabledEnd != null) {
            checkNotNull(threadContextHolderLocal);
            checkNotNull(threadContextLocal);
            loadMaybeNullThreadContext();
            if (advice.hasBindThreadContext() && !advice.hasBindOptionalThreadContext()) {
                // if thread context == null, then nothing to check or update, go to disabled
                visitJumpInsn(IFNULL, otherEnabledFactorsDisabledEnd);
                if (!nestingGroup.isEmpty()) {
                    int prevNestingGroupIdLocal =
                            checkNotNull(prevNestingGroupIdLocals.get(advice));
                    checkAndUpdateNestingGroupId(prevNestingGroupIdLocal, nestingGroup,
                            otherEnabledFactorsDisabledEnd);
                }
                if (!suppressibleUsingKey.isEmpty()) {
                    checkSuppressibleUsingKey(suppressibleUsingKey, otherEnabledFactorsDisabledEnd);
                }
                if (!suppressionKey.isEmpty()) {
                    int prevSuppressionKeyIdLocal =
                            checkNotNull(prevSuppressionKeyIdLocals.get(advice));
                    updateSuppressionKeyId(prevSuppressionKeyIdLocal, suppressionKey);
                }
            } else {
                Label label = new Label();
                // if thread context == null, then nothing to check or update, go to enabled
                visitJumpInsn(IFNULL, label);
                if (!nestingGroup.isEmpty()) {
                    int prevNestingGroupIdLocal =
                            checkNotNull(prevNestingGroupIdLocals.get(advice));
                    checkAndUpdateNestingGroupId(prevNestingGroupIdLocal, nestingGroup,
                            otherEnabledFactorsDisabledEnd);
                }
                if (!suppressibleUsingKey.isEmpty()) {
                    checkSuppressibleUsingKey(suppressibleUsingKey, otherEnabledFactorsDisabledEnd);
                }
                if (!suppressionKey.isEmpty()) {
                    int prevSuppressionKeyIdLocal =
                            checkNotNull(prevSuppressionKeyIdLocals.get(advice));
                    updateSuppressionKeyId(prevSuppressionKeyIdLocal, suppressionKey);
                }
                visitLabel(label);
                visitImplicitFrame();
            }
            visitInsn(ICONST_1);
            Label otherEnabledFactorsStoreLabel = new Label();
            goTo(otherEnabledFactorsStoreLabel);
            visitLabel(otherEnabledFactorsDisabledEnd);
            visitImplicitFrame();
            visitInsn(ICONST_0);
            visitLabel(otherEnabledFactorsStoreLabel);
            visitImplicitFrame(INTEGER);
            // guaranteed to be non-null via defineLocalVars() above
            int enabledLocal = checkNotNull(enabledLocals.get(advice));
            storeLocal(enabledLocal);
        } else if (!suppressionKey.isEmpty()) {
            checkNotNull(threadContextHolderLocal);
            checkNotNull(threadContextLocal);
            loadMaybeNullThreadContext();
            Label label = new Label();
            // if thread context == null, then nothing to update
            visitJumpInsn(IFNULL, label);
            int prevSuppressionKeyIdLocal = checkNotNull(prevSuppressionKeyIdLocals.get(advice));
            updateSuppressionKeyId(prevSuppressionKeyIdLocal, suppressionKey);
            visitLabel(label);
            visitImplicitFrame();
        }
    }

    @RequiresNonNull("threadContextLocal")
    private void checkAndUpdateNestingGroupId(int prevNestingGroupIdLocal, String nestingGroup,
            Label otherEnabledFactorsDisabledEnd) {
        loadLocal(threadContextLocal);
        visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                "getCurrentNestingGroupId", "()I", true);
        dup();
        storeLocal(prevNestingGroupIdLocal);
        int nestingGroupId = getNestingGroupId(nestingGroup);
        mv.visitLdcInsn(nestingGroupId);
        visitJumpInsn(IF_ICMPEQ, otherEnabledFactorsDisabledEnd);
        loadLocal(threadContextLocal);
        mv.visitLdcInsn(nestingGroupId);
        visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                "setCurrentNestingGroupId", "(I)V", true);
    }

    @RequiresNonNull("threadContextLocal")
    private void checkSuppressibleUsingKey(String suppressibleUsingKey,
            Label otherEnabledFactorsDisabledEnd) {
        loadLocal(threadContextLocal);
        visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                "getCurrentSuppressionKeyId", "()I", true);
        int suppressionKeyId = getSuppressionKeyId(suppressibleUsingKey);
        mv.visitLdcInsn(suppressionKeyId);
        visitJumpInsn(IF_ICMPEQ, otherEnabledFactorsDisabledEnd);
    }

    @RequiresNonNull("threadContextLocal")
    private void updateSuppressionKeyId(int prevSuppressionKeyIdLocal, String suppressionKey) {
        loadLocal(threadContextLocal);
        visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                "getCurrentSuppressionKeyId", "()I", true);
        storeLocal(prevSuppressionKeyIdLocal);
        int suppressionKeyId = getSuppressionKeyId(suppressionKey);
        loadLocal(threadContextLocal);
        mv.visitLdcInsn(suppressionKeyId);
        visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                "setCurrentSuppressionKeyId", "(I)V", true);
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

    private void defineParameterHolderVars(Advice advice) {
        Map<Integer, Integer> locals = Maps.newHashMap();
        int argIndex = 0;
        for (AdviceParameter adviceParameter : advice.onBeforeParameters()) {
            if (adviceParameter.kind() == ParameterKind.METHOD_ARG) {
                if (adviceParameter.type().equals(parameterHolderType)) {
                    int local = newLocal(parameterHolderImplType);
                    // temporary initial value needed for Java 7 stack map frames
                    visitInsn(ACONST_NULL);
                    storeLocal(local);
                    locals.put(argIndex, local);
                }
                argIndex++;
            }
        }
        parameterHolderLocals.put(advice, locals);
    }

    private void invokeOnBefore(Advice advice, @Nullable Integer travelerLocal) {
        Method onBeforeAdvice = advice.onBeforeAdvice();
        if (onBeforeAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onBeforeBlockEnd = null;
        if (enabledLocal != null) {
            if (name.equals("<init>")) {
                loadLocal(enabledLocal);
            } else {
                onBeforeBlockEnd = new Label();
                loadLocal(enabledLocal);
                visitJumpInsn(IFEQ, onBeforeBlockEnd);
            }
        }
        Map<Integer, Integer> parameterHolderLocals =
                checkNotNull(this.parameterHolderLocals.get(advice));
        loadMethodParameters(advice.onBeforeParameters(), 0, null, advice.adviceType(),
                OnBefore.class, false, parameterHolderLocals, advice.pointcut().nestingGroup(),
                advice.pointcut().suppressionKey());
        if (enabledLocal != null && name.equals("<init>")) {
            String descriptor = "(Z" + onBeforeAdvice.getDescriptor().substring(1);
            visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                    onBeforeAdvice.getName(), descriptor, false);
        } else {
            visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                    onBeforeAdvice.getName(), onBeforeAdvice.getDescriptor(), false);
        }
        if (travelerLocal != null) {
            storeLocal(travelerLocal);
        }
        for (Map.Entry<Integer, Integer> entry : parameterHolderLocals.entrySet()) {
            int argIndex = entry.getKey();
            int parameterHolderLocal = entry.getValue();
            loadLocal(parameterHolderLocal);
            invokeVirtual(parameterHolderImplType, parameterHolderImplGetMethod);
            Type argType = getArgumentTypes()[argIndex];
            boolean primitive = argType.getSort() < Type.ARRAY;
            if (primitive) {
                unbox(argType);
            } else {
                checkCast(argType);
            }
            storeArg(argIndex);
        }
        if (onBeforeBlockEnd != null) {
            visitLabel(onBeforeBlockEnd);
            visitImplicitFrame();
        }
    }

    private void saveArgsForMethodExit() {
        int numSavedArgs = getNumSavedArgsNeeded();
        if (numSavedArgs == 0) {
            return;
        }
        savedArgLocals = new int[numSavedArgs];
        for (int i = 0; i < numSavedArgs; i++) {
            savedArgLocals[i] = newLocal(argumentTypes[i]);
            loadArg(i);
            storeLocal(savedArgLocals[i]);
        }
    }

    private int getNumSavedArgsNeeded() {
        int numSaveArgsNeeded = 0;
        for (Advice advice : advisors) {
            numSaveArgsNeeded = Math.max(numSaveArgsNeeded, getNum(advice.onReturnParameters()));
            numSaveArgsNeeded = Math.max(numSaveArgsNeeded, getNum(advice.onAfterParameters()));
            numSaveArgsNeeded = Math.max(numSaveArgsNeeded, getNum(advice.onThrowParameters()));
        }
        return numSaveArgsNeeded;
    }

    private int getNum(List<AdviceParameter> adviceParameters) {
        int numSaveArgsNeeded = 0;
        for (AdviceParameter parameter : adviceParameters) {
            if (parameter.kind() == ParameterKind.METHOD_ARG_ARRAY) {
                return argumentTypes.length;
            } else if (parameter.kind() == ParameterKind.METHOD_ARG) {
                numSaveArgsNeeded++;
            }
        }
        return numSaveArgsNeeded;
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
                visitImplicitFrame();
            } else {
                visitImplicitFrame(convert(returnType));
            }
        }
    }

    private void weaveOnReturnAdvice(int opcode, Advice advice, Method onReturnAdvice) {
        if (onReturnAdvice.getArgumentTypes().length > 0) {
            // @BindReturn must be the first argument to @OnReturn (if present)
            int startIndex;
            Object[] stack;
            AdviceParameter parameter = advice.onReturnParameters().get(0);
            boolean leaveReturnValueOnStack = onReturnAdvice.getReturnType().getSort() == Type.VOID;
            switch (parameter.kind()) {
                case RETURN:
                    loadNonOptionalReturnValue(opcode, parameter, leaveReturnValueOnStack);
                    startIndex = 1;
                    if (leaveReturnValueOnStack && opcode != RETURN) {
                        stack = new Object[] {convert(returnType), convert(parameter.type())};
                    } else {
                        stack = new Object[] {convert(parameter.type())};
                    }
                    break;
                case OPTIONAL_RETURN:
                    loadOptionalReturnValue(opcode, leaveReturnValueOnStack);
                    startIndex = 1;
                    if (leaveReturnValueOnStack && opcode != RETURN) {
                        stack = new Object[] {convert(returnType), convert(parameter.type())};
                    } else {
                        stack = new Object[] {convert(parameter.type())};
                    }
                    break;
                default:
                    // first argument is not @BindReturn (which means there is no @BindReturn)
                    startIndex = 0;
                    if (opcode == RETURN) {
                        stack = new Object[] {};
                    } else {
                        if (onReturnAdvice.getReturnType().getSort() == Type.VOID) {
                            stack = new Object[] {convert(returnType)};
                        } else {
                            pop();
                            stack = new Object[] {};
                        }
                    }
                    break;
            }
            loadMethodParameters(advice.onReturnParameters(), startIndex,
                    travelerLocals.get(advice), advice.adviceType(), OnReturn.class, true, null,
                    advice.pointcut().nestingGroup(), advice.pointcut().suppressionKey(), stack);
        } else if (onReturnAdvice.getReturnType().getSort() != Type.VOID && opcode != RETURN) {
            pop();
        }
        visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                onReturnAdvice.getName(), onReturnAdvice.getDescriptor(), false);
        if (onReturnAdvice.getReturnType().getSort() != Type.VOID && opcode == RETURN) {
            pop();
        }
    }

    private void loadNonOptionalReturnValue(int opcode, AdviceParameter parameter, boolean dup) {
        if (opcode == RETURN) {
            logger.warn("cannot use @BindReturn on a @Pointcut returning void");
            pushDefault(parameter.type());
        } else {
            boolean primitive = parameter.type().getSort() < Type.ARRAY;
            loadReturnValue(opcode, dup, !primitive);
        }
    }

    private void loadOptionalReturnValue(int opcode, boolean dup) {
        if (opcode == RETURN) {
            // void
            visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/VoidReturn",
                    "getInstance", "()Lorg/glowroot/agent/plugin/api/weaving/OptionalReturn;",
                    false);
        } else {
            loadReturnValue(opcode, dup, true);
            visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/NonVoidReturn", "create",
                    "(Ljava/lang/Object;)Lorg/glowroot/agent/plugin/api/weaving/OptionalReturn;",
                    false);
        }
    }

    private void loadReturnValue(int opcode, boolean dup, boolean autobox) {
        if (dup) {
            if (opcode == LRETURN || opcode == DRETURN) {
                visitInsn(DUP2);
            } else {
                visitInsn(DUP);
            }
        }
        if (autobox && opcode != ARETURN && opcode != ATHROW) {
            box(returnType);
        }
    }

    private void visitOnThrowAdvice(Advice advice) {
        Method onThrowAdvice = advice.onThrowAdvice();
        if (onThrowAdvice == null) {
            return;
        }
        Integer enabledLocal = enabledLocals.get(advice);
        Label onThrowBlockEnd = null;
        if (enabledLocal != null) {
            onThrowBlockEnd = new Label();
            loadLocal(enabledLocal);
            visitJumpInsn(IFEQ, onThrowBlockEnd);
        }
        if (onThrowAdvice.getArgumentTypes().length > 0) {
            int startIndex;
            Object[] stack;
            if (advice.onThrowParameters().get(0).kind() == ParameterKind.THROWABLE) {
                // @BindThrowable must be the first argument to @OnThrow (if present)
                visitInsn(DUP);
                startIndex = 1;
                stack = new Object[] {"java/lang/Throwable", "java/lang/Throwable"};
            } else {
                startIndex = 0;
                stack = new Object[] {"java/lang/Throwable"};
            }
            loadMethodParameters(advice.onThrowParameters(), startIndex, travelerLocals.get(advice),
                    advice.adviceType(), OnThrow.class, true, null,
                    advice.pointcut().nestingGroup(), advice.pointcut().suppressionKey(), stack);
        }
        visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                onThrowAdvice.getName(), onThrowAdvice.getDescriptor(), false);
        if (onThrowBlockEnd != null) {
            visitLabel(onThrowBlockEnd);
            visitImplicitFrame("java/lang/Throwable");
        }
    }

    private void visitOnAfterAdvice(Advice advice, boolean insideCatchHandler) {
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
                advice.adviceType(), OnAfter.class, true, null, advice.pointcut().nestingGroup(),
                advice.pointcut().suppressionKey());
        visitMethodInsn(INVOKESTATIC, advice.adviceType().getInternalName(),
                onAfterAdvice.getName(), onAfterAdvice.getDescriptor(), false);
        if (onAfterBlockEnd != null) {
            visitLabel(onAfterBlockEnd);
            // either inside catch handler or inside on return block
            if (insideCatchHandler) {
                visitImplicitFrame("java/lang/Throwable");
            } else if (returnType.getSort() == Type.VOID) {
                visitImplicitFrame();
            } else {
                visitImplicitFrame(convert(returnType));
            }
        }
    }

    // only called from inside catch handler or inside on return block
    private void resetCurrentNestingGroupIfNeeded(boolean insideCatchHandler) {
        ListIterator<Advice> i = advisors.listIterator(advisors.size());
        while (i.hasPrevious()) {
            Advice advice = i.previous();
            Integer prevNestingGroupIdLocal = prevNestingGroupIdLocals.get(advice);
            if (prevNestingGroupIdLocal != null) {
                loadLocal(prevNestingGroupIdLocal);
                visitInsn(ICONST_M1);
                Label label = new Label();
                visitJumpInsn(IF_ICMPEQ, label);
                checkNotNull(threadContextLocal);
                loadLocal(threadContextLocal);
                loadLocal(prevNestingGroupIdLocal);
                visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                        "setCurrentNestingGroupId", "(I)V", true);
                visitLabel(label);
                // either inside catch handler or inside on return block
                if (insideCatchHandler) {
                    visitImplicitFrame("java/lang/Throwable");
                } else if (returnType.getSort() == Type.VOID) {
                    visitImplicitFrame();
                } else {
                    visitImplicitFrame(convert(returnType));
                }
            }
            Integer prevSuppressionKeyIdLocal = prevSuppressionKeyIdLocals.get(advice);
            if (prevSuppressionKeyIdLocal != null) {
                loadLocal(prevSuppressionKeyIdLocal);
                visitInsn(ICONST_M1);
                Label label = new Label();
                visitJumpInsn(IF_ICMPEQ, label);
                checkNotNull(threadContextLocal);
                loadLocal(threadContextLocal);
                loadLocal(prevSuppressionKeyIdLocal);
                visitMethodInsn(INVOKEINTERFACE, threadContextPlusType.getInternalName(),
                        "setCurrentSuppressionKeyId", "(I)V", true);
                visitLabel(label);
                // either inside catch handler or inside on return block
                if (insideCatchHandler) {
                    visitImplicitFrame("java/lang/Throwable");
                } else if (returnType.getSort() == Type.VOID) {
                    visitImplicitFrame();
                } else {
                    visitImplicitFrame(convert(returnType));
                }
            }
        }
    }

    private void loadMethodParameters(List<AdviceParameter> parameters, int startIndex,
            @Nullable Integer travelerLocal, Type adviceType,
            Class<? extends Annotation> annotationType, boolean useSavedArgs,
            @Nullable Map<Integer, Integer> parameterHolderLocals, String nestingGroup,
            String suppressionKey, Object... stack) {
        int argIndex = 0;
        for (int i = startIndex; i < parameters.size(); i++) {
            AdviceParameter parameter = parameters.get(i);
            switch (parameter.kind()) {
                case RECEIVER:
                    loadTarget();
                    break;
                case METHOD_ARG:
                    loadMethodParameter(adviceType, annotationType, argIndex, parameter,
                            useSavedArgs);
                    if (parameterHolderLocals != null && parameter.type().getClassName()
                            .equals(ParameterHolder.class.getName())) {
                        invokeStatic(parameterHolderImplType, parameterHolderImplCreateMethod);
                        dup();
                        storeLocal(checkNotNull(parameterHolderLocals.get(argIndex)));
                    }
                    argIndex++;
                    break;
                case METHOD_ARG_ARRAY:
                    loadArgArray(useSavedArgs);
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
                case THREAD_CONTEXT:
                    checkNotNull(threadContextLocal);
                    loadLocal(threadContextLocal);
                    break;
                case OPTIONAL_THREAD_CONTEXT:
                    checkNotNull(threadContextHolderLocal);
                    checkNotNull(threadContextLocal);
                    loadOptionalThreadContext(nestingGroup, suppressionKey, stack);
                    break;
                default:
                    // this should have been caught during Advice construction, but just in case:
                    logger.warn(
                            "the @{} method in {} has an unexpected parameter kind {} at index {}",
                            annotationType.getSimpleName(), adviceType.getClassName(),
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

    private void loadMethodParameter(Type adviceType, Class<? extends Annotation> annotationType,
            int argIndex, AdviceParameter parameter, boolean useSavedArg) {
        if (argIndex >= argumentTypes.length) {
            logger.warn(
                    "the @{} method in {} has more @{} arguments than the number of args in"
                            + " the target method",
                    annotationType.getSimpleName(), adviceType.getClassName(),
                    BindParameter.class.getSimpleName());
            pushDefault(parameter.type());
            return;
        }
        if (useSavedArg) {
            loadLocal(savedArgLocals[argIndex]);
        } else {
            loadArg(argIndex);
        }
        boolean primitive = parameter.type().getSort() < Type.ARRAY;
        if (!primitive) {
            // autobox
            box(argumentTypes[argIndex]);
        }
    }

    private void loadArgArray(boolean useSavedArgs) {
        push(argumentTypes.length);
        newArray(objectType);
        for (int i = 0; i < argumentTypes.length; i++) {
            dup();
            push(i);
            if (useSavedArgs) {
                loadLocal(savedArgLocals[i]);
            } else {
                loadArg(i);
            }
            box(argumentTypes[i]);
            arrayStore(objectType);
        }
    }

    private void loadMethodName() {
        visitLdcInsn(name);
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
        String classMetaFieldName =
                "glowroot$class$meta$" + classMetaFieldType.getInternalName().replace('/', '$');
        if (bootstrapClassLoader) {
            int index = BootstrapMetaHolders.reserveClassMetaHolderIndex(metaHolderInternalName,
                    classMetaFieldName);
            push(index);
            visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(), "getClassMeta",
                    "(I)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, classMetaFieldType.getInternalName());
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
            visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(), "getMethodMeta",
                    "(I)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, methodMetaFieldType.getInternalName());
        } else {
            visitFieldInsn(GETSTATIC, metaHolderInternalName, methodMetaFieldName,
                    methodMetaFieldType.getDescriptor());
        }
    }

    @RequiresNonNull({"threadContextHolderLocal", "threadContextLocal"})
    private void loadOptionalThreadContext(String nestingGroup, String suppressionKey,
            Object... stack) {
        loadMaybeNullThreadContext(stack);
        Label label = new Label();
        visitJumpInsn(IFNONNULL, label);
        loadLocal(threadContextHolderLocal);
        if (nestingGroup.isEmpty()) {
            mv.visitLdcInsn(0);
        } else {
            mv.visitLdcInsn(getNestingGroupId(nestingGroup));
        }
        if (suppressionKey.isEmpty()) {
            mv.visitLdcInsn(0);
        } else {
            mv.visitLdcInsn(getSuppressionKeyId(suppressionKey));
        }
        visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(), "createOptionalThreadContext",
                "(" + fastThreadContextThreadLocalHolderType.getDescriptor() + "II)"
                        + threadContextPlusType.getDescriptor(),
                false);
        storeLocal(threadContextLocal);
        visitLabel(label);
        visitImplicitFrame(stack);
        loadLocal(threadContextLocal);
    }

    @RequiresNonNull({"threadContextHolderLocal", "threadContextLocal"})
    private void loadMaybeNullThreadContext(Object... stack) {
        loadLocal(threadContextHolderLocal);
        Label label = new Label();
        visitJumpInsn(IFNONNULL, label);
        visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(),
                "getCurrentThreadContextHolder",
                "()" + fastThreadContextThreadLocalHolderType.getDescriptor(), false);
        storeLocal(threadContextHolderLocal);
        visitLabel(label);
        visitImplicitFrame(stack);
        loadLocal(threadContextHolderLocal);
        visitMethodInsn(INVOKEVIRTUAL, fastThreadContextThreadLocalHolderType.getInternalName(),
                "get", "()" + threadContextPlusType.getDescriptor(), false);
        dup();
        storeLocal(threadContextLocal);
    }

    private void visitImplicitFrame(Object... stack) {
        if (frames) {
            super.visitFrame(F_NEW, implicitFrameLocals.length, implicitFrameLocals, stack.length,
                    stack);
        }
    }

    @Override
    public void visitFrame(int type, int nLocal, Object /*@Nullable*/ [] local, int nStack,
            Object /*@Nullable*/ [] stack) {
        checkState(type == F_NEW, "Unexpected frame type: " + type);
        int nExtraLocal = nLocal - implicitFrameLocals.length;
        if (implicitFrameLocals.length >= nLocal) {
            nExtraLocal = 0;
        } else {
            int i = 0;
            int j = 0;
            while (i < nLocal && j < implicitFrameLocals.length) {
                Object currLocal = checkNotNull(local)[i++];
                Object currImplicitFrameLocal = implicitFrameLocals[j++];
                if (currLocal == TOP
                        && (currImplicitFrameLocal == LONG || currImplicitFrameLocal == DOUBLE)) {
                    i++;
                }
            }
            nExtraLocal = nLocal - i;
        }
        if (nExtraLocal > 0) {
            Object[] overlay = new Object[implicitFrameLocals.length + nExtraLocal];
            System.arraycopy(implicitFrameLocals, 0, overlay, 0, implicitFrameLocals.length);
            System.arraycopy(checkNotNull(local), nLocal - nExtraLocal, overlay,
                    implicitFrameLocals.length, nExtraLocal);
            super.visitFrame(type, overlay.length, overlay, nStack, stack);
        } else {
            super.visitFrame(type, implicitFrameLocals.length, implicitFrameLocals, nStack, stack);
        }
    }

    private void pushDefault(Type type) {
        pushDefault(this, type);
    }

    static void pushDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                mv.visitInsn(ICONST_0);
                break;
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                mv.visitInsn(ICONST_0);
                break;
            case Type.FLOAT:
                mv.visitInsn(FCONST_0);
                break;
            case Type.LONG:
                mv.visitInsn(LCONST_0);
                break;
            case Type.DOUBLE:
                mv.visitInsn(DCONST_0);
                break;
            default:
                mv.visitInsn(ACONST_NULL);
                break;
        }
    }

    // need to drain stack if any
    // normal javac bytecode leaves clean stack, but this is not a requirement of valid
    // bytecode, e.g. see:
    // https://github.com/jbossas/jboss-invocation/blob/09ac89f4c77f59be12a96a1946273e4fd40a9f78/src/main/java/org/jboss/invocation/proxy/ProxyFactory.java#L166
    // ideally this would have else statement and pop() the prior method result if the
    // bytecode generated method returns void
    private void cleanUpStackIfNeeded(int opcode) {
        int expectedStackFrameSize = getExpectedStackFrameSize(opcode);
        if (stackFrame.size() == expectedStackFrameSize) {
            return;
        }
        if (stackFrame.size() < expectedStackFrameSize) {
            // this shouldn't happen
            return;
        }
        if (expectedStackFrameSize == 0) {
            cleanExcessFramesLeavingNothing();
            return;
        }
        if (expectedStackFrameSize == 1) {
            cleanExcessFramesLeavingOneWord();
            return;
        }
        cleanExcessFramesLeavingDoubleWord();
    }

    private void cleanExcessFramesLeavingNothing() {
        int excessFrames = stackFrame.size();
        for (int i = excessFrames - 1; i >= 0; i--) {
            if (stackFrame.get(i) == SECOND_WORD) {
                pop2();
                i--;
            } else {
                pop();
            }
        }
    }

    private void cleanExcessFramesLeavingOneWord() {
        int excessFrames = stackFrame.size() - 1;
        for (int i = excessFrames - 1; i >= 0; i--) {
            if (stackFrame.get(i) == SECOND_WORD) {
                // duplicate top word and insert beneath the third word
                super.visitInsn(DUP_X2);
                pop();
                pop2();
                i--;
            } else {
                swap();
                pop();
            }
        }
    }

    private void cleanExcessFramesLeavingDoubleWord() {
        int excessFrames = stackFrame.size() - 2;
        for (int i = excessFrames - 1; i >= 0; i--) {
            if (stackFrame.get(i) == SECOND_WORD) {
                // duplicate two word and insert beneath the fourth word
                super.visitInsn(DUP2_X2);
                pop2();
                pop2();
                i--;
            } else {
                // duplicate two words and insert beneath third word
                super.visitInsn(DUP2_X1);
                pop2();
                pop();
            }
        }
    }

    private static Object convert(Type type) {
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
                return type.getDescriptor();
            case Type.OBJECT:
                return type.getInternalName();
            case Type.METHOD:
                return type.getDescriptor();
            default:
                throw new IllegalStateException("Unexpected type: " + type.getDescriptor());
        }
    }

    private static boolean hasOtherEnabledFactors(Advice advice, String nestingGroup,
            String suppressibleUsingKey) {
        return !nestingGroup.isEmpty() || !suppressibleUsingKey.isEmpty()
                || (advice.hasBindThreadContext() && !advice.hasBindOptionalThreadContext());
    }

    private static int getNestingGroupId(String nestingGroup) {
        Integer nullableNestingGroupId = nestingGroupIds.get(nestingGroup);
        if (nullableNestingGroupId != null) {
            return nullableNestingGroupId;
        }
        int nestingGroupId = nestingGroupIdCounter.getAndIncrement();
        Integer previousValue = nestingGroupIds.putIfAbsent(nestingGroup, nestingGroupId);
        if (previousValue == null) {
            return nestingGroupId;
        } else {
            // handling race condition
            return previousValue;
        }
    }

    private static int getSuppressionKeyId(String suppressionKey) {
        Integer nullableSuppressionKeyId = suppressionKeyIds.get(suppressionKey);
        if (nullableSuppressionKeyId != null) {
            return nullableSuppressionKeyId;
        }
        int suppressionKeyId = suppressionKeyIdCounter.getAndIncrement();
        Integer previousValue = suppressionKeyIds.putIfAbsent(suppressionKey, suppressionKeyId);
        if (previousValue == null) {
            return suppressionKeyId;
        } else {
            // handling race condition
            return previousValue;
        }
    }

    private static boolean isReturnOpcode(int opcode) {
        return opcode >= IRETURN && opcode <= RETURN;
    }

    private static int getExpectedStackFrameSize(int opcode) {
        if (opcode == IRETURN || opcode == FRETURN || opcode == ARETURN) {
            return 1;
        } else if (opcode == LRETURN || opcode == DRETURN) {
            return 2;
        } else {
            return 0;
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface CatchHandler {
        Label catchStartLabel();
        // advisors that have successfully executed @OnBefore
        List<Advice> advisors();
    }
}

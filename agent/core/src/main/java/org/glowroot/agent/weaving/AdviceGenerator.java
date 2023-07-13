/*
 * Copyright 2013-2023 the original author or authors.
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
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.AlreadyInTransactionBehavior;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.INSTANCEOF;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

class AdviceGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdviceGenerator.class);

    private static final AtomicInteger counter = new AtomicInteger();

    private final InstrumentationConfig config;
    private final @Nullable String pluginId;
    private final int priorityForSetters;
    private final String adviceInternalName;
    private final @Nullable String methodMetaInternalName;
    private final int uniqueNum;

    static ImmutableMap<Advice, LazyDefinedClass> createAdvisors(
            List<InstrumentationConfig> configs, @Nullable String pluginId, boolean userPlugin,
            boolean reweavable) {
        Map<Advice, LazyDefinedClass> advisors = Maps.newHashMap();
        for (InstrumentationConfig config : configs) {
            if (!config.validationErrors().isEmpty()) {
                continue;
            }
            try {
                LazyDefinedClass lazyAdviceClass =
                        new AdviceGenerator(config, pluginId, userPlugin).generate();
                Advice advice = new AdviceBuilder(lazyAdviceClass, reweavable).build();
                advisors.put(advice, lazyAdviceClass);
            } catch (Exception e) {
                logger.error("error creating advice for advice config: {}", config, e);
            }
        }
        return ImmutableMap.copyOf(advisors);
    }

    private AdviceGenerator(InstrumentationConfig config, @Nullable String pluginId,
            boolean userPlugin) {
        this.config = config;
        this.pluginId = pluginId;
        if (pluginId == null) {
            priorityForSetters = Priority.USER_CONFIG;
        } else if (userPlugin) {
            priorityForSetters = Priority.USER_PLUGIN;
        } else {
            priorityForSetters = Priority.CORE_PLUGIN;
        }
        uniqueNum = counter.incrementAndGet();
        adviceInternalName = "org/glowroot/agent/weaving/GeneratedAdvice" + uniqueNum;
        if (config.isTraceEntryOrGreater() || !config.transactionNameTemplate().isEmpty()
                || !config.transactionUserTemplate().isEmpty()
                || !config.transactionAttributeTemplates().isEmpty()) {
            // templates are used, so method meta is needed
            methodMetaInternalName = "org/glowroot/agent/weaving/GeneratedMethodMeta" + uniqueNum;
        } else {
            methodMetaInternalName = null;
        }
    }

    private LazyDefinedClass generate() {
        LazyDefinedClass methodMetaClass = null;
        if (methodMetaInternalName != null) {
            methodMetaClass = generateMethodMetaClass(config);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        String[] interfaces = null;
        if (!config.enabledProperty().isEmpty() || !config.traceEntryEnabledProperty().isEmpty()) {
            interfaces = new String[] {"org/glowroot/agent/plugin/api/config/ConfigListener"};
        }
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceInternalName, null, "java/lang/Object",
                interfaces);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        boolean checkNotInTransaction = config.isTransaction()
                && config.alreadyInTransactionBehavior() == AlreadyInTransactionBehavior.DO_NOTHING;
        boolean checkPropertyNotEnabled = pluginId != null && !config.enabledProperty().isEmpty();
        addIsEnabledMethodIfNeeded(cw, checkNotInTransaction, checkPropertyNotEnabled);
        if (config.isTraceEntryOrGreater()) {
            // methodMetaInternalName is non-null when entry or greater
            checkNotNull(methodMetaInternalName);
            addOnBeforeMethod(cw);
            addOnThrowMethod(cw);
            addOnReturnMethod(cw);
        } else if (config.captureKind() == CaptureKind.TIMER) {
            addOnBeforeMethodTimerOnly(cw);
            addOnAfterMethodTimerOnly(cw);
        } else {
            addOnBeforeMethodOther(cw);
        }
        cw.visitEnd();
        ImmutableLazyDefinedClass.Builder builder = ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType(adviceInternalName))
                .bytes(cw.toByteArray());
        if (methodMetaClass != null) {
            builder.addDependencies(methodMetaClass);
        }
        return builder.build();
    }

    private void addClassAnnotation(ClassWriter cw) {
        AnnotationVisitor annotationVisitor =
                cw.visitAnnotation("Lorg/glowroot/agent/plugin/api/weaving/Pointcut;", true);
        annotationVisitor.visit("className", config.className());
        annotationVisitor.visit("classAnnotation", config.classAnnotation());
        annotationVisitor.visit("subTypeRestriction", config.subTypeRestriction());
        annotationVisitor.visit("superTypeRestriction", config.superTypeRestriction());
        annotationVisitor.visit("methodName", config.methodName());
        annotationVisitor.visit("methodAnnotation", config.methodAnnotation());
        AnnotationVisitor arrayAnnotationVisitor =
                checkNotNull(annotationVisitor.visitArray("methodParameterTypes"));
        for (String methodParameterType : config.methodParameterTypes()) {
            arrayAnnotationVisitor.visit(null, methodParameterType);
        }
        arrayAnnotationVisitor.visitEnd();
        String timerName = config.timerName();
        if (config.isTimerOrGreater()) {
            if (timerName.isEmpty()) {
                annotationVisitor.visit("timerName", "<no timer name provided>");
            } else {
                annotationVisitor.visit("timerName", timerName);
            }
        }
        String nestingGroup = config.nestingGroup();
        if (!nestingGroup.isEmpty()) {
            annotationVisitor.visit("nestingGroup", nestingGroup);
        } else if (!config.traceEntryCaptureSelfNested()) {
            annotationVisitor.visit("nestingGroup", "__GeneratedAdvice" + uniqueNum);
        }
        annotationVisitor.visit("order", config.order());
        annotationVisitor.visitEnd();
    }

    private void addStaticFields(ClassWriter cw) {
        if (pluginId != null) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "configService",
                    "Lorg/glowroot/agent/plugin/api/config/ConfigService;", null, null).visitEnd();
        }
        if (config.isTimerOrGreater()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "timerName",
                    "Lorg/glowroot/agent/plugin/api/TimerName;", null, null).visitEnd();
        }
        if (!config.enabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_FINAL, "enabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;", null, null)
                    .visitEnd();
        }
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_FINAL, "entryEnabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;", null, null)
                    .visitEnd();
        }
    }

    private void addStaticInitializer(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        if (pluginId != null) {
            mv.visitLdcInsn(pluginId);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/plugin/api/Agent",
                    "getConfigService",
                    "(Ljava/lang/String;)Lorg/glowroot/agent/plugin/api/config/ConfigService;",
                    false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "configService",
                    "Lorg/glowroot/agent/plugin/api/config/ConfigService;");
        }
        if (config.isTimerOrGreater()) {
            mv.visitLdcInsn(Type.getObjectType(adviceInternalName));
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/plugin/api/Agent", "getTimerName",
                    "(Ljava/lang/Class;)Lorg/glowroot/agent/plugin/api/TimerName;", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "timerName",
                    "Lorg/glowroot/agent/plugin/api/TimerName;");
        }
        if (!config.enabledProperty().isEmpty() && pluginId != null) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "configService",
                    "Lorg/glowroot/agent/plugin/api/config/ConfigService;");
            mv.visitLdcInsn(config.enabledProperty());
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/config/ConfigService", "getBooleanProperty",
                    "(Ljava/lang/String;)Lorg/glowroot/agent/plugin/api/config/BooleanProperty;",
                    true);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "enabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;");
        }
        if (!config.traceEntryEnabledProperty().isEmpty() && pluginId != null) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "configService",
                    "Lorg/glowroot/agent/plugin/api/config/ConfigService;");
            mv.visitLdcInsn(config.traceEntryEnabledProperty());
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/config/ConfigService", "getBooleanProperty",
                    "(Ljava/lang/String;)Lorg/glowroot/agent/plugin/api/config/BooleanProperty;",
                    true);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "entryEnabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addIsEnabledMethodIfNeeded(ClassWriter cw, boolean checkNotInTransaction,
            boolean checkPropertyNotEnabled) {
        if (!checkNotInTransaction && !checkPropertyNotEnabled) {
            return;
        }
        String descriptor;
        if (checkNotInTransaction) {
            descriptor = "(Lorg/glowroot/agent/plugin/api/OptionalThreadContext;)Z";
        } else {
            descriptor = "()Z";
        }
        MethodVisitor mv =
                cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "isEnabled", descriptor, null, null);
        visitAnnotation(mv, "Lorg/glowroot/agent/plugin/api/weaving/IsEnabled;");
        mv.visitCode();
        if (checkNotInTransaction && !checkPropertyNotEnabled) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/OptionalThreadContext", "isInTransaction", "()Z",
                    true);
            Label returnTrueLabel = new Label();
            mv.visitJumpInsn(IFEQ, returnTrueLabel);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitLabel(returnTrueLabel);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        } else if (!checkNotInTransaction) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "enabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;");
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/config/BooleanProperty", "value", "()Z", true);
            mv.visitInsn(IRETURN);
        } else {
            // check both
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/OptionalThreadContext", "isInTransaction", "()Z",
                    true);
            Label returnTrueLabel = new Label();
            mv.visitJumpInsn(IFEQ, returnTrueLabel);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "enabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;");
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/config/BooleanProperty", "value", "()Z", true);
            mv.visitJumpInsn(IFNE, returnTrueLabel);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitLabel(returnTrueLabel);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void addOnBeforeMethod(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/agent/plugin/api/TraceEntry;");
        mv.visitCode();
        if (!config.traceEntryEnabledProperty().isEmpty() && pluginId != null) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "entryEnabled",
                    "Lorg/glowroot/agent/plugin/api/config/BooleanProperty;");
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/config/BooleanProperty", "value", "()Z", true);
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            // entryEnabled is false, collect timer only
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "timerName",
                    "Lorg/glowroot/agent/plugin/api/TimerName;");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                    "startTimer", "(Lorg/glowroot/agent/plugin/api/TimerName;)"
                            + "Lorg/glowroot/agent/plugin/api/Timer;",
                    true);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
        }
        mv.visitVarInsn(ALOAD, 0);
        if (config.isTransaction()) {
            String transactionType = config.transactionType();
            if (transactionType.isEmpty()) {
                mv.visitLdcInsn("<no transaction type provided>");
            } else {
                mv.visitLdcInsn(transactionType);
            }
            mv.visitVarInsn(ALOAD, 4);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionNameTemplate",
                    "()Lorg/glowroot/agent/bytecode/api/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                    "getMessageText",
                    "(Lorg/glowroot/agent/bytecode/api/MessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Ljava/lang/String;",
                    false);
        }
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getMessageTemplate",
                "()Lorg/glowroot/agent/bytecode/api/MessageTemplate;", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                "createMessageSupplier",
                "(Lorg/glowroot/agent/bytecode/api/MessageTemplate;"
                        + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Lorg/glowroot/agent/plugin/api/MessageSupplier;",
                false);
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "timerName",
                "Lorg/glowroot/agent/plugin/api/TimerName;");
        if (config.isTransaction()) {
            String fieldName;
            if (config
                    .alreadyInTransactionBehaviorCorrected() == AlreadyInTransactionBehavior.CAPTURE_NEW_TRANSACTION) {
                fieldName = "CAPTURE_NEW_TRANSACTION";
            } else {
                // it doesn't matter what DO_NOTHING is mapped to, since in that case the @OnBefore
                // method will be bypassed completely if already in a transaction due to @IsEnabled
                fieldName = "CAPTURE_TRACE_ENTRY";
            }
            mv.visitFieldInsn(GETSTATIC,
                    "org/glowroot/agent/plugin/api/OptionalThreadContext"
                            + "$AlreadyInTransactionBehavior",
                    fieldName,
                    "Lorg/glowroot/agent/plugin/api/OptionalThreadContext"
                            + "$AlreadyInTransactionBehavior;");
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/agent/plugin/api/OptionalThreadContext", "startTransaction",
                    "(Ljava/lang/String;Ljava/lang/String;"
                            + "Lorg/glowroot/agent/plugin/api/MessageSupplier;"
                            + "Lorg/glowroot/agent/plugin/api/TimerName;"
                            + "Lorg/glowroot/agent/plugin/api/OptionalThreadContext"
                            + "$AlreadyInTransactionBehavior;)"
                            + "Lorg/glowroot/agent/plugin/api/TraceEntry;",
                    true);
        } else {
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                    "startTraceEntry",
                    "(Lorg/glowroot/agent/plugin/api/MessageSupplier;"
                            + "Lorg/glowroot/agent/plugin/api/TimerName;)"
                            + "Lorg/glowroot/agent/plugin/api/TraceEntry;",
                    true);
        }
        addCodeForOptionalTransactionAttributes(mv);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodTimerOnly(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/agent/plugin/api/Timer;");
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "timerName",
                "Lorg/glowroot/agent/plugin/api/TimerName;");
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                "startTimer", "(Lorg/glowroot/agent/plugin/api/TimerName;)"
                        + "Lorg/glowroot/agent/plugin/api/Timer;",
                true);
        addCodeForOptionalTransactionAttributes(mv);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodOther(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "V");
        mv.visitCode();
        addCodeForOptionalTransactionAttributes(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private MethodVisitor visitOnBeforeMethod(ClassWriter cw, String returnInternalName) {
        StringBuilder descriptor = new StringBuilder();
        descriptor.append("(");
        if (config.isTransaction()) {
            descriptor.append("Lorg/glowroot/agent/plugin/api/OptionalThreadContext;");
        } else {
            descriptor.append("Lorg/glowroot/agent/plugin/api/ThreadContext;");
        }
        if (methodMetaInternalName != null) {
            descriptor.append("Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;L");
            descriptor.append(methodMetaInternalName);
            descriptor.append(";)");
        } else {
            descriptor.append(")");
        }
        descriptor.append(returnInternalName);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                descriptor.toString(), null, null);
        visitAnnotation(mv, "Lorg/glowroot/agent/plugin/api/weaving/OnBefore;");
        if (methodMetaInternalName != null) {
            checkNotNull(mv.visitParameterAnnotation(1,
                    "Lorg/glowroot/agent/plugin/api/weaving/BindReceiver;", true)).visitEnd();
            checkNotNull(mv.visitParameterAnnotation(2,
                    "Lorg/glowroot/agent/plugin/api/weaving/BindMethodName;", true)).visitEnd();
            checkNotNull(mv.visitParameterAnnotation(3,
                    "Lorg/glowroot/agent/plugin/api/weaving/BindParameterArray;", true)).visitEnd();
            checkNotNull(mv.visitParameterAnnotation(4,
                    "Lorg/glowroot/agent/plugin/api/weaving/BindMethodMeta;", true)).visitEnd();
        }
        return mv;
    }

    private void addCodeForOptionalTransactionAttributes(MethodVisitor mv) {
        if (!config.transactionType().isEmpty() && !config.isTransaction()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(config.transactionType());
            mv.visitLdcInsn(priorityForSetters);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                    "setTransactionType", "(Ljava/lang/String;I)V", true);
        }
        if (!config.transactionNameTemplate().isEmpty() && !config.isTransaction()) {
            addCodeForSetTransactionX(mv, "getTransactionNameTemplate", "setTransactionName");
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            addCodeForSetTransactionX(mv, "getTransactionUserTemplate", "setTransactionUser");
        }
        int i = 0;
        for (String attrName : config.transactionAttributeTemplates().keySet()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(attrName);
            mv.visitVarInsn(ALOAD, 4);
            // methodMetaInternalName is non-null when transactionAttributeTemplates is
            // non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName,
                    "getTransactionAttributeTemplate" + i++,
                    "()Lorg/glowroot/agent/bytecode/api/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                    "getMessageText",
                    "(Lorg/glowroot/agent/bytecode/api/MessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Ljava/lang/String;",
                    false);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                    "addTransactionAttribute", "(Ljava/lang/String;Ljava/lang/String;)V", true);
        }
        Integer slowThresholdMillis = config.transactionSlowThresholdMillis();
        if (slowThresholdMillis != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(slowThresholdMillis.longValue());
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitLdcInsn(priorityForSetters);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                    "setTransactionSlowThreshold", "(JLjava/util/concurrent/TimeUnit;I)V", true);
        }
        if (config.transactionOuter()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                    "setTransactionOuter", "()V", true);
        }
    }

    private void addOnReturnMethod(ClassWriter cw) {
        boolean entryOrTimer = !config.traceEntryEnabledProperty().isEmpty();
        String travelerType =
                entryOrTimer ? "Ljava/lang/Object;" : "Lorg/glowroot/agent/plugin/api/TraceEntry;";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                "(Lorg/glowroot/agent/plugin/api/weaving/OptionalReturn;" + travelerType + ")V",
                null, null);
        checkNotNull(mv.visitParameterAnnotation(0,
                "Lorg/glowroot/agent/plugin/api/weaving/BindOptionalReturn;", true)).visitEnd();
        checkNotNull(mv.visitParameterAnnotation(1,
                "Lorg/glowroot/agent/plugin/api/weaving/BindTraveler;", true)).visitEnd();
        int travelerParamIndex = 1;
        visitAnnotation(mv, "Lorg/glowroot/agent/plugin/api/weaving/OnReturn;");
        mv.visitCode();
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            // TraceEntryImpl implements both TraceEntry and Timer so cannot check instanceof Timer
            // to differentiate here (but can check isntanceof TraceEntry)
            mv.visitTypeInsn(INSTANCEOF, "org/glowroot/agent/plugin/api/TraceEntry");
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(CHECKCAST, "org/glowroot/agent/plugin/api/Timer");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/Timer", "stop",
                    "()V", true);
            mv.visitInsn(RETURN);
            mv.visitLabel(label);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/weaving/OptionalReturn",
                "isVoid", "()Z", true);
        Label notVoidLabel = new Label();
        Label endIfLabel = new Label();
        mv.visitJumpInsn(IFEQ, notVoidLabel);
        mv.visitLdcInsn("void");
        mv.visitJumpInsn(GOTO, endIfLabel);
        mv.visitLabel(notVoidLabel);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/weaving/OptionalReturn",
                "getValue", "()Ljava/lang/Object;", true);
        mv.visitLabel(endIfLabel);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                "updateWithReturnValue",
                "(Lorg/glowroot/agent/plugin/api/TraceEntry;Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, travelerParamIndex);
        Integer stackTraceThresholdMillis = config.traceEntryStackThresholdMillis();
        if (stackTraceThresholdMillis == null) {
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/TraceEntry", "end",
                    "()V", true);
        } else {
            mv.visitLdcInsn(stackTraceThresholdMillis.longValue());
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/TraceEntry",
                    "endWithLocationStackTrace", "(JLjava/util/concurrent/TimeUnit;)V", true);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnThrowMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onThrow",
                "(Ljava/lang/Throwable;Lorg/glowroot/agent/plugin/api/TraceEntry;)V", null, null);
        visitAnnotation(mv, "Lorg/glowroot/agent/plugin/api/weaving/OnThrow;");
        checkNotNull(mv.visitParameterAnnotation(0,
                "Lorg/glowroot/agent/plugin/api/weaving/BindThrowable;", true)).visitEnd();
        checkNotNull(mv.visitParameterAnnotation(1,
                "Lorg/glowroot/agent/plugin/api/weaving/BindTraveler;", true)).visitEnd();
        mv.visitCode();
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, 1);
            Label l0 = new Label();
            mv.visitJumpInsn(IFNONNULL, l0);
            mv.visitInsn(RETURN);
            mv.visitLabel(l0);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/TraceEntry",
                "endWithError", "(Ljava/lang/Throwable;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private LazyDefinedClass generateMethodMetaClass(InstrumentationConfig config) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, methodMetaInternalName, null, "java/lang/Object",
                null);
        cw.visitField(ACC_PRIVATE + ACC_FINAL, "messageTemplate",
                "Lorg/glowroot/agent/bytecode/api/MessageTemplate;", null, null).visitEnd();
        if (!config.transactionNameTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionNameTemplate",
                    "Lorg/glowroot/agent/bytecode/api/MessageTemplate;", null, null).visitEnd();
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionUserTemplate",
                    "Lorg/glowroot/agent/bytecode/api/MessageTemplate;", null, null).visitEnd();
        }
        for (int i = 0; i < config.transactionAttributeTemplates().size(); i++) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionAttributeTemplate" + i,
                    "Lorg/glowroot/agent/bytecode/api/MessageTemplate;", null, null).visitEnd();
        }
        generateMethodMetaConstructor(cw);
        generateMethodMetaGetter(cw, "messageTemplate", "getMessageTemplate");
        if (!config.transactionNameTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionNameTemplate", "getTransactionNameTemplate");
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionUserTemplate", "getTransactionUserTemplate");
        }
        for (int i = 0; i < config.transactionAttributeTemplates().size(); i++) {
            generateMethodMetaGetter(cw, "transactionAttributeTemplate" + i,
                    "getTransactionAttributeTemplate" + i);
        }
        cw.visitEnd();
        return ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType(methodMetaInternalName))
                .bytes(cw.toByteArray())
                .build();
    }

    private void addCodeForSetTransactionX(MethodVisitor mv, String templateGetterName,
            String threadContextSetterName) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        // methodMetaInternalName is non-null when transactionNameTemplate or
        // transactionUserTemplate are non-empty
        checkNotNull(methodMetaInternalName);
        mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, templateGetterName,
                "()Lorg/glowroot/agent/bytecode/api/MessageTemplate;", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                "getMessageText",
                "(Lorg/glowroot/agent/bytecode/api/MessageTemplate;Ljava/lang/Object;"
                        + "Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Ljava/lang/String;",
                false);
        mv.visitLdcInsn(priorityForSetters);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/ThreadContext",
                threadContextSetterName, "(Ljava/lang/String;I)V", true);
    }

    @RequiresNonNull("methodMetaInternalName")
    private void generateMethodMetaConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lorg/glowroot/agent/plugin/api/MethodInfo;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        if (config.isTraceEntryOrGreater()) {
            String messageTemplate = config.traceEntryMessageTemplate();
            if (messageTemplate.isEmpty() && config.isTransaction()) {
                messageTemplate = config.transactionNameTemplate();
            }
            if (messageTemplate.isEmpty()) {
                mv.visitLdcInsn("<no message template provided>");
            } else {
                mv.visitLdcInsn(messageTemplate);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                    "createMessageTemplate",
                    "(Ljava/lang/String;Lorg/glowroot/agent/plugin/api/MethodInfo;)"
                            + "Lorg/glowroot/agent/bytecode/api/MessageTemplate;",
                    false);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "messageTemplate",
                "Lorg/glowroot/agent/bytecode/api/MessageTemplate;");
        if (!config.transactionNameTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(config.transactionNameTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                    "createMessageTemplate",
                    "(Ljava/lang/String;Lorg/glowroot/agent/plugin/api/MethodInfo;)"
                            + "Lorg/glowroot/agent/bytecode/api/MessageTemplate;",
                    false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionNameTemplate",
                    "Lorg/glowroot/agent/bytecode/api/MessageTemplate;");
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(config.transactionUserTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                    "createMessageTemplate",
                    "(Ljava/lang/String;Lorg/glowroot/agent/plugin/api/MethodInfo;)"
                            + "Lorg/glowroot/agent/bytecode/api/MessageTemplate;",
                    false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionUserTemplate",
                    "Lorg/glowroot/agent/bytecode/api/MessageTemplate;");
        }
        int i = 0;
        for (String attrTemplate : config.transactionAttributeTemplates().values()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(attrTemplate);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Bytecode",
                    "createMessageTemplate",
                    "(Ljava/lang/String;Lorg/glowroot/agent/plugin/api/MethodInfo;)"
                            + "Lorg/glowroot/agent/bytecode/api/MessageTemplate;",
                    false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName,
                    "transactionAttributeTemplate" + i++,
                    "Lorg/glowroot/agent/bytecode/api/MessageTemplate;");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void generateMethodMetaGetter(ClassWriter cw, String fieldName, String methodName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName,
                "()Lorg/glowroot/agent/bytecode/api/MessageTemplate;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, methodMetaInternalName, fieldName,
                "Lorg/glowroot/agent/bytecode/api/MessageTemplate;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void addOnAfterMethodTimerOnly(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onAfter",
                "(Lorg/glowroot/agent/plugin/api/Timer;)V", null, null);
        visitAnnotation(mv, "Lorg/glowroot/agent/plugin/api/weaving/OnAfter;");
        checkNotNull(mv.visitParameterAnnotation(0,
                "Lorg/glowroot/agent/plugin/api/weaving/BindTraveler;", true)).visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/agent/plugin/api/Timer", "stop", "()V",
                true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void visitAnnotation(MethodVisitor mv, String descriptor) {
        AnnotationVisitor av = mv.visitAnnotation(descriptor, true);
        checkNotNull(av);
        av.visitEnd();
    }
}

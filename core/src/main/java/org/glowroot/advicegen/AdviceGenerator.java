/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.advicegen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.config.CaptureKind;
import org.glowroot.config.InstrumentationConfig;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.AdviceBuilder;
import org.glowroot.weaving.AdviceFlowOuterHolder;
import org.glowroot.weaving.LazyDefinedClass;

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
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
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

public class AdviceGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdviceGenerator.class);

    private static final String HANDLE_CLASS_NAME =
            "org/glowroot/transaction/PluginServicesRegistry";
    private static final String HANDLE_METHOD_NAME = "get";

    private static final AtomicInteger counter = new AtomicInteger();

    private final InstrumentationConfig config;
    private final @Nullable String pluginId;
    private final String adviceInternalName;
    private final @Nullable String methodMetaInternalName;

    public static ImmutableMap<Advice, LazyDefinedClass> createAdvisors(
            List<InstrumentationConfig> configs, @Nullable String pluginId) {
        Map<Advice, LazyDefinedClass> advisors = Maps.newHashMap();
        for (InstrumentationConfig config : configs) {
            if (!config.validationErrors().isEmpty()) {
                continue;
            }
            try {
                LazyDefinedClass lazyAdviceClass =
                        new AdviceGenerator(config, pluginId).generate();
                boolean reweavable = pluginId == null;
                Advice advice = new AdviceBuilder(lazyAdviceClass, reweavable).build();
                advisors.put(advice, lazyAdviceClass);
            } catch (Exception e) {
                logger.error("error creating advice for advice config: {}", config, e);
            }
        }
        return ImmutableMap.copyOf(advisors);
    }

    private AdviceGenerator(InstrumentationConfig config, @Nullable String pluginId) {
        this.config = config;
        this.pluginId = pluginId;
        int uniqueNum = counter.incrementAndGet();
        adviceInternalName = "org/glowroot/advicegen/GeneratedAdvice" + uniqueNum;
        if (config.isTraceEntryOrGreater()
                || !config.transactionNameTemplate().isEmpty()
                || !config.transactionUserTemplate().isEmpty()
                || !config.transactionCustomAttributeTemplates().isEmpty()) {
            // templates are used, so method meta is needed
            methodMetaInternalName = "org/glowroot/advicegen/GeneratedMethodMeta" + uniqueNum;
        } else {
            methodMetaInternalName = null;
        }
    }

    private LazyDefinedClass generate() throws Exception {
        LazyDefinedClass methodMetaClass = null;
        if (methodMetaInternalName != null) {
            methodMetaClass = generateMethodMetaClass(config);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        String[] interfaces = null;
        if (!config.enabledProperty().isEmpty()
                || !config.traceEntryEnabledProperty().isEmpty()) {
            interfaces = new String[] {"org/glowroot/plugin/api/PluginServices$ConfigListener"};
        }
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceInternalName, null, "java/lang/Object",
                interfaces);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        addIsEnabledMethod(cw);
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
        LazyDefinedClass.Builder builder = LazyDefinedClass.builder()
                .type(Type.getObjectType(adviceInternalName))
                .bytes(cw.toByteArray());
        if (methodMetaClass != null) {
            builder.addDependencies(methodMetaClass);
        }
        return builder.build();
    }

    private void addClassAnnotation(ClassWriter cw) {
        AnnotationVisitor annotationVisitor =
                cw.visitAnnotation("Lorg/glowroot/plugin/api/weaving/Pointcut;", true);
        annotationVisitor.visit("className", config.className());
        annotationVisitor.visit("declaringClassName", config.declaringClassName());
        annotationVisitor.visit("methodName", config.methodName());
        AnnotationVisitor arrayAnnotationVisitor =
                annotationVisitor.visitArray("methodParameterTypes");
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
        if (config.isTraceEntryOrGreater() && !config.traceEntryCaptureSelfNested()) {
            annotationVisitor.visit("ignoreSelfNested", true);
        }
        annotationVisitor.visitEnd();
    }

    private void addStaticFields(ClassWriter cw) {
        // some of these classes are generated before weaving is set up, so need to add
        // glowroot$advice$flow$outer$holder in same way as PointcutClassVisitor
        cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "glowroot$advice$flow$outer$holder",
                Type.getDescriptor(AdviceFlowOuterHolder.class), null, null)
                .visitEnd();
        cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "pluginServices",
                "Lorg/glowroot/plugin/api/PluginServices;", null, null)
                .visitEnd();
        if (config.isTimerOrGreater()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "timerName",
                    "Lorg/glowroot/plugin/api/TimerName;", null, null)
                    .visitEnd();
        }
        if (!config.enabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_FINAL, "enabled",
                    "Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;", null, null)
                    .visitEnd();
        }
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_FINAL, "entryEnabled",
                    "Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;", null, null)
                    .visitEnd();
        }
    }

    private void addStaticInitializer(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(AdviceFlowOuterHolder.class),
                "create", "()" + Type.getDescriptor(AdviceFlowOuterHolder.class), false);
        mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "glowroot$advice$flow$outer$holder",
                Type.getDescriptor(AdviceFlowOuterHolder.class));
        if (pluginId == null) {
            mv.visitInsn(ACONST_NULL);
        } else {
            mv.visitLdcInsn(pluginId);
        }
        mv.visitMethodInsn(INVOKESTATIC, HANDLE_CLASS_NAME, HANDLE_METHOD_NAME,
                "(Ljava/lang/String;)Lorg/glowroot/plugin/api/PluginServices;", false);
        mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/plugin/api/PluginServices;");
        if (config.isTimerOrGreater()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitLdcInsn(Type.getObjectType(adviceInternalName));
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "getTimerName", "(Ljava/lang/Class;)Lorg/glowroot/plugin/api/TimerName;",
                    false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "timerName",
                    "Lorg/glowroot/plugin/api/TimerName;");
        }
        if (!config.enabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitLdcInsn(config.enabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "getEnabledProperty",
                    "(Ljava/lang/String;)Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;",
                    false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "enabled",
                    "Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;");
        }
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitLdcInsn(config.traceEntryEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/plugin/api/PluginServices",
                    "getEnabledProperty",
                    "(Ljava/lang/String;)Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;",
                    false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "entryEnabled",
                    "Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addIsEnabledMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "isEnabled", "()Z", null, null);
        mv.visitAnnotation("Lorg/glowroot/plugin/api/weaving/IsEnabled;", true)
                .visitEnd();
        mv.visitCode();
        if (config.enabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices", "isEnabled",
                    "()Z", false);
            mv.visitInsn(IRETURN);
        } else {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "enabled",
                    "Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;");
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/plugin/api/PluginServices$BooleanProperty", "value", "()Z", true);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void addOnBeforeMethod(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/plugin/api/TraceEntry;");
        mv.visitCode();
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "entryEnabled",
                    "Lorg/glowroot/plugin/api/PluginServices$BooleanProperty;");
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "org/glowroot/plugin/api/PluginServices$BooleanProperty", "value", "()Z", true);
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            // entryEnabled is false, collect timer only
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "timerName",
                    "Lorg/glowroot/plugin/api/TimerName;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "startTimer",
                    "(Lorg/glowroot/plugin/api/TimerName;)Lorg/glowroot/plugin/api/Timer;",
                    false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/plugin/api/PluginServices;");
        if (config.isTransaction()) {
            String transactionType = config.transactionType();
            if (transactionType.isEmpty()) {
                mv.visitLdcInsn("<no transaction type provided>");
            } else {
                mv.visitLdcInsn(transactionType);
            }
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionNameTemplate",
                    "()Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/GenericMessageSupplier",
                    "create",
                    "(Lorg/glowroot/advicegen/MessageTemplate;Ljava/lang/Object;Ljava/lang/String;"
                            + "[Ljava/lang/Object;)Lorg/glowroot/advicegen/GenericMessageSupplier;",
                    false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
        }
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getMessageTemplate",
                "()Lorg/glowroot/advicegen/MessageTemplate;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC,
                "org/glowroot/advicegen/GenericMessageSupplier",
                "create",
                "(Lorg/glowroot/advicegen/MessageTemplate;Ljava/lang/Object;Ljava/lang/String;"
                        + "[Ljava/lang/Object;)Lorg/glowroot/advicegen/GenericMessageSupplier;",
                false);
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "timerName",
                "Lorg/glowroot/plugin/api/TimerName;");
        if (config.isTransaction()) {
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/plugin/api/PluginServices",
                    "startTransaction",
                    "(Ljava/lang/String;Ljava/lang/String;Lorg/glowroot/plugin/api/MessageSupplier;"
                            + "Lorg/glowroot/plugin/api/TimerName;)"
                            + "Lorg/glowroot/plugin/api/TraceEntry;",
                    false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/plugin/api/PluginServices",
                    "startTraceEntry",
                    "(Lorg/glowroot/plugin/api/MessageSupplier;Lorg/glowroot/plugin/api/TimerName;)"
                            + "Lorg/glowroot/plugin/api/TraceEntry;",
                    false);
        }
        addCodeForOptionalTraceAttributes(mv);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodTimerOnly(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/plugin/api/Timer;");
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/plugin/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "timerName",
                "Lorg/glowroot/plugin/api/TimerName;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices", "startTimer",
                "(Lorg/glowroot/plugin/api/TimerName;)Lorg/glowroot/plugin/api/Timer;", false);
        addCodeForOptionalTraceAttributes(mv);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodOther(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "V");
        mv.visitCode();
        addCodeForOptionalTraceAttributes(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private MethodVisitor visitOnBeforeMethod(ClassWriter cw, String returnInternalName) {
        String desc;
        if (methodMetaInternalName != null) {
            desc = "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;L"
                    + methodMetaInternalName + ";)" + returnInternalName;
        } else {
            desc = "()" + returnInternalName;
        }
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore", desc, null, null);
        mv.visitAnnotation("Lorg/glowroot/plugin/api/weaving/OnBefore;", true)
                .visitEnd();
        if (methodMetaInternalName != null) {
            mv.visitParameterAnnotation(0, "Lorg/glowroot/plugin/api/weaving/BindReceiver;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/plugin/api/weaving/BindMethodName;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(2, "Lorg/glowroot/plugin/api/weaving/BindParameterArray;",
                    true).visitEnd();
            mv.visitParameterAnnotation(3, "Lorg/glowroot/plugin/api/weaving/BindMethodMeta;", true)
                    .visitEnd();
        }
        return mv;
    }

    private void addCodeForOptionalTraceAttributes(MethodVisitor mv) {
        if (!config.transactionType().isEmpty() && !config.isTransaction()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitLdcInsn(config.transactionType());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "setTransactionType", "(Ljava/lang/String;)V", false);
        }
        if (!config.transactionNameTemplate().isEmpty() && !config.isTransaction()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionNameTemplate is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionNameTemplate",
                    "()Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/GenericMessageSupplier",
                    "create",
                    "(Lorg/glowroot/advicegen/MessageTemplate;Ljava/lang/Object;Ljava/lang/String;"
                            + "[Ljava/lang/Object;)Lorg/glowroot/advicegen/GenericMessageSupplier;",
                    false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "setTransactionName", "(Ljava/lang/String;)V", false);
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionUserTemplate is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionUserTemplate",
                    "()Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/GenericMessageSupplier",
                    "create",
                    "(Lorg/glowroot/advicegen/MessageTemplate;Ljava/lang/Object;Ljava/lang/String;"
                            + "[Ljava/lang/Object;)Lorg/glowroot/advicegen/GenericMessageSupplier;",
                    false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "setTransactionUser", "(Ljava/lang/String;)V", false);
        }
        int i = 0;
        for (String attrName : config.transactionCustomAttributeTemplates().keySet()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitLdcInsn(attrName);
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionCustomAttributeTemplates is
            // non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    methodMetaInternalName,
                    "getTransactionCustomAttributeTemplate" + i++,
                    "()Lorg/glowroot/advicegen/MessageTemplate;",
                    false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/GenericMessageSupplier",
                    "create",
                    "(Lorg/glowroot/advicegen/MessageTemplate;Ljava/lang/Object;Ljava/lang/String;"
                            + "[Ljava/lang/Object;)Lorg/glowroot/advicegen/GenericMessageSupplier;",
                    false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/plugin/api/PluginServices",
                    "addTransactionCustomAttribute",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    false);
        }
        Long slowTraceThresholdMillis = config.slowTraceThresholdMillis();
        if (slowTraceThresholdMillis != null) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/plugin/api/PluginServices;");
            mv.visitLdcInsn(slowTraceThresholdMillis);
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/plugin/api/PluginServices",
                    "setSlowTraceThreshold", "(JLjava/util/concurrent/TimeUnit;)V", false);

        }
    }

    private void addOnAfterMethodTimerOnly(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onAfter",
                "(Lorg/glowroot/plugin/api/Timer;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/plugin/api/weaving/OnAfter;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/plugin/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/Timer", "stop", "()V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnReturnMethod(ClassWriter cw) {
        boolean entryOrTimer = !config.traceEntryEnabledProperty().isEmpty();
        String travelerType =
                entryOrTimer ? "Ljava/lang/Object;" : "Lorg/glowroot/plugin/api/TraceEntry;";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                "(Lorg/glowroot/plugin/api/OptionalReturn;" + travelerType + ")V", null, null);
        mv.visitParameterAnnotation(0, "Lorg/glowroot/plugin/api/weaving/BindOptionalReturn;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/plugin/api/weaving/BindTraveler;", true)
                .visitEnd();
        int travelerParamIndex = 1;
        mv.visitAnnotation("Lorg/glowroot/plugin/api/weaving/OnReturn;", true)
                .visitEnd();
        mv.visitCode();
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            // TraceEntryImpl implements both TraceEntry and Timer so cannot check instanceof Timer
            // to differentiate here (but can check isntanceof TraceEntry)
            mv.visitTypeInsn(INSTANCEOF, "org/glowroot/plugin/api/TraceEntry");
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(CHECKCAST, "org/glowroot/plugin/api/Timer");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/Timer", "stop", "()V",
                    true);
            mv.visitInsn(RETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/OptionalReturn", "isVoid",
                "()Z", true);
        Label notVoidLabel = new Label();
        Label endIfLabel = new Label();
        mv.visitJumpInsn(IFEQ, notVoidLabel);
        mv.visitLdcInsn("void");
        mv.visitJumpInsn(GOTO, endIfLabel);
        mv.visitLabel(notVoidLabel);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/OptionalReturn", "getValue",
                "()Ljava/lang/Object;", true);
        mv.visitLabel(endIfLabel);
        mv.visitMethodInsn(INVOKESTATIC,
                "org/glowroot/advicegen/GenericMessageSupplier",
                "updateWithReturnValue",
                "(Lorg/glowroot/plugin/api/TraceEntry;Ljava/lang/Object;)V",
                false);
        mv.visitVarInsn(ALOAD, travelerParamIndex);
        Long stackTraceThresholdMillis = config.traceEntryStackThresholdMillis();
        if (stackTraceThresholdMillis == null) {
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/TraceEntry", "end", "()V",
                    true);
        } else {
            mv.visitLdcInsn(stackTraceThresholdMillis);
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/TraceEntry",
                    "endWithStackTrace", "(JLjava/util/concurrent/TimeUnit;)V", true);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnThrowMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onThrow",
                "(Ljava/lang/Throwable;Lorg/glowroot/plugin/api/TraceEntry;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/plugin/api/weaving/OnThrow;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/plugin/api/weaving/BindThrowable;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/plugin/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        if (!config.traceEntryEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, 1);
            Label l0 = new Label();
            mv.visitJumpInsn(IFNONNULL, l0);
            mv.visitInsn(RETURN);
            mv.visitLabel(l0);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/plugin/api/ErrorMessage", "from",
                "(Ljava/lang/Throwable;)Lorg/glowroot/plugin/api/ErrorMessage;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/plugin/api/TraceEntry", "endWithError",
                "(Lorg/glowroot/plugin/api/ErrorMessage;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private LazyDefinedClass generateMethodMetaClass(InstrumentationConfig config)
            throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, methodMetaInternalName, null, "java/lang/Object",
                null);
        cw.visitField(ACC_PRIVATE + ACC_FINAL, "messageTemplate",
                "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                .visitEnd();
        if (!config.transactionNameTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionNameTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                    .visitEnd();
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionUserTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                    .visitEnd();
        }
        for (int i = 0; i < config.transactionCustomAttributeTemplates().size(); i++) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionCustomAttributeTemplate" + i,
                    "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                    .visitEnd();
        }
        generateMethodMetaConstructor(cw);
        generateMethodMetaGetter(cw, "messageTemplate", "getMessageTemplate");
        if (!config.transactionNameTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionNameTemplate", "getTransactionNameTemplate");
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionUserTemplate", "getTransactionUserTemplate");
        }
        for (int i = 0; i < config.transactionCustomAttributeTemplates().size(); i++) {
            generateMethodMetaGetter(cw, "transactionCustomAttributeTemplate" + i,
                    "getTransactionCustomAttributeTemplate" + i);
        }
        cw.visitEnd();
        return LazyDefinedClass.builder()
                .type(Type.getObjectType(methodMetaInternalName))
                .bytes(cw.toByteArray())
                .build();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void generateMethodMetaConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)V",
                "(Ljava/lang/Class<*>;Ljava/lang/Class<*>;[Ljava/lang/Class<*>;)V", null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        if (config.isTraceEntryOrGreater()) {
            String traceEntryTemplate = config.traceEntryTemplate();
            if (traceEntryTemplate.isEmpty() && config.isTransaction()) {
                traceEntryTemplate = config.transactionNameTemplate();
            }
            if (traceEntryTemplate.isEmpty()) {
                mv.visitLdcInsn("<no message template provided>");
            } else {
                mv.visitLdcInsn(traceEntryTemplate);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/MessageTemplate",
                    "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;",
                    false);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "messageTemplate",
                "Lorg/glowroot/advicegen/MessageTemplate;");
        if (!config.transactionNameTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(config.transactionNameTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/MessageTemplate",
                    "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;",
                    false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionNameTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;");
        }
        if (!config.transactionUserTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(config.transactionUserTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/MessageTemplate",
                    "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;",
                    false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionUserTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;");
        }
        int i = 0;
        for (String attrTemplate : config.transactionCustomAttributeTemplates().values()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(attrTemplate);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/MessageTemplate",
                    "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;",
                    false);
            mv.visitFieldInsn(PUTFIELD,
                    methodMetaInternalName,
                    "transactionCustomAttributeTemplate" + i++,
                    "Lorg/glowroot/advicegen/MessageTemplate;");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void generateMethodMetaGetter(ClassWriter cw, String fieldName, String methodName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName,
                "()Lorg/glowroot/advicegen/MessageTemplate;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, methodMetaInternalName, fieldName,
                "Lorg/glowroot/advicegen/MessageTemplate;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}

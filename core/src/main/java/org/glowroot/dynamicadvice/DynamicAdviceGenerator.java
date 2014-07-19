/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.dynamicadvice;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.config.PointcutConfig;
import org.glowroot.jvm.ClassLoaders;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.TypeNames;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_VOLATILE;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_SAME;
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
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicAdviceGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DynamicAdviceGenerator.class);

    private static final boolean dumpBytecode =
            Boolean.getBoolean("glowroot.internal.bytecode.dump");

    private static final String HANDLE_CLASS_NAME = "org/glowroot/trace/PluginServicesRegistry";
    private static final String HANDLE_METHOD_NAME = "get";

    private static final AtomicInteger counter = new AtomicInteger();

    private final PointcutConfig pointcutConfig;
    @Nullable
    private final String pluginId;
    private final String adviceTypeName;
    private final String methodMetaName;

    public static ImmutableList<Advice> createAdvisors(
            ImmutableList<PointcutConfig> pointcutConfigs, @Nullable String pluginId) {
        List<Advice> advisors = Lists.newArrayList();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            try {
                Class<?> dynamicAdviceClass =
                        new DynamicAdviceGenerator(pointcutConfig, pluginId).generate();
                Pointcut pointcut = dynamicAdviceClass.getAnnotation(Pointcut.class);
                if (pointcut == null) {
                    logger.error("class was generated without @Pointcut annotation");
                    continue;
                }
                boolean reweavable = pluginId == null;
                Advice advice = Advice.from(pointcut, dynamicAdviceClass, reweavable);
                advisors.add(advice);
            } catch (ReflectiveException e) {
                logger.error("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (AdviceConstructionException e) {
                logger.error("error creating advice for pointcut config: {}", pointcutConfig, e);
            }
        }
        return ImmutableList.copyOf(advisors);
    }

    private DynamicAdviceGenerator(PointcutConfig pointcutConfig, @Nullable String pluginId) {
        this.pointcutConfig = pointcutConfig;
        this.pluginId = pluginId;
        int uniqueNum = counter.incrementAndGet();
        adviceTypeName = "org/glowroot/dynamicadvice/GeneratedAdvice" + uniqueNum;
        methodMetaName = "org/glowroot/dynamicadvice/GeneratedMethodMeta" + uniqueNum;
    }

    private Class<?> generate() throws ReflectiveException {
        if (pointcutConfig.isSpan() || pointcutConfig.isTrace()) {
            generateMethodMeta();
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        String[] interfaces = null;
        if (!pointcutConfig.getEnabledProperty().isEmpty()
                || !pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            interfaces = new String[] {"org/glowroot/api/PluginServices$ConfigListener"};
        }
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceTypeName, null, "java/lang/Object",
                interfaces);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        addIsEnabledMethod(cw);
        if (pointcutConfig.isSpan()) {
            addOnBeforeMethod(cw);
            addOnThrowMethod(cw);
            addOnReturnMethod(cw);
        } else {
            addOnBeforeMethodTraceMetricOnly(cw);
            addOnAfterMethodTraceMetricOnly(cw);
        }
        if (!pointcutConfig.getEnabledProperty().isEmpty()
                || !pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            addConstructor(cw);
            addOnChangeMethod(cw);
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        if (dumpBytecode) {
            ClassReader cr = new ClassReader(bytes);
            TraceClassVisitor tcv = new TraceClassVisitor(new PrintWriter(System.out));
            cr.accept(tcv, ClassReader.SKIP_FRAMES);
        }
        return ClassLoaders.defineClass(TypeNames.fromInternal(adviceTypeName), bytes,
                DynamicAdviceGenerator.class.getClassLoader());
    }

    private void addClassAnnotation(ClassWriter cw) {
        AnnotationVisitor annotationVisitor =
                cw.visitAnnotation("Lorg/glowroot/api/weaving/Pointcut;", true);
        annotationVisitor.visit("className", pointcutConfig.getClassName());
        annotationVisitor.visit("methodName", pointcutConfig.getMethodName());
        AnnotationVisitor arrayAnnotationVisitor =
                annotationVisitor.visitArray("methodParameterTypes");
        for (String methodParameterType : pointcutConfig.getMethodParameterTypes()) {
            arrayAnnotationVisitor.visit(null, methodParameterType);
        }
        arrayAnnotationVisitor.visitEnd();
        String traceMetric = pointcutConfig.getTraceMetric();
        if (traceMetric == null) {
            annotationVisitor.visit("traceMetric", "<no trace metric provided>");
        } else {
            annotationVisitor.visit("traceMetric", traceMetric);
        }
        if (pointcutConfig.isSpan() && !pointcutConfig.isCaptureSelfNested()) {
            annotationVisitor.visit("ignoreSelfNested", true);
        }
        annotationVisitor.visitEnd();
    }

    private void addStaticFields(ClassWriter cw) {
        cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "pluginServices",
                "Lorg/glowroot/api/PluginServices;", null, null)
                .visitEnd();
        cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "traceMetricName",
                "Lorg/glowroot/api/TraceMetricName;", null, null)
                .visitEnd();
        if (!pointcutConfig.getEnabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_VOLATILE, "enabled", "Z", null, null)
                    .visitEnd();
        }
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_VOLATILE, "spanEnabled", "Z", null, null)
                    .visitEnd();
        }
    }

    private void addStaticInitializer(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        if (pluginId == null) {
            mv.visitInsn(ACONST_NULL);
        } else {
            mv.visitLdcInsn(pluginId);
        }
        mv.visitMethodInsn(INVOKESTATIC, HANDLE_CLASS_NAME, HANDLE_METHOD_NAME,
                "(Ljava/lang/String;)Lorg/glowroot/api/PluginServices;", false);
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitLdcInsn(Type.getType("L" + adviceTypeName + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "getTraceMetricName",
                "(Ljava/lang/Class;)Lorg/glowroot/api/TraceMetricName;", false);
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "traceMetricName",
                "Lorg/glowroot/api/TraceMetricName;");
        if (!pointcutConfig.getEnabledProperty().isEmpty()
                || !pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitTypeInsn(NEW, adviceTypeName);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, adviceTypeName, "<init>", "()V", false);
            mv.visitVarInsn(ASTORE, 0);
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "registerConfigListener",
                    "(Lorg/glowroot/api/PluginServices$ConfigListener;)V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, adviceTypeName, "onChange", "()V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addIsEnabledMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "isEnabled", "()Z", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/IsEnabled;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "isEnabled", "()Z",
                false);
        if (!pointcutConfig.getEnabledProperty().isEmpty()) {
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "enabled", "Z");
            mv.visitJumpInsn(IFEQ, label);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ICONST_0);
        }
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;L" + methodMetaName
                        + ";)Lorg/glowroot/api/Span;",
                null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindReceiver;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindMethodName;", true)
                .visitEnd();
        mv.visitParameterAnnotation(2, "Lorg/glowroot/api/weaving/BindParameterArray;", true)
                .visitEnd();
        mv.visitParameterAnnotation(3, "Lorg/glowroot/api/weaving/BindMethodMeta;", true)
                .visitEnd();
        mv.visitCode();
        if (!pointcutConfig.getEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "enabled", "Z");
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "spanEnabled", "Z");
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            // spanEnabled is false, collect trace metric only
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "traceMetricName",
                    "Lorg/glowroot/api/TraceMetricName;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "startTraceMetric",
                    "(Lorg/glowroot/api/TraceMetricName;)Lorg/glowroot/api/TraceMetricTimer;",
                    false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (pointcutConfig.isTrace()) {
            String transactionType = pointcutConfig.getTransactionType();
            if (transactionType == null) {
                mv.visitLdcInsn("<no transaction type provided>");
            } else {
                mv.visitLdcInsn(transactionType);
            }
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaName, "getTransactionNameTemplate",
                    "()Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "create",
                    "(Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageSupplier;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
        }
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaName, "getMessageTemplate",
                "()Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC,
                "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "create",
                "(Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;"
                        + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageSupplier;", false);
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "traceMetricName",
                "Lorg/glowroot/api/TraceMetricName;");
        if (pointcutConfig.isTrace()) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startTrace",
                    "(Ljava/lang/String;Ljava/lang/String;Lorg/glowroot/api/MessageSupplier;"
                            + "Lorg/glowroot/api/TraceMetricName;)Lorg/glowroot/api/Span;", false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startSpan",
                    "(Lorg/glowroot/api/MessageSupplier;Lorg/glowroot/api/TraceMetricName;)"
                            + "Lorg/glowroot/api/Span;", false);
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodTraceMetricOnly(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "()Lorg/glowroot/api/TraceMetricTimer;", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "traceMetricName",
                "Lorg/glowroot/api/TraceMetricName;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startTraceMetric",
                "(Lorg/glowroot/api/TraceMetricName;)Lorg/glowroot/api/TraceMetricTimer;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnAfterMethodTraceMetricOnly(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onAfter",
                "(Lorg/glowroot/api/TraceMetricTimer;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnAfter;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TraceMetricTimer", "stop", "()V",
                true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnReturnMethod(ClassWriter cw) {
        boolean spanOrTimer = !pointcutConfig.getSpanEnabledProperty().isEmpty();
        String travelerType = spanOrTimer ? "Ljava/lang/Object;" : "Lorg/glowroot/api/Span;";
        MethodVisitor mv;
        int travelerParamIndex;
        if (pointcutConfig.getMethodReturnType().isEmpty()) {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lorg/glowroot/api/OptionalReturn;" + travelerType + ")V", null, null);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindOptionalReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
            travelerParamIndex = 1;
        } else if (pointcutConfig.getMethodReturnType().equals("void")) {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn", "(" + travelerType + ")V",
                    null, null);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
            travelerParamIndex = 0;
        } else {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn", "(Ljava/lang/Object;"
                    + travelerType + ")V", null, null);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
            travelerParamIndex = 1;
        }
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnReturn;", true)
                .visitEnd();
        mv.visitCode();
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(INSTANCEOF, "org/glowroot/api/TraceMetricTimer");
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(CHECKCAST, "org/glowroot/api/TraceMetricTimer");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TraceMetricTimer", "stop", "()V",
                    true);
            mv.visitInsn(RETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        if (pointcutConfig.getMethodReturnType().isEmpty()) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/OptionalReturn", "isVoid", "()Z",
                    true);
            Label notVoidLabel = new Label();
            Label endIfLabel = new Label();
            mv.visitJumpInsn(IFEQ, notVoidLabel);
            mv.visitLdcInsn("void");
            mv.visitJumpInsn(GOTO, endIfLabel);
            mv.visitLabel(notVoidLabel);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/OptionalReturn", "getValue",
                    "()Ljava/lang/Object;", true);
            mv.visitLabel(endIfLabel);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/Span;Ljava/lang/Object;)V", false);
        } else if (!pointcutConfig.getMethodReturnType().equals("void")) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/Span;Ljava/lang/Object;)V", false);
        }
        mv.visitVarInsn(ALOAD, travelerParamIndex);
        Long stackTraceThresholdMillis = pointcutConfig.getStackTraceThresholdMillis();
        if (stackTraceThresholdMillis == null) {
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "end",
                    "()Lorg/glowroot/api/CompletedSpan;", true);
        } else {
            mv.visitLdcInsn(stackTraceThresholdMillis);
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "endWithStackTrace",
                    "(JLjava/util/concurrent/TimeUnit;)Lorg/glowroot/api/CompletedSpan;", true);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnThrowMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onThrow",
                "(Ljava/lang/Throwable;Lorg/glowroot/api/Span;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnThrow;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindThrowable;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, 1);
            Label l0 = new Label();
            mv.visitJumpInsn(IFNONNULL, l0);
            mv.visitInsn(RETURN);
            mv.visitLabel(l0);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/api/ErrorMessage", "from",
                "(Ljava/lang/Throwable;)Lorg/glowroot/api/ErrorMessage;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "endWithError",
                "(Lorg/glowroot/api/ErrorMessage;)Lorg/glowroot/api/CompletedSpan;", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnChangeMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "onChange", "()V", null, null);
        mv.visitCode();
        if (!pointcutConfig.getEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(pointcutConfig.getEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "getBooleanProperty", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "enabled", "Z");
        }
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(pointcutConfig.getSpanEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "getBooleanProperty", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "spanEnabled", "Z");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateMethodMeta() throws ReflectiveException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, methodMetaName, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE + ACC_FINAL, "messageTemplate",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                .visitEnd();
        cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionNameTemplate",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                .visitEnd();
        generateMethodMetaConstructor(cw);
        generateMethodMetaGetter(cw, "messageTemplate", "getMessageTemplate");
        generateMethodMetaGetter(cw, "transactionNameTemplate", "getTransactionNameTemplate");
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        ClassLoaders.defineClass(TypeNames.fromInternal(methodMetaName), bytes,
                DynamicAdviceGenerator.class.getClassLoader());
    }

    private void generateMethodMetaConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)V",
                "(Ljava/lang/Class<*>;Ljava/lang/Class<*>;[Ljava/lang/Class<*>;)V", null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        if (pointcutConfig.isSpan()) {
            String messageTemplate = pointcutConfig.getMessageTemplate();
            if (messageTemplate == null) {
                mv.visitLdcInsn("<no message template provided>");
            } else {
                mv.visitLdcInsn(messageTemplate);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(PUTFIELD, methodMetaName, "messageTemplate",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        mv.visitVarInsn(ALOAD, 0);
        if (pointcutConfig.isTrace()) {
            String transactionNameTemplate = pointcutConfig.getTransactionNameTemplate();
            if (transactionNameTemplate.isEmpty()) {
                mv.visitLdcInsn("<no transaction name template provided>");
            } else {
                mv.visitLdcInsn(transactionNameTemplate);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(PUTFIELD, methodMetaName, "transactionNameTemplate",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateMethodMetaGetter(ClassWriter cw, String fieldName, String methodName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName,
                "()Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, methodMetaName, fieldName,
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}

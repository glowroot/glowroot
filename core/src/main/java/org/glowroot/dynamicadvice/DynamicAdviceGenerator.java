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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.config.PointcutConfig;
import org.glowroot.jvm.ClassLoaders;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.TypeNames;

import static org.glowroot.common.Nullness.castNonNull;
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
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicAdviceGenerator {

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(DynamicAdviceGenerator.class);

    private static final AtomicInteger counter = new AtomicInteger();

    private final PointcutConfig pointcutConfig;
    @Nullable
    private final String pluginId;
    private final String adviceTypeName;

    public static ImmutableList<Advice> getAdvisors(@ReadOnly List<PointcutConfig> pointcutConfigs,
            @Nullable String pluginId) {
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            try {
                Class<?> dynamicAdviceClass =
                        new DynamicAdviceGenerator(pointcutConfig, pluginId).generate();
                Pointcut pointcut = dynamicAdviceClass.getAnnotation(Pointcut.class);
                if (pointcut == null) {
                    logger.error("class was generated without @Pointcut annotation");
                    continue;
                }
                // only adhoc pointcuts (pluginId is null) are reweavable
                Advice advice = Advice.from(pointcut, dynamicAdviceClass, pluginId == null);
                advisors.add(advice);
            } catch (ReflectiveException e) {
                logger.error("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (AdviceConstructionException e) {
                logger.error("error creating advice for pointcut config: {}", pointcutConfig, e);
            }
        }
        return advisors.build();
    }

    private DynamicAdviceGenerator(PointcutConfig pointcutConfig, @Nullable String pluginId) {
        this.pointcutConfig = pointcutConfig;
        this.pluginId = pluginId;
        adviceTypeName = "org/glowroot/dynamicadvice/GeneratedAdvice" + counter.incrementAndGet();
    }

    public Class<?> generate() throws ReflectiveException {
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
            addOnBeforeMethodMetricOnly(cw);
            addOnAfterMethodMetricOnly(cw);
        }
        if (!pointcutConfig.getEnabledProperty().isEmpty()
                || !pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            addConstructor(cw);
            addOnChangeMethod(cw);
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        return ClassLoaders.defineClass(TypeNames.fromInternal(adviceTypeName), bytes);
    }

    private void addClassAnnotation(ClassVisitor cv) {
        AnnotationVisitor annotationVisitor =
                cv.visitAnnotation("Lorg/glowroot/api/weaving/Pointcut;", true);
        annotationVisitor.visit("typeName", pointcutConfig.getTypeName());
        annotationVisitor.visit("methodName", pointcutConfig.getMethodName());
        AnnotationVisitor argVisitor = annotationVisitor.visitArray("methodArgs");
        for (String methodArgTypeName : pointcutConfig.getMethodArgTypeNames()) {
            argVisitor.visit(null, methodArgTypeName);
        }
        argVisitor.visitEnd();
        String metricName = pointcutConfig.getMetricName();
        if (metricName == null) {
            annotationVisitor.visit("metricName", "<no metric name provided>");
        } else {
            annotationVisitor.visit("metricName", metricName);
        }
        if (pointcutConfig.isSpan() && pointcutConfig.isSpanIgnoreSameNested()) {
            annotationVisitor.visit("ignoreSameNested", true);
        }
        annotationVisitor.visitEnd();
    }

    private void addStaticFields(ClassVisitor cv) {
        cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "pluginServices",
                "Lorg/glowroot/api/PluginServices;", null, null)
                .visitEnd();
        cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "metric",
                "Lorg/glowroot/api/MetricName;", null, null)
                .visitEnd();
        if (pointcutConfig.isSpan()) {
            cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "spanText",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                    .visitEnd();
        }
        if (pointcutConfig.isTrace()) {
            cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "transactionName",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                    .visitEnd();
        }
        if (!pointcutConfig.getEnabledProperty().isEmpty()) {
            cv.visitField(ACC_PRIVATE + ACC_STATIC + ACC_VOLATILE, "enabled", "Z", null, null)
                    .visitEnd();
        }
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            cv.visitField(ACC_PRIVATE + ACC_STATIC + ACC_VOLATILE, "spanEnabled", "Z", null, null)
                    .visitEnd();
        }
    }

    private void addStaticInitializer(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        castNonNull(mv);
        mv.visitCode();
        if (pluginId == null) {
            mv.visitInsn(ACONST_NULL);
        } else {
            mv.visitLdcInsn(pluginId);
        }
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/MainEntryPoint", "getPluginServices",
                "(Ljava/lang/String;)Lorg/glowroot/api/PluginServices;", false);
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitLdcInsn(Type.getType("L" + adviceTypeName + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "getMetricName",
                "(Ljava/lang/Class;)Lorg/glowroot/api/MetricName;", false);
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "metric", "Lorg/glowroot/api/MetricName;");
        if (pointcutConfig.isSpan()) {
            String spanText = pointcutConfig.getSpanText();
            if (spanText == null) {
                mv.visitLdcInsn("<no span text provided>");
            } else {
                mv.visitLdcInsn(spanText);
            }
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "spanText",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        }
        if (pointcutConfig.isTrace()) {
            String transactionName = pointcutConfig.getTransactionName();
            if (Strings.isNullOrEmpty(transactionName)) {
                mv.visitLdcInsn("<no transaction name provided>");
            } else {
                mv.visitLdcInsn(transactionName);
            }
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "transactionName",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        }
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

    private void addIsEnabledMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "isEnabled", "()Z", null, null);
        castNonNull(mv);
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

    private void addOnBeforeMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Lorg/glowroot/api/Span;",
                null, null);
        castNonNull(mv);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindReceiver;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindMethodName;", true)
                .visitEnd();
        mv.visitParameterAnnotation(2, "Lorg/glowroot/api/weaving/BindMethodArgArray;", true)
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
            // spanEnabled is false, collect metric only
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "metric", "Lorg/glowroot/api/MetricName;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "startMetricTimer",
                    "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/MetricTimer;", false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (pointcutConfig.isTrace()) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "transactionName",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
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
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "spanText",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC,
                "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "create",
                "(Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;"
                        + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageSupplier;", false);
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "metric", "Lorg/glowroot/api/MetricName;");
        if (pointcutConfig.isTrace()) {
            String methodName = pointcutConfig.isBackground() ? "startBackgroundTrace"
                    : "startTrace";
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", methodName,
                    "(Ljava/lang/String;Lorg/glowroot/api/MessageSupplier;"
                            + "Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/Span;", false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startSpan",
                    "(Lorg/glowroot/api/MessageSupplier;Lorg/glowroot/api/MetricName;)"
                            + "Lorg/glowroot/api/Span;", false);
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
    private void addOnBeforeMethodMetricOnly(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "()Lorg/glowroot/api/MetricTimer;", null, null);
        castNonNull(mv);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "metric", "Lorg/glowroot/api/MetricName;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startMetricTimer",
                "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/MetricTimer;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnAfterMethodMetricOnly(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onAfter",
                "(Lorg/glowroot/api/MetricTimer;)V", null, null);
        castNonNull(mv);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnAfter;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/MetricTimer", "stop", "()V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnReturnMethod(ClassVisitor cv) {
        boolean spanOrTimer = !pointcutConfig.getSpanEnabledProperty().isEmpty();
        String travelerType = spanOrTimer ? "Ljava/lang/Object;" : "Lorg/glowroot/api/Span;";
        MethodVisitor mv;
        int travelerParamIndex;
        if (pointcutConfig.getMethodReturnTypeName().isEmpty()) {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lorg/glowroot/api/OptionalReturn;" + travelerType + ")V", null, null);
            castNonNull(mv);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindOptionalReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
            travelerParamIndex = 1;
        } else if (pointcutConfig.getMethodReturnTypeName().equals("void")) {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn", "(" + travelerType + ")V",
                    null, null);
            castNonNull(mv);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
            travelerParamIndex = 0;
        } else {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn", "(Ljava/lang/Object;"
                    + travelerType + ")V", null, null);
            castNonNull(mv);
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
            mv.visitTypeInsn(INSTANCEOF, "org/glowroot/api/MetricTimer");
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(CHECKCAST, "org/glowroot/api/MetricTimer");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/MetricTimer", "stop", "()V",
                    true);
            mv.visitInsn(RETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        if (pointcutConfig.getMethodReturnTypeName().isEmpty()) {
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
        } else if (!pointcutConfig.getMethodReturnTypeName().equals("void")) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/Span;Ljava/lang/Object;)V", false);
        }
        mv.visitVarInsn(ALOAD, travelerParamIndex);
        Long spanStackTraceThresholdMillis = pointcutConfig.getSpanStackTraceThresholdMillis();
        if (spanStackTraceThresholdMillis == null) {
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "end",
                    "()Lorg/glowroot/api/CompletedSpan;", true);
        } else {
            mv.visitLdcInsn(spanStackTraceThresholdMillis);
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "endWithStackTrace",
                    "(JLjava/util/concurrent/TimeUnit;)Lorg/glowroot/api/CompletedSpan;", true);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnThrowMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onThrow",
                "(Ljava/lang/Throwable;Lorg/glowroot/api/Span;)V", null, null);
        castNonNull(mv);
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

    private void addConstructor(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        castNonNull(mv);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnChangeMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "onChange", "()V", null, null);
        castNonNull(mv);
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
}

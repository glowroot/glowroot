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
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
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
    private final String adviceTypeName;

    public static ImmutableList<Advice> getAdvisors(@ReadOnly List<PointcutConfig> pointcutConfigs,
            boolean reweavable) {
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            try {
                Class<?> dynamicAdviceClass = new DynamicAdviceGenerator(pointcutConfig).generate();
                Pointcut pointcut = dynamicAdviceClass.getAnnotation(Pointcut.class);
                if (pointcut == null) {
                    logger.error("class was generated without @Pointcut annotation");
                    continue;
                }
                Advice advice = Advice.from(pointcut, dynamicAdviceClass, reweavable);
                advisors.add(advice);
            } catch (ReflectiveException e) {
                logger.error("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (AdviceConstructionException e) {
                logger.error("error creating advice for pointcut config: {}", pointcutConfig, e);
            }
        }
        return advisors.build();
    }

    private DynamicAdviceGenerator(PointcutConfig pointcutConfig) {
        this.pointcutConfig = pointcutConfig;
        adviceTypeName = "org/glowroot/dynamicadvice/GeneratedAdvice" + counter.incrementAndGet();
    }

    public Class<?> generate() throws ReflectiveException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceTypeName, null, "java/lang/Object", null);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        addIsEnabledMethod(cw);
        if (pointcutConfig.isSpan()) {
            addOnBeforeMethod(cw, pointcutConfig.isTrace());
            addOnThrowMethod(cw);
            addOnReturnMethod(cw);
        } else {
            addOnBeforeMethodMetricOnly(cw);
            addOnAfterMethodMetricOnly(cw);
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
        // all pointcut config advice is set to captureNested=false
        annotationVisitor.visit("captureNested", false);
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
            cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "traceGrouping",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                    .visitEnd();
        }
    }

    private void addStaticInitializer(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        castNonNull(mv);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/MainEntryPoint", "getPluginServices",
                "(Ljava/lang/String;)Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitLdcInsn(Type.getType("L" + adviceTypeName + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "getMetricName",
                "(Ljava/lang/Class;)Lorg/glowroot/api/MetricName;");
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
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "spanText",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        }
        if (pointcutConfig.isTrace()) {
            String traceGrouping = pointcutConfig.getTraceGrouping();
            if (Strings.isNullOrEmpty(traceGrouping)) {
                mv.visitLdcInsn("<no trace grouping provided>");
            } else {
                mv.visitLdcInsn(traceGrouping);
            }
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "traceGrouping",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
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
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "isEnabled", "()Z");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethod(ClassVisitor cv, boolean startTrace) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Lorg/glowroot/api/Span;",
                null, null);
        castNonNull(mv);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTarget;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindMethodName;", true)
                .visitEnd();
        mv.visitParameterAnnotation(2, "Lorg/glowroot/api/weaving/BindMethodArgArray;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (startTrace) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "traceGrouping",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "create",
                    "(Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageSupplier;");
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;");
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
                        + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageSupplier;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "metric", "Lorg/glowroot/api/MetricName;");
        if (startTrace) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startTrace",
                    "(Ljava/lang/String;Lorg/glowroot/api/MessageSupplier;"
                            + "Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/Span;");
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startSpan",
                    "(Lorg/glowroot/api/MessageSupplier;Lorg/glowroot/api/MetricName;)"
                            + "Lorg/glowroot/api/Span;");
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
                "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/MetricTimer;");
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
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/MetricTimer", "stop", "()V");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnReturnMethod(ClassVisitor cv) {
        MethodVisitor mv;
        if (pointcutConfig.getMethodReturnTypeName().equals("")) {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lorg/glowroot/api/OptionalReturn;Lorg/glowroot/api/Span;)V", null, null);
            castNonNull(mv);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindOptionalReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
        } else if (pointcutConfig.getMethodReturnTypeName().equals("void")) {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lorg/glowroot/api/Span;)V", null, null);
            castNonNull(mv);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
        } else {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Ljava/lang/Object;Lorg/glowroot/api/Span;)V", null, null);
            castNonNull(mv);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
        }
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnReturn;", true)
                .visitEnd();
        mv.visitCode();
        if (pointcutConfig.getMethodReturnTypeName().equals("")) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/OptionalReturn", "isVoid", "()Z");
            Label notVoidLabel = new Label();
            Label endIfLabel = new Label();
            mv.visitJumpInsn(IFEQ, notVoidLabel);
            mv.visitLdcInsn("void");
            mv.visitJumpInsn(GOTO, endIfLabel);
            mv.visitLabel(notVoidLabel);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/OptionalReturn", "getValue",
                    "()Ljava/lang/Object;");
            mv.visitLabel(endIfLabel);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/Span;Ljava/lang/Object;)V");
            mv.visitVarInsn(ALOAD, 1);
        } else if (pointcutConfig.getMethodReturnTypeName().equals("void")) {
            mv.visitVarInsn(ALOAD, 0);
        } else {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/Span;Ljava/lang/Object;)V");
            mv.visitVarInsn(ALOAD, 1);
        }
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "end",
                "()Lorg/glowroot/api/CompletedSpan;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void addOnThrowMethod(ClassVisitor cv) {
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
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/api/ErrorMessage", "from",
                "(Ljava/lang/Throwable;)Lorg/glowroot/api/ErrorMessage;");
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/Span", "endWithError",
                "(Lorg/glowroot/api/ErrorMessage;)Lorg/glowroot/api/CompletedSpan;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

    }
}

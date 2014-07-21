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
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
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
import org.glowroot.config.PointcutConfig.AdviceKind;
import org.glowroot.jvm.ClassLoaders;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.ClassNames;

import static com.google.common.base.Preconditions.checkNotNull;
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
    private final String adviceInternalName;
    @Nullable
    private final String methodMetaInternalName;

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
        adviceInternalName = "org/glowroot/dynamicadvice/GeneratedAdvice" + uniqueNum;
        if (pointcutConfig.isSpanOrGreater()
                || !pointcutConfig.getTransactionNameTemplate().isEmpty()
                || !pointcutConfig.getTraceUserTemplate().isEmpty()
                || !pointcutConfig.getTraceCustomAttributeTemplates().isEmpty()) {
            // templates are used, so method meta is needed
            methodMetaInternalName = "org/glowroot/dynamicadvice/GeneratedMethodMeta" + uniqueNum;
        } else {
            methodMetaInternalName = null;
        }
    }

    private Class<?> generate() throws ReflectiveException {
        if (methodMetaInternalName != null) {
            generateMethodMetaClass(pointcutConfig);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        String[] interfaces = null;
        if (!pointcutConfig.getEnabledProperty().isEmpty()
                || !pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            interfaces = new String[] {"org/glowroot/api/PluginServices$ConfigListener"};
        }
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceInternalName, null, "java/lang/Object",
                interfaces);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        addIsEnabledMethod(cw);
        if (pointcutConfig.isSpanOrGreater()) {
            // methodMetaInternalName is non-null when span or greater
            checkNotNull(methodMetaInternalName);
            addOnBeforeMethod(cw);
            addOnThrowMethod(cw);
            addOnReturnMethod(cw);
        } else if (pointcutConfig.getAdviceKind() == AdviceKind.METRIC) {
            addOnBeforeMethodMetricOnly(cw);
            addOnAfterMethodMetricOnly(cw);
        } else {
            addOnBeforeMethodOther(cw);
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
        return ClassLoaders.defineClass(ClassNames.fromInternalName(adviceInternalName), bytes,
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
        String metricName = pointcutConfig.getMetricName();
        if (pointcutConfig.isMetricOrGreater()) {
            if (metricName == null) {
                annotationVisitor.visit("metricName", "<no metric name provided>");
            } else {
                annotationVisitor.visit("metricName", metricName);
            }
        }
        if (pointcutConfig.isSpanOrGreater() && !pointcutConfig.isCaptureSelfNested()) {
            annotationVisitor.visit("ignoreSelfNested", true);
        }
        annotationVisitor.visitEnd();
    }

    private void addStaticFields(ClassWriter cw) {
        cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "pluginServices",
                "Lorg/glowroot/api/PluginServices;", null, null)
                .visitEnd();
        if (pointcutConfig.isMetricOrGreater()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "metricName",
                    "Lorg/glowroot/api/MetricName;", null, null)
                    .visitEnd();
        }
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
        mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (pointcutConfig.isMetricOrGreater()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(Type.getType("L" + adviceInternalName + ";"));
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "getMetricName",
                    "(Ljava/lang/Class;)Lorg/glowroot/api/MetricName;", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "metricName",
                    "Lorg/glowroot/api/MetricName;");
        }
        if (!pointcutConfig.getEnabledProperty().isEmpty()
                || !pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitTypeInsn(NEW, adviceInternalName);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, adviceInternalName, "<init>", "()V", false);
            mv.visitVarInsn(ASTORE, 0);
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "registerConfigListener",
                    "(Lorg/glowroot/api/PluginServices$ConfigListener;)V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, adviceInternalName, "onChange", "()V", false);
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
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "isEnabled", "()Z",
                false);
        if (pointcutConfig.getEnabledProperty().isEmpty()) {
            mv.visitInsn(IRETURN);
        } else {
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "enabled", "Z");
            mv.visitJumpInsn(IFEQ, label);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void addOnBeforeMethod(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/api/Span;");
        mv.visitCode();
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "spanEnabled", "Z");
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            // spanEnabled is false, collect metric only
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "metricName",
                    "Lorg/glowroot/api/MetricName;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "startMetric",
                    "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/MetricTimer;",
                    false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (pointcutConfig.isTrace()) {
            String transactionType = pointcutConfig.getTransactionType();
            if (transactionType == null) {
                mv.visitLdcInsn("<no transaction type provided>");
            } else {
                mv.visitLdcInsn(transactionType);
            }
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionNameTemplate",
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
        mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getMessageTemplate",
                "()Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC,
                "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "create",
                "(Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;"
                        + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageSupplier;", false);
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "metricName",
                "Lorg/glowroot/api/MetricName;");
        if (pointcutConfig.isTrace()) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startTrace",
                    "(Ljava/lang/String;Ljava/lang/String;Lorg/glowroot/api/MessageSupplier;"
                            + "Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/Span;", false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startSpan",
                    "(Lorg/glowroot/api/MessageSupplier;Lorg/glowroot/api/MetricName;)"
                            + "Lorg/glowroot/api/Span;", false);
        }
        addCodeForOptionalTraceAttributes(mv);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodMetricOnly(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/api/MetricTimer;");
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "metricName",
                "Lorg/glowroot/api/MetricName;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startMetric",
                "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/MetricTimer;", false);
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
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnBefore;", true)
                .visitEnd();
        if (methodMetaInternalName != null) {
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindReceiver;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindMethodName;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(2, "Lorg/glowroot/api/weaving/BindParameterArray;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(3, "Lorg/glowroot/api/weaving/BindMethodMeta;", true)
                    .visitEnd();
        }
        return mv;
    }

    private void addCodeForOptionalTraceAttributes(MethodVisitor mv) {
        if (!pointcutConfig.getTransactionType().isEmpty() && !pointcutConfig.isTrace()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(pointcutConfig.getTransactionType());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTransactionType", "(Ljava/lang/String;)V", false);
        }
        if (!pointcutConfig.getTransactionNameTemplate().isEmpty() && !pointcutConfig.isTrace()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionNameTemplate is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionNameTemplate",
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
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "getMessageText",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTransactionName", "(Ljava/lang/String;)V", false);
        }
        if (!pointcutConfig.getTraceUserTemplate().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when traceUserTemplate is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTraceUserTemplate",
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
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "getMessageText",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "setTraceUser",
                    "(Ljava/lang/String;)V", false);
        }
        int i = 0;
        for (String attrName : pointcutConfig.getTraceCustomAttributeTemplates().keySet()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(attrName);
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when traceCustomAttributeTemplates is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName,
                    "getTraceCustomAttributeTemplate" + i++,
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
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageSupplier", "getMessageText",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTraceCustomAttribute", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        }
        Long traceStoreThresholdMillis = pointcutConfig.getTraceStoreThresholdMillis();
        if (traceStoreThresholdMillis != null) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(traceStoreThresholdMillis);
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTraceStoreThreshold", "(JLjava/util/concurrent/TimeUnit;)V", false);

        }
    }

    private void addOnAfterMethodMetricOnly(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onAfter",
                "(Lorg/glowroot/api/MetricTimer;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnAfter;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/MetricTimer", "stop", "()V",
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
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(pointcutConfig.getEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "getBooleanProperty", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "enabled", "Z");
        }
        if (!pointcutConfig.getSpanEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(pointcutConfig.getSpanEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "getBooleanProperty", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "spanEnabled", "Z");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void generateMethodMetaClass(PointcutConfig pointcutConfig) throws ReflectiveException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, methodMetaInternalName, null, "java/lang/Object",
                null);
        cw.visitField(ACC_PRIVATE + ACC_FINAL, "messageTemplate",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                .visitEnd();
        if (!pointcutConfig.getTransactionNameTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionNameTemplate",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                    .visitEnd();
        }
        if (!pointcutConfig.getTraceUserTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "traceUserTemplate",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                    .visitEnd();
        }
        for (int i = 0; i < pointcutConfig.getTraceCustomAttributeTemplates().size(); i++) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "traceCustomAttributeTemplate" + i,
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null)
                    .visitEnd();
        }
        generateMethodMetaConstructor(cw);
        generateMethodMetaGetter(cw, "messageTemplate", "getMessageTemplate");
        if (!pointcutConfig.getTransactionNameTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionNameTemplate", "getTransactionNameTemplate");
        }
        if (!pointcutConfig.getTraceUserTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "traceUserTemplate", "getTraceUserTemplate");
        }
        for (int i = 0; i < pointcutConfig.getTraceCustomAttributeTemplates().size(); i++) {
            generateMethodMetaGetter(cw, "traceCustomAttributeTemplate" + i,
                    "getTraceCustomAttributeTemplate" + i);
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        ClassLoaders.defineClass(ClassNames.fromInternalName(methodMetaInternalName), bytes,
                DynamicAdviceGenerator.class.getClassLoader());
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
        if (pointcutConfig.isSpanOrGreater()) {
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
        mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "messageTemplate",
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        if (!pointcutConfig.getTransactionNameTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(pointcutConfig.getTransactionNameTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionNameTemplate",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        }
        if (!pointcutConfig.getTraceUserTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(pointcutConfig.getTraceUserTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "traceUserTemplate",
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        }
        int i = 0;
        for (String attrTemplate : pointcutConfig.getTraceCustomAttributeTemplates().values()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(attrTemplate);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/dynamicadvice/DynamicAdviceMessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName,
                    "traceCustomAttributeTemplate" + i++,
                    "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private void generateMethodMetaGetter(ClassWriter cw, String fieldName, String methodName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName,
                "()Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, methodMetaInternalName, fieldName,
                "Lorg/glowroot/dynamicadvice/DynamicAdviceMessageTemplate;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}

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
package org.glowroot.advicegen;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
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

import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.config.CapturePoint;
import org.glowroot.config.CapturePoint.CaptureKind;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.AdviceFlowOuterHolder;
import org.glowroot.weaving.LazyDefinedClass;

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

public class AdviceGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdviceGenerator.class);

    private static final boolean dumpBytecode =
            Boolean.getBoolean("glowroot.internal.bytecode.dump");

    private static final String HANDLE_CLASS_NAME =
            "org/glowroot/transaction/PluginServicesRegistry";
    private static final String HANDLE_METHOD_NAME = "get";

    private static final AtomicInteger counter = new AtomicInteger();

    private final CapturePoint capturePoint;
    @Nullable
    private final String pluginId;
    private final String adviceInternalName;
    @Nullable
    private final String methodMetaInternalName;

    public static ImmutableMap<Advice, LazyDefinedClass> createAdvisors(
            ImmutableList<CapturePoint> capturePoints, @Nullable String pluginId) {
        Map<Advice, LazyDefinedClass> advisors = Maps.newHashMap();
        for (CapturePoint capturePoint : capturePoints) {
            validatePointcut(capturePoint);
            try {
                LazyDefinedClass lazyAdviceClass =
                        new AdviceGenerator(capturePoint, pluginId).generate();
                boolean reweavable = pluginId == null;
                Advice advice = Advice.from(lazyAdviceClass, reweavable);
                advisors.put(advice, lazyAdviceClass);
            } catch (ReflectiveException e) {
                logger.error("error creating advice for advice config: {}", capturePoint, e);
            } catch (AdviceConstructionException e) {
                logger.error("error creating advice for advice config: {}", capturePoint, e);
            }
        }
        return ImmutableMap.copyOf(advisors);
    }

    private static void validatePointcut(CapturePoint capturePoint) {
        if (capturePoint.isTransaction() && capturePoint.getTransactionType().isEmpty()) {
            // TODO complete
        }
    }

    private AdviceGenerator(CapturePoint capturePoint, @Nullable String pluginId) {
        this.capturePoint = capturePoint;
        this.pluginId = pluginId;
        int uniqueNum = counter.incrementAndGet();
        adviceInternalName = "org/glowroot/advicegen/GeneratedAdvice" + uniqueNum;
        if (capturePoint.isTraceEntryOrGreater()
                || !capturePoint.getTransactionNameTemplate().isEmpty()
                || !capturePoint.getTransactionUserTemplate().isEmpty()
                || !capturePoint.getTransactionCustomAttributeTemplates().isEmpty()) {
            // templates are used, so method meta is needed
            methodMetaInternalName = "org/glowroot/advicegen/GeneratedMethodMeta" + uniqueNum;
        } else {
            methodMetaInternalName = null;
        }
    }

    private LazyDefinedClass generate() throws ReflectiveException {
        LazyDefinedClass methodMetaClass = null;
        if (methodMetaInternalName != null) {
            methodMetaClass = generateMethodMetaClass(capturePoint);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        String[] interfaces = null;
        if (!capturePoint.getEnabledProperty().isEmpty()
                || !capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
            interfaces = new String[] {"org/glowroot/api/PluginServices$ConfigListener"};
        }
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceInternalName, null, "java/lang/Object",
                interfaces);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        addIsEnabledMethod(cw);
        if (capturePoint.isTraceEntryOrGreater()) {
            // methodMetaInternalName is non-null when entry or greater
            checkNotNull(methodMetaInternalName);
            addOnBeforeMethod(cw);
            addOnThrowMethod(cw);
            addOnReturnMethod(cw);
        } else if (capturePoint.getCaptureKind() == CaptureKind.METRIC) {
            addOnBeforeMethodMetricOnly(cw);
            addOnAfterMethodMetricOnly(cw);
        } else {
            addOnBeforeMethodOther(cw);
        }
        if (!capturePoint.getEnabledProperty().isEmpty()
                || !capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
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
        if (methodMetaClass == null) {
            return new LazyDefinedClass(Type.getObjectType(adviceInternalName), bytes);
        } else {
            return new LazyDefinedClass(Type.getObjectType(adviceInternalName), bytes,
                    ImmutableList.of(methodMetaClass));
        }
    }

    private void addClassAnnotation(ClassWriter cw) {
        AnnotationVisitor annotationVisitor =
                cw.visitAnnotation("Lorg/glowroot/api/weaving/Pointcut;", true);
        annotationVisitor.visit("className", capturePoint.getClassName());
        annotationVisitor.visit("methodName", capturePoint.getMethodName());
        AnnotationVisitor arrayAnnotationVisitor =
                annotationVisitor.visitArray("methodParameterTypes");
        for (String methodParameterType : capturePoint.getMethodParameterTypes()) {
            arrayAnnotationVisitor.visit(null, methodParameterType);
        }
        arrayAnnotationVisitor.visitEnd();
        String metricName = capturePoint.getMetricName();
        if (capturePoint.isMetricOrGreater()) {
            if (metricName == null) {
                annotationVisitor.visit("metricName", "<no metric name provided>");
            } else {
                annotationVisitor.visit("metricName", metricName);
            }
        }
        if (capturePoint.isTraceEntryOrGreater() && !capturePoint.isTraceEntryCaptureSelfNested()) {
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
                "Lorg/glowroot/api/PluginServices;", null, null)
                .visitEnd();
        if (capturePoint.isMetricOrGreater()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "metricName",
                    "Lorg/glowroot/api/MetricName;", null, null)
                    .visitEnd();
        }
        if (!capturePoint.getEnabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_VOLATILE, "enabled", "Z", null, null)
                    .visitEnd();
        }
        if (!capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_STATIC + ACC_VOLATILE, "entryEnabled", "Z", null, null)
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
                "(Ljava/lang/String;)Lorg/glowroot/api/PluginServices;", false);
        mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (capturePoint.isMetricOrGreater()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(Type.getObjectType(adviceInternalName));
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "getMetricName",
                    "(Ljava/lang/Class;)Lorg/glowroot/api/MetricName;", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "metricName",
                    "Lorg/glowroot/api/MetricName;");
        }
        if (!capturePoint.getEnabledProperty().isEmpty()
                || !capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
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
        if (capturePoint.getEnabledProperty().isEmpty()) {
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
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/api/TraceEntry;");
        mv.visitCode();
        if (!capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "entryEnabled", "Z");
            Label label = new Label();
            mv.visitJumpInsn(IFNE, label);
            // entryEnabled is false, collect metric only
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "metricName",
                    "Lorg/glowroot/api/MetricName;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "startTransactionMetric",
                    "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/TransactionMetric;", false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        if (capturePoint.isTransaction()) {
            String transactionType = capturePoint.getTransactionType();
            if (transactionType == null) {
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
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                    "create", "(Lorg/glowroot/advicegen/MessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lorg/glowroot/advicegen/GenericMessageSupplier;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
        }
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getMessageTemplate",
                "()Lorg/glowroot/advicegen/MessageTemplate;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                "create",
                "(Lorg/glowroot/advicegen/MessageTemplate;"
                        + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Lorg/glowroot/advicegen/GenericMessageSupplier;", false);
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "metricName",
                "Lorg/glowroot/api/MetricName;");
        if (capturePoint.isTransaction()) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "startTransaction", "(Ljava/lang/String;Ljava/lang/String;"
                            + "Lorg/glowroot/api/MessageSupplier;Lorg/glowroot/api/MetricName;)"
                            + "Lorg/glowroot/api/TraceEntry;", false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices", "startTraceEntry",
                    "(Lorg/glowroot/api/MessageSupplier;Lorg/glowroot/api/MetricName;)"
                            + "Lorg/glowroot/api/TraceEntry;", false);
        }
        addCodeForOptionalTraceAttributes(mv);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodMetricOnly(ClassWriter cw) {
        MethodVisitor mv = visitOnBeforeMethod(cw, "Lorg/glowroot/api/TransactionMetric;");
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                "Lorg/glowroot/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceInternalName, "metricName",
                "Lorg/glowroot/api/MetricName;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                "startTransactionMetric",
                "(Lorg/glowroot/api/MetricName;)Lorg/glowroot/api/TransactionMetric;", false);
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
        if (!capturePoint.getTransactionType().isEmpty() && !capturePoint.isTransaction()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(capturePoint.getTransactionType());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTransactionType", "(Ljava/lang/String;)V", false);
        }
        if (!capturePoint.getTransactionNameTemplate().isEmpty() && !capturePoint.isTransaction()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionNameTemplate is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionNameTemplate",
                    "()Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                    "create", "(Lorg/glowroot/advicegen/MessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lorg/glowroot/advicegen/GenericMessageSupplier;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTransactionName", "(Ljava/lang/String;)V", false);
        }
        if (!capturePoint.getTransactionUserTemplate().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionUserTemplate is non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName, "getTransactionUserTemplate",
                    "()Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                    "create", "(Lorg/glowroot/advicegen/MessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lorg/glowroot/advicegen/GenericMessageSupplier;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/glowroot/advicegen/GenericMessageSupplier", "getMessageText",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTransactionUser", "(Ljava/lang/String;)V", false);
        }
        int i = 0;
        for (String attrName : capturePoint.getTransactionCustomAttributeTemplates().keySet()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(attrName);
            mv.visitVarInsn(ALOAD, 3);
            // methodMetaInternalName is non-null when transactionCustomAttributeTemplates is
            // non-empty
            checkNotNull(methodMetaInternalName);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodMetaInternalName,
                    "getTransactionCustomAttributeTemplate" + i++,
                    "()Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                    "create", "(Lorg/glowroot/advicegen/MessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lorg/glowroot/advicegen/GenericMessageSupplier;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/advicegen/GenericMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "setTransactionCustomAttribute", "(Ljava/lang/String;Ljava/lang/String;)V",
                    false);
        }
        Long traceStoreThresholdMillis = capturePoint.getTraceStoreThresholdMillis();
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
                "(Lorg/glowroot/api/TransactionMetric;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnAfter;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TransactionMetric", "stop", "()V",
                true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnReturnMethod(ClassWriter cw) {
        boolean entryOrTimer = !capturePoint.getTraceEntryEnabledProperty().isEmpty();
        String travelerType = entryOrTimer ? "Ljava/lang/Object;" : "Lorg/glowroot/api/TraceEntry;";
        MethodVisitor mv;
        int travelerParamIndex;
        if (capturePoint.getMethodReturnType().isEmpty()) {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lorg/glowroot/api/OptionalReturn;" + travelerType + ")V", null, null);
            mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindOptionalReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                    .visitEnd();
            travelerParamIndex = 1;
        } else if (capturePoint.getMethodReturnType().equals("void")) {
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
        if (!capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(INSTANCEOF, "org/glowroot/api/TransactionMetric");
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitVarInsn(ALOAD, travelerParamIndex);
            mv.visitTypeInsn(CHECKCAST, "org/glowroot/api/TransactionMetric");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TransactionMetric", "stop",
                    "()V", true);
            mv.visitInsn(RETURN);
            mv.visitLabel(label);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
        if (capturePoint.getMethodReturnType().isEmpty()) {
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
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/TraceEntry;Ljava/lang/Object;)V",
                    false);
        } else if (!capturePoint.getMethodReturnType().equals("void")) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/GenericMessageSupplier",
                    "updateWithReturnValue", "(Lorg/glowroot/api/TraceEntry;Ljava/lang/Object;)V",
                    false);
        }
        mv.visitVarInsn(ALOAD, travelerParamIndex);
        Long stackTraceThresholdMillis = capturePoint.getTraceEntryStackThresholdMillis();
        if (stackTraceThresholdMillis == null) {
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TraceEntry", "end",
                    "()Lorg/glowroot/api/CompletedTraceEntry;", true);
        } else {
            mv.visitLdcInsn(stackTraceThresholdMillis);
            mv.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS",
                    "Ljava/util/concurrent/TimeUnit;");
            mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TraceEntry", "endWithStackTrace",
                    "(JLjava/util/concurrent/TimeUnit;)Lorg/glowroot/api/CompletedTraceEntry;",
                    true);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnThrowMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "onThrow",
                "(Ljava/lang/Throwable;Lorg/glowroot/api/TraceEntry;)V", null, null);
        mv.visitAnnotation("Lorg/glowroot/api/weaving/OnThrow;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lorg/glowroot/api/weaving/BindThrowable;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lorg/glowroot/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        if (!capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
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
        mv.visitMethodInsn(INVOKEINTERFACE, "org/glowroot/api/TraceEntry", "endWithError",
                "(Lorg/glowroot/api/ErrorMessage;)Lorg/glowroot/api/CompletedTraceEntry;", true);
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
        if (!capturePoint.getEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(capturePoint.getEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "getBooleanProperty", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "enabled", "Z");
        }
        if (!capturePoint.getTraceEntryEnabledProperty().isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, adviceInternalName, "pluginServices",
                    "Lorg/glowroot/api/PluginServices;");
            mv.visitLdcInsn(capturePoint.getTraceEntryEnabledProperty());
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/glowroot/api/PluginServices",
                    "getBooleanProperty", "(Ljava/lang/String;)Z", false);
            mv.visitFieldInsn(PUTSTATIC, adviceInternalName, "entryEnabled", "Z");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @RequiresNonNull("methodMetaInternalName")
    private LazyDefinedClass generateMethodMetaClass(CapturePoint capturePoint)
            throws ReflectiveException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, methodMetaInternalName, null, "java/lang/Object",
                null);
        cw.visitField(ACC_PRIVATE + ACC_FINAL, "messageTemplate",
                "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                .visitEnd();
        if (!capturePoint.getTransactionNameTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionNameTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                    .visitEnd();
        }
        if (!capturePoint.getTransactionUserTemplate().isEmpty()) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionUserTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                    .visitEnd();
        }
        for (int i = 0; i < capturePoint.getTransactionCustomAttributeTemplates().size(); i++) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, "transactionCustomAttributeTemplate" + i,
                    "Lorg/glowroot/advicegen/MessageTemplate;", null, null)
                    .visitEnd();
        }
        generateMethodMetaConstructor(cw);
        generateMethodMetaGetter(cw, "messageTemplate", "getMessageTemplate");
        if (!capturePoint.getTransactionNameTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionNameTemplate", "getTransactionNameTemplate");
        }
        if (!capturePoint.getTransactionUserTemplate().isEmpty()) {
            generateMethodMetaGetter(cw, "transactionUserTemplate", "getTransactionUserTemplate");
        }
        for (int i = 0; i < capturePoint.getTransactionCustomAttributeTemplates().size(); i++) {
            generateMethodMetaGetter(cw, "transactionCustomAttributeTemplate" + i,
                    "getTransactionCustomAttributeTemplate" + i);
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        return new LazyDefinedClass(Type.getObjectType(methodMetaInternalName), bytes);
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
        if (capturePoint.isTraceEntryOrGreater()) {
            String traceEntryTemplate = capturePoint.getTraceEntryTemplate();
            if (traceEntryTemplate == null) {
                mv.visitLdcInsn("<no message template provided>");
            } else {
                mv.visitLdcInsn(traceEntryTemplate);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/MessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;", false);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "messageTemplate",
                "Lorg/glowroot/advicegen/MessageTemplate;");
        if (!capturePoint.getTransactionNameTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(capturePoint.getTransactionNameTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/MessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionNameTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;");
        }
        if (!capturePoint.getTransactionUserTemplate().isEmpty()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(capturePoint.getTransactionUserTemplate());
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, "org/glowroot/advicegen/MessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName, "transactionUserTemplate",
                    "Lorg/glowroot/advicegen/MessageTemplate;");
        }
        int i = 0;
        for (String attrTemplate : capturePoint.getTransactionCustomAttributeTemplates().values()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(attrTemplate);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "org/glowroot/advicegen/MessageTemplate", "create",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)"
                            + "Lorg/glowroot/advicegen/MessageTemplate;", false);
            mv.visitFieldInsn(PUTFIELD, methodMetaInternalName,
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

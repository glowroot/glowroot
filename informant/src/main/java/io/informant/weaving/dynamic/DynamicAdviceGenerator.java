/**
 * Copyright 2013 the original author or authors.
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
package io.informant.weaving.dynamic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Strings;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.informant.config.PointcutConfig;
import io.informant.config.PointcutConfig.CaptureItem;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicAdviceGenerator implements Opcodes {

    private static final AtomicInteger counter = new AtomicInteger();

    private final PointcutConfig pointcutConfig;
    private final String adviceTypeName;

    public DynamicAdviceGenerator(PointcutConfig pointcutConfig) {
        this.pointcutConfig = pointcutConfig;
        adviceTypeName = "io/informant/weaving/dynamic/GeneratedAdvice"
                + counter.incrementAndGet();
    }

    public Class<?> generate() throws SecurityException, NoSuchMethodException,
            IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, adviceTypeName, null, "java/lang/Object", null);
        addClassAnnotation(cw);
        addStaticFields(cw);
        addStaticInitializer(cw);
        // addConstructor(cw);
        addIsEnabledMethod(cw);
        if (pointcutConfig.getCaptureItems().contains(CaptureItem.SPAN)) {
            addOnBeforeMethod(cw, pointcutConfig.getCaptureItems().contains(CaptureItem.TRACE));
            addOnThrowMethod(cw);
            addOnReturnMethod(cw);
        } else {
            addOnBeforeMethodMetricOnly(cw);
            addOnAfterMethodMetricOnly(cw);
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        return defineClass(adviceTypeName.replace('/', '.'), bytes);
    }

    private void addClassAnnotation(ClassVisitor cv) {
        AnnotationVisitor annotationVisitor =
                cv.visitAnnotation("Lio/informant/api/weaving/Pointcut;", true);
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
                "Lio/informant/api/PluginServices;", null, null)
                .visitEnd();
        cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "metric",
                "Lio/informant/api/MetricName;", null, null)
                .visitEnd();
        if (pointcutConfig.getCaptureItems().contains(CaptureItem.SPAN)) {
            cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "spanText",
                    "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;", null, null)
                    .visitEnd();
        }
        if (pointcutConfig.getCaptureItems().contains(CaptureItem.TRACE)) {
            cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "traceGrouping",
                    "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;", null, null)
                    .visitEnd();
        }
    }

    private void addStaticInitializer(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitCode();
        mv.visitLdcInsn("io.informant:dynamic-pointcuts");
        mv.visitMethodInsn(INVOKESTATIC, "io/informant/api/PluginServices", "get",
                "(Ljava/lang/String;)Lio/informant/api/PluginServices;");
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "pluginServices",
                "Lio/informant/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lio/informant/api/PluginServices;");
        mv.visitLdcInsn(Type.getType("L" + adviceTypeName + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/PluginServices", "getMetricName",
                "(Ljava/lang/Class;)Lio/informant/api/MetricName;");
        mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "metric", "Lio/informant/api/MetricName;");
        if (pointcutConfig.getCaptureItems().contains(CaptureItem.SPAN)) {
            String spanText = pointcutConfig.getSpanText();
            if (spanText == null) {
                mv.visitLdcInsn("<no span text provided>");
            } else {
                mv.visitLdcInsn(spanText);
            }
            mv.visitMethodInsn(INVOKESTATIC,
                    "io/informant/weaving/dynamic/DynamicPointcutMessageTemplate", "create",
                    "(Ljava/lang/String;)"
                            + "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;");
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "spanText",
                    "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;");
        }
        if (pointcutConfig.getCaptureItems().contains(CaptureItem.TRACE)) {
            if (Strings.isNullOrEmpty(pointcutConfig.getTraceGrouping())) {
                mv.visitLdcInsn("<no trace grouping provided>");
            } else {
                mv.visitLdcInsn(pointcutConfig.getTraceGrouping());
            }
            mv.visitMethodInsn(INVOKESTATIC,
                    "io/informant/weaving/dynamic/DynamicPointcutMessageTemplate", "create",
                    "(Ljava/lang/String;)"
                            + "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;");
            mv.visitFieldInsn(PUTSTATIC, adviceTypeName, "traceGrouping",
                    "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addIsEnabledMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "isEnabled", "()Z", null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitAnnotation("Lio/informant/api/weaving/IsEnabled;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lio/informant/api/PluginServices;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/PluginServices", "isEnabled", "()Z");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethod(ClassVisitor cv, boolean startTrace) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Lio/informant/api/Span;",
                null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitAnnotation("Lio/informant/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lio/informant/api/weaving/BindTarget;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lio/informant/api/weaving/BindMethodName;", true)
                .visitEnd();
        mv.visitParameterAnnotation(2, "Lio/informant/api/weaving/BindMethodArgArray;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lio/informant/api/PluginServices;");
        if (startTrace) {
            mv.visitFieldInsn(GETSTATIC, adviceTypeName, "traceGrouping",
                    "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "io/informant/weaving/dynamic/DynamicPointcutMessageSupplier", "create",
                    "(Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;"
                            + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Lio/informant/weaving/dynamic/DynamicPointcutMessageSupplier;");
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "io/informant/weaving/dynamic/DynamicPointcutMessageSupplier",
                    "getMessageText", "()Ljava/lang/String;");
        }
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "spanText",
                "Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC,
                "io/informant/weaving/dynamic/DynamicPointcutMessageSupplier", "create",
                "(Lio/informant/weaving/dynamic/DynamicPointcutMessageTemplate;"
                        + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                        + "Lio/informant/weaving/dynamic/DynamicPointcutMessageSupplier;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "metric", "Lio/informant/api/MetricName;");
        if (startTrace) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/PluginServices", "startTrace",
                    "(Ljava/lang/String;Lio/informant/api/MessageSupplier;"
                            + "Lio/informant/api/MetricName;)Lio/informant/api/Span;");
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/PluginServices", "startSpan",
                    "(Lio/informant/api/MessageSupplier;Lio/informant/api/MetricName;)"
                            + "Lio/informant/api/Span;");
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnBeforeMethodMetricOnly(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onBefore",
                "()Lio/informant/api/MetricTimer;", null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitAnnotation("Lio/informant/api/weaving/OnBefore;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "pluginServices",
                "Lio/informant/api/PluginServices;");
        mv.visitFieldInsn(GETSTATIC, adviceTypeName, "metric", "Lio/informant/api/MetricName;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/PluginServices", "startMetricTimer",
                "(Lio/informant/api/MetricName;)Lio/informant/api/MetricTimer;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnAfterMethodMetricOnly(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onAfter",
                "(Lio/informant/api/MetricTimer;)V", null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitAnnotation("Lio/informant/api/weaving/OnAfter;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lio/informant/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "io/informant/api/MetricTimer", "stop", "()V");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void addOnReturnMethod(ClassVisitor cv) {
        MethodVisitor mv;
        if (pointcutConfig.getMethodReturnTypeName().equals("")) {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lio/informant/api/OptionalReturn;Lio/informant/api/Span;)V", null, null);
            assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
            mv.visitParameterAnnotation(0, "Lio/informant/api/weaving/BindOptionalReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lio/informant/api/weaving/BindTraveler;", true)
                    .visitEnd();
        } else if (pointcutConfig.getMethodReturnTypeName().equals("void")) {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Lio/informant/api/Span;)V", null, null);
            assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
            mv.visitParameterAnnotation(0, "Lio/informant/api/weaving/BindTraveler;", true)
                    .visitEnd();
        } else {
            mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onReturn",
                    "(Ljava/lang/Object;Lio/informant/api/Span;)V", null, null);
            assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
            mv.visitParameterAnnotation(0, "Lio/informant/api/weaving/BindReturn;", true)
                    .visitEnd();
            mv.visitParameterAnnotation(1, "Lio/informant/api/weaving/BindTraveler;", true)
                    .visitEnd();
        }
        mv.visitAnnotation("Lio/informant/api/weaving/OnReturn;", true)
                .visitEnd();
        mv.visitCode();
        if (pointcutConfig.getMethodReturnTypeName().equals("")) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/OptionalReturn", "isVoid", "()Z");
            Label notVoidLabel = new Label();
            Label endIfLabel = new Label();
            mv.visitJumpInsn(IFEQ, notVoidLabel);
            mv.visitLdcInsn("void");
            mv.visitJumpInsn(GOTO, endIfLabel);
            mv.visitLabel(notVoidLabel);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/informant/api/OptionalReturn", "getValue",
                    "()Ljava/lang/Object;");
            mv.visitLabel(endIfLabel);
            mv.visitMethodInsn(INVOKESTATIC,
                    "io/informant/weaving/dynamic/DynamicPointcutMessageSupplier",
                    "updateWithReturnValue", "(Lio/informant/api/Span;Ljava/lang/Object;)V");
            mv.visitVarInsn(ALOAD, 1);
        } else if (pointcutConfig.getMethodReturnTypeName().equals("void")) {
            mv.visitVarInsn(ALOAD, 0);
        } else {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                    "io/informant/weaving/dynamic/DynamicPointcutMessageSupplier",
                    "updateWithReturnValue", "(Lio/informant/api/Span;Ljava/lang/Object;)V");
            mv.visitVarInsn(ALOAD, 1);
        }
        mv.visitMethodInsn(INVOKEINTERFACE, "io/informant/api/Span", "end",
                "()Lio/informant/api/CompletedSpan;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void addOnThrowMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_STATIC, "onThrow",
                "(Ljava/lang/Throwable;Lio/informant/api/Span;)V", null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitAnnotation("Lio/informant/api/weaving/OnThrow;", true)
                .visitEnd();
        mv.visitParameterAnnotation(0, "Lio/informant/api/weaving/BindThrowable;", true)
                .visitEnd();
        mv.visitParameterAnnotation(1, "Lio/informant/api/weaving/BindTraveler;", true)
                .visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "io/informant/api/ErrorMessage", "from",
                "(Ljava/lang/Throwable;)Lio/informant/api/ErrorMessage;");
        mv.visitMethodInsn(INVOKEINTERFACE, "io/informant/api/Span", "endWithError",
                "(Lio/informant/api/ErrorMessage;)Lio/informant/api/CompletedSpan;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

    }

    private static Class<?> defineClass(String name, byte[] bytes) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class,
                byte[].class, int.class, int.class);
        defineClassMethod.setAccessible(true);
        Class<?> definedClass = (Class<?>) defineClassMethod.invoke(
                DynamicAdviceGenerator.class.getClassLoader(), name, bytes, 0, bytes.length);
        assertNonNull(definedClass, "ClassLoader.defineClass() returned null");
        return definedClass;
    }
}

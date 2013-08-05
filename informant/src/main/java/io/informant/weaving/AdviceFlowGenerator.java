/*
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
package io.informant.weaving;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static io.informant.common.Nullness.assertNonNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class AdviceFlowGenerator {

    private static final AtomicInteger counter = new AtomicInteger();

    private static final Type adviceFlowOuterHolderType = Type.getType(AdviceFlowOuterHolder.class);

    private AdviceFlowGenerator() {}

    static Class<?> generate() throws SecurityException, NoSuchMethodException,
            IllegalArgumentException, IllegalAccessException, InvocationTargetException {

        String generatedTypeName = "io/informant/weaving/GeneratedAdviceFlow"
                + counter.incrementAndGet();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, generatedTypeName, null, "java/lang/Object", null);
        writeThreadLocalFields(cw);
        writeThreadLocalInitialization(cw, generatedTypeName);
        return defineClass(TypeNames.fromInternal(generatedTypeName), cw.toByteArray());
    }

    private static void writeThreadLocalFields(ClassVisitor cv) {
        cv.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "adviceFlow",
                adviceFlowOuterHolderType.getDescriptor(), null, null);
    }

    private static void writeThreadLocalInitialization(ClassVisitor cv, String adviceFlowTypeName) {
        MethodVisitor mv = cv.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        assertNonNull(mv, "ClassVisitor.visitMethod() returned null");
        mv.visitCode();
        String adviceFlowInternalName = adviceFlowOuterHolderType.getInternalName();
        mv.visitMethodInsn(INVOKESTATIC, adviceFlowInternalName, "create",
                "()L" + adviceFlowInternalName + ";");
        mv.visitFieldInsn(PUTSTATIC, adviceFlowTypeName, "adviceFlow",
                adviceFlowOuterHolderType.getDescriptor());
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cv.visitEnd();
    }

    private static Class<?> defineClass(String name, byte[] bytes) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class,
                byte[].class, int.class, int.class);
        defineClassMethod.setAccessible(true);
        Class<?> definedClass = (Class<?>) defineClassMethod.invoke(
                AdviceFlowGenerator.class.getClassLoader(), name, bytes, 0, bytes.length);
        assertNonNull(definedClass, "ClassLoader.defineClass() returned null");
        return definedClass;
    }
}

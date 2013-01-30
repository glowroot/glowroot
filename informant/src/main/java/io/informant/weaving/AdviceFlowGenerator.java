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
package io.informant.weaving;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AdviceFlowGenerator implements Opcodes {

    private static final AtomicInteger counter = new AtomicInteger();

    private static final Type adviceFlowOuterHolderType = Type.getType(AdviceFlowOuterHolder.class);

    private AdviceFlowGenerator() {}

    public static Class<?> generate() throws SecurityException, NoSuchMethodException,
            IllegalArgumentException, IllegalAccessException, InvocationTargetException {

        String generatedTypeName = "io/informant/weaving/GeneratedAdviceFlow"
                + counter.incrementAndGet();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, generatedTypeName, null, "java/lang/Object", null);
        writeThreadLocalFields(cw);
        writeThreadLocalInitialization(cw, generatedTypeName);
        Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class,
                byte[].class, int.class, int.class);
        byte[] bytes = cw.toByteArray();
        defineClassMethod.setAccessible(true);
        return (Class<?>) defineClassMethod.invoke(AdviceFlowGenerator.class.getClassLoader(),
                generatedTypeName.replace('/', '.'), bytes, 0, bytes.length);
    }

    private static void writeThreadLocalFields(ClassVisitor cv) {
        cv.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "adviceFlow",
                adviceFlowOuterHolderType.getDescriptor(), null, null);
    }

    private static void writeThreadLocalInitialization(ClassWriter cw, String adviceFlowTypeName) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        String adviceFlowInternalName = adviceFlowOuterHolderType.getInternalName();
        mv.visitMethodInsn(INVOKESTATIC, adviceFlowInternalName, "create",
                "()L" + adviceFlowInternalName + ";");
        mv.visitFieldInsn(PUTSTATIC, adviceFlowTypeName, "adviceFlow",
                adviceFlowOuterHolderType.getDescriptor());
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
    }
}

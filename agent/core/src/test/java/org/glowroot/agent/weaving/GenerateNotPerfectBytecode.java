/*
 * Copyright 2016 the original author or authors.
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

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.LCONST_0;
import static org.objectweb.asm.Opcodes.LRETURN;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_6;

public class GenerateNotPerfectBytecode {

    private static final boolean GENERATE_PERFECT = false;

    static LazyDefinedClass generateNotPerfectBytecode() throws Exception {

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;

        cw.visit(V1_6, ACC_PUBLIC + ACC_SUPER, "NotPerfectBytecode", null, "java/lang/Object",
                new String[] {Test.class.getName().replace('.', '/')});

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC, "test1", "()V", null, null);
            mv.visitCode();
            common(mv);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC, "test2", "()I", null, null);
            mv.visitCode();
            common(mv);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC, "test3", "()J", null, null);
            mv.visitCode();
            common(mv);
            mv.visitInsn(LCONST_0);
            mv.visitInsn(LRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PRIVATE, "x", "()I", null, null);
            mv.visitCode();
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PRIVATE, "y", "()J", null, null);
            mv.visitCode();
            mv.visitInsn(LCONST_0);
            mv.visitInsn(LRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();

        return ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType("NotPerfectBytecode"))
                .bytes(cw.toByteArray())
                .build();
    }

    private static void common(MethodVisitor mv) {
        invokeX(mv);
        invokeY(mv);
        invokeY(mv);
        invokeY(mv);
        invokeX(mv);
        invokeX(mv);
        invokeY(mv);
        invokeX(mv);
    }

    private static void invokeX(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "NotPerfectBytecode", "x", "()I", false);
        if (GENERATE_PERFECT) {
            mv.visitInsn(POP);
        }
    }

    private static void invokeY(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "NotPerfectBytecode", "y", "()J", false);
        if (GENERATE_PERFECT) {
            mv.visitInsn(POP2);
        }
    }

    public interface Test {
        void test1();
        int test2();
        long test3();
    }
}

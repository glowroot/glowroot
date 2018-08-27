/*
 * Copyright 2018 the original author or authors.
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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_7;

// this is valid bytecode, but cannot be generated from valid Java code
public class GenerateMoreHackedConstructorBytecode {

    static LazyDefinedClass generateMoreHackedConstructorBytecode() throws Exception {

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;

        // 1.7+ since testing frames
        cw.visit(V1_7, ACC_PUBLIC + ACC_SUPER, "MoreHackedConstructorBytecode", null,
                Test.class.getName().replace('.', '/'), new String[] {});

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            Label trueLabel = new Label();
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(IFEQ, trueLabel);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ATHROW);
            mv.visitLabel(trueLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, Test.class.getName().replace('.', '/'), "<init>",
                    "()V", false);

            mv.visitInsn(RETURN);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();

        return ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType("MoreHackedConstructorBytecode"))
                .bytes(cw.toByteArray())
                .build();
    }

    public static class Test {}
}

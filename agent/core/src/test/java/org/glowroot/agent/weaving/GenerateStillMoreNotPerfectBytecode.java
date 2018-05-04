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
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

public class GenerateStillMoreNotPerfectBytecode {

    static LazyDefinedClass generateStillMoreNotPerfectBytecode() throws Exception {

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;

        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, "StillMoreNotPerfectBytecode", null,
                "java/lang/Object", new String[] {Test.class.getName().replace('.', '/')});

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
            mv = cw.visitMethod(ACC_PUBLIC, "execute", "()V", null, null);
            mv.visitCode();
            Label label = new Label();
            mv.visitJumpInsn(GOTO, label);
            // need to generate more than Short.MAX_VALUE instructions so that asm has to generate
            // special instructions in org.objectweb.asm.Label.resolve() and do a second pass at the
            // end of org.objectweb.asm.ClassWriter.toByteArray(), which ended up causing
            // java.lang.NegativeArraySizeException at
            // org.objectweb.asm.Frame.merge(Frame.java:1197)
            // before frames were ignored for Java 6 bytecode in
            // WeavingMethodVisitor.visitImplicitFrame()
            for (int i = 0; i < 20000; i++) {
                mv.visitInsn(ICONST_1);
                mv.visitInsn(POP);
            }
            Label label2 = new Label();
            mv.visitLabel(label2);
            mv.visitInsn(POP);
            mv.visitLabel(label);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();

        return ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType("StillMoreNotPerfectBytecode"))
                .bytes(cw.toByteArray())
                .build();
    }

    public interface Test {
        void execute();
    }
}

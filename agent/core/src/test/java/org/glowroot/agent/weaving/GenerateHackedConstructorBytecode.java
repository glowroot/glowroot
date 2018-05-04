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

import java.io.IOException;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.ASMifier;

import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.BASTORE;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.NEWARRAY;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.T_BOOLEAN;
import static org.objectweb.asm.Opcodes.V1_6;

// this is valid bytecode, but cannot be generated from valid Java code
// e.g. jacoco does this
public class GenerateHackedConstructorBytecode {

    static LazyDefinedClass generateHackedConstructorBytecode() throws Exception {

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;

        cw.visit(V1_6, ACC_PUBLIC + ACC_SUPER, "HackedConstructorBytecode", null,
                Test.class.getName().replace('.', '/'), new String[] {});

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(ICONST_1);
            mv.visitIntInsn(NEWARRAY, T_BOOLEAN);
            mv.visitVarInsn(ASTORE, 1);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, Test.class.getName().replace('.', '/'), "<init>",
                    "()V", false);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(BASTORE);

            mv.visitInsn(RETURN);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();

        return ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType("HackedConstructorBytecode"))
                .bytes(cw.toByteArray())
                .build();
    }

    public static class Test {}

    public static void main(String[] args) throws IOException {
        ASMifier.main(new String[] {X.class.getName()});
    }

    public static class X {
        X() {
            boolean[] x = new boolean[1];
            x[0] = true;
        }

        static byte[] get() {
            return null;
        }
    }
}

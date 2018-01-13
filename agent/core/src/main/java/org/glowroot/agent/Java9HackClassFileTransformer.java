/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import javax.annotation.Nullable;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM6;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

class Java9HackClassFileTransformer implements ClassFileTransformer {

    @Override
    public byte /*@Nullable*/ [] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> classBeingRedefined, @Nullable ProtectionDomain protectionDomain,
            byte[] bytes) {
        if ("org/glowroot/agent/weaving/WeavingClassFileTransformer".equals(className)) {
            ClassWriter cw = new ClassWriter(0);
            ClassVisitor cv = new Java9HackClassVisitor(cw);
            ClassReader cr = new ClassReader(bytes);
            cr.accept(cv, 0);
            return cw.toByteArray();
        }
        return null;
    }

    private static class Java9HackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private Java9HackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            if (name.equals("transform")
                    && desc.equals("(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/security/ProtectionDomain;[B)[B")) {
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "transform",
                        "(Ljava/lang/Module;Ljava/lang/ClassLoader;Ljava/lang/String;"
                                + "Ljava/lang/Class;Ljava/security/ProtectionDomain;[B)[B",
                        null, null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 4);
                mv.visitVarInsn(ALOAD, 5);
                mv.visitVarInsn(ALOAD, 6);
                mv.visitMethodInsn(INVOKEVIRTUAL,
                        "org/glowroot/agent/weaving/WeavingClassFileTransformer", "transformJava9",
                        "(Ljava/lang/Object;Ljava/lang/ClassLoader;Ljava/lang/String;"
                                + "Ljava/lang/Class;Ljava/security/ProtectionDomain;[B)[B",
                        false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(7, 7);
                mv.visitEnd();
            }
            return cw.visitMethod(access, name, desc, signature, exceptions);
        }
    }
}

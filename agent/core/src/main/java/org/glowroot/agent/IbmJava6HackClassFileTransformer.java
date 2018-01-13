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

import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM6;

class IbmJava6HackClassFileTransformer implements ClassFileTransformer {

    @Override
    public byte /*@Nullable*/ [] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> classBeingRedefined, @Nullable ProtectionDomain protectionDomain,
            byte[] bytes) {
        if ("com/google/protobuf/UnsafeUtil".equals(className)) {
            ClassWriter cw = new ClassWriter(0);
            ClassVisitor cv = new IbmJava6HackClassVisitor(cw);
            ClassReader cr = new ClassReader(bytes);
            cr.accept(cv, 0);
            return cw.toByteArray();
        }
        return null;
    }

    private static class IbmJava6HackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private IbmJava6HackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("getUnsafe")) {
                mv.visitCode();
                mv.visitInsn(ACONST_NULL);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                return mv;
            } else {
                return mv;
            }
        }
    }
}

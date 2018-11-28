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
package org.glowroot.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.BytecodeEarly;

import static org.objectweb.asm.Opcodes.ASM7;

class IbmJava6HackClassFileTransformer2 implements ClassFileTransformer {

    private static final Logger logger =
            LoggerFactory.getLogger(IbmJava6HackClassFileTransformer.class);

    @Override
    public byte /*@Nullable*/ [] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> classBeingRedefined, @Nullable ProtectionDomain protectionDomain,
            byte[] bytes) {
        try {
            if ("org/slf4j/LoggerFactory".equals(className)) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv =
                        new IbmJava6HackClassVisitor2(cw, "findPossibleStaticLoggerBinderPathSet");
                ClassReader cr = new ClassReader(bytes);
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
            if ("ch/qos/logback/core/util/Loader".equals(className)) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv = new IbmJava6HackClassVisitor2(cw, "getResources");
                ClassReader cr = new ClassReader(bytes);
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        return null;
    }

    private static class IbmJava6HackClassVisitor2 extends ClassVisitor {

        private final ClassWriter cw;
        private final String methodName;

        private IbmJava6HackClassVisitor2(ClassWriter cw, String methodName) {
            super(ASM7, cw);
            this.cw = cw;
            this.methodName = methodName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, descriptor, signature, exceptions);
            if (name.equals(methodName) && descriptor.endsWith(")Ljava/util/Set;")) {
                return new IbmJava6HackMethodAdvice(mv, access, name, descriptor);
            } else {
                return mv;
            }
        }
    }

    private static class IbmJava6HackMethodAdvice extends AdviceAdapter {

        private IbmJava6HackMethodAdvice(MethodVisitor mv, int access, String name,
                String descriptor) {
            super(ASM7, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                visitMethodInsn(INVOKESTATIC, Type.getType(BytecodeEarly.class).getInternalName(),
                        "removeDuplicates", "(Ljava/util/Set;)Ljava/util/Set;", false);
            }
        }
    }
}

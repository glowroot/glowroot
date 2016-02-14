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
package org.glowroot.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import javax.annotation.Nullable;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.POP;

// this is a workaround for https://github.com/qos-ch/logback/pull/291
public class LogbackPatch implements ClassFileTransformer {

    @Override
    public byte /*@Nullable*/[] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> classBeingRedefined, @Nullable ProtectionDomain protectionDomain,
            byte[] bytes) {
        // need to match both shaded and unshaded for tests
        if (className != null && className.endsWith("/qos/logback/core/util/EnvUtil")) {
            return patch(bytes);
        }
        return null;
    }

    private byte[] patch(byte[] bytes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        PatchingClassVisitor cv = new PatchingClassVisitor(cw);
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private static class PatchingClassVisitor extends ClassVisitor {

        private PatchingClassVisitor(ClassWriter cw) {
            super(ASM5, cw);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/[] exceptions) {
            if (name.equals("isJaninoAvailable") && desc.equals("()Z")) {
                addPatchedMethod();
                return null;
            } else {
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        }

        private void addPatchedMethod() {
            MethodVisitor mv = super.visitMethod(ACC_PUBLIC + ACC_STATIC, "isJaninoAvailable",
                    "()Z", null, null);
            checkNotNull(mv);
            mv.visitCode();
            Label l0 = new Label();
            Label l1 = new Label();
            Label l2 = new Label();
            mv.visitTryCatchBlock(l0, l1, l2, "java/lang/ClassNotFoundException");
            mv.visitLdcInsn(Type.getType("Lch/qos/logback/core/util/EnvUtil;"));
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                    "()Ljava/lang/ClassLoader;", false);
            mv.visitVarInsn(ASTORE, 0);
            mv.visitLabel(l0);
            mv.visitLdcInsn("org.codehaus.janino.ScriptEvaluator");
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
            mv.visitInsn(POP);
            mv.visitLabel(l1);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(l2);
            mv.visitVarInsn(ASTORE, 1);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(3, 2);
            mv.visitEnd();
        }
    }
}

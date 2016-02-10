/*
 * Copyright 2015-2016 the original author or authors.
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

import javax.annotation.Nullable;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;

import static org.objectweb.asm.Opcodes.ASM5;

class FelixOsgiHackClassVisitor extends ClassVisitor {

    private final ClassWriter cw;

    FelixOsgiHackClassVisitor(ClassWriter cw) {
        super(ASM5, cw);
        this.cw = cw;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/[] exceptions) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
        if (name.equals("shouldBootDelegate") && desc.equals("(Ljava/lang/String;)Z")) {
            return new FelixOsgiHackMethodVisitor(mv, access, name, desc);
        } else {
            return mv;
        }
    }

    private static class FelixOsgiHackMethodVisitor extends AdviceAdapter {

        private FelixOsgiHackMethodVisitor(MethodVisitor mv, int access, String name, String desc) {
            super(ASM5, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn("org.glowroot.");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(label);

        }
    }
}

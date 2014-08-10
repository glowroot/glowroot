/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.weaving;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PointcutClassVisitor extends ClassVisitor {

    private final ClassWriter cw;

    @MonotonicNonNull
    private String internalName;
    private boolean clinit;

    PointcutClassVisitor(ClassWriter cw) {
        super(ASM5, cw);
        this.cw = cw;
    }

    @Override
    public void visit(int version, int access, String internalName, @Nullable String signature,
            @Nullable String superInternalName, String/*@Nullable*/[] interfaceInternalNames) {
        this.internalName = internalName;
        cw.visit(version, access, internalName, signature, superInternalName,
                interfaceInternalNames);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions) {
        if (name.equals("<clinit>")) {
            clinit = true;
            return cw.visitMethod(ACC_PRIVATE + ACC_STATIC, "glowroot$clinit", "()V", null,
                    null);
        } else {
            return cw.visitMethod(access, name, desc, signature, exceptions);
        }
    }

    @Override
    public void visitEnd() {
        checkNotNull(internalName);
        // normally can't add field during weaving since that will break re-weaving, but @Pointcut
        // classes never need to be re-woven so this is ok
        FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC,
                "glowroot$advice$flow$outer$holder",
                Type.getDescriptor(AdviceFlowOuterHolder.class), null, null);
        fv.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(AdviceFlowOuterHolder.class),
                "create", "()" + Type.getDescriptor(AdviceFlowOuterHolder.class), false);
        mv.visitFieldInsn(PUTSTATIC, internalName, "glowroot$advice$flow$outer$holder",
                Type.getDescriptor(AdviceFlowOuterHolder.class));
        if (clinit) {
            mv.visitMethodInsn(INVOKESTATIC, internalName, "glowroot$clinit", "()V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
    }
}

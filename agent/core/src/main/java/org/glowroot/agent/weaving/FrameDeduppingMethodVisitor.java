/*
 * Copyright 2014-2017 the original author or authors.
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

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ASM6;

// this is needed for weaving the code in DuplicateStackFramesMisc.execute1()
// and it seems that jacoco project had need for similar method visitor, see
// https://github.com/jacoco/jacoco/blob/master/org.jacoco.core/src/org/jacoco/core/internal/instr/DuplicateFrameEliminator.java
class FrameDeduppingMethodVisitor extends MethodVisitor {

    private boolean skipNextFrame;

    FrameDeduppingMethodVisitor(MethodVisitor mv) {
        super(ASM6, mv);
    }

    @Override
    public void visitFrame(int type, int nLocal, Object/*@Nullable*/[] local, int nStack,
            Object/*@Nullable*/[] stack) {
        if (!skipNextFrame) {
            super.visitFrame(type, nLocal, local, nStack, stack);
        }
        skipNextFrame = true;
    }

    @Override
    public void visitParameter(String name, int access) {
        super.visitParameter(name, access);
        skipNextFrame = false;
    }

    @Override
    public void visitInsn(int opcode) {
        super.visitInsn(opcode);
        skipNextFrame = false;
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        super.visitIntInsn(opcode, operand);
        skipNextFrame = false;
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        super.visitVarInsn(opcode, var);
        skipNextFrame = false;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        super.visitTypeInsn(opcode, type);
        skipNextFrame = false;
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        super.visitFieldInsn(opcode, owner, name, desc);
        skipNextFrame = false;
    }

    @Override
    @Deprecated
    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        super.visitMethodInsn(opcode, owner, name, desc);
        skipNextFrame = false;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        super.visitMethodInsn(opcode, owner, name, desc, itf);
        skipNextFrame = false;
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        skipNextFrame = false;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        super.visitJumpInsn(opcode, label);
        skipNextFrame = false;
    }

    @Override
    public void visitLdcInsn(Object cst) {
        super.visitLdcInsn(cst);
        skipNextFrame = false;
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        super.visitIincInsn(var, increment);
        skipNextFrame = false;
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        super.visitTableSwitchInsn(min, max, dflt, labels);
        skipNextFrame = false;
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        super.visitLookupSwitchInsn(dflt, keys, labels);
        skipNextFrame = false;
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        super.visitMultiANewArrayInsn(desc, dims);
        skipNextFrame = false;
    }
}

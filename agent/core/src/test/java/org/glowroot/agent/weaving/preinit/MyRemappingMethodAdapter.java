/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.weaving.preinit;

import javax.annotation.Nullable;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.LocalVariablesSorter;

import static org.objectweb.asm.Opcodes.ASM6;

// from org.objectweb.asm.commons.RemappingMethodAdapter
class MyRemappingMethodAdapter extends LocalVariablesSorter {

    private final MethodCollector remapper;

    MyRemappingMethodAdapter(int access, String desc, MethodCollector remapper) {
        super(ASM6, access, desc, new MethodVisitor(ASM6) {});
        this.remapper = remapper;
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        remapEntries(nLocal, local);
        remapEntries(nStack, stack);
    }

    private void remapEntries(int n, Object[] entries) {
        for (int i = 0; i < n; i++) {
            Object entry = entries[i++];
            if (entry instanceof String) {
                remapper.mapType((String) entry);
            }
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        remapper.mapType(owner);
        remapper.mapFieldName(owner, name, desc);
        remapper.mapDesc(desc);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        remapper.mapType(owner);
        remapper.mapMethodName(owner, name, desc);
        remapper.mapMethodDesc(desc);
        remapper.addReferencedMethod(ReferencedMethod.create(owner, name, desc));
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        for (int i = 0; i < bsmArgs.length; i++) {
            bsmArgs[i] = remapper.mapValue(bsmArgs[i]);
        }
        remapper.mapInvokeDynamicMethodName(name, desc);
        remapper.mapMethodDesc(desc);
        remapper.mapValue(bsm);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        remapper.mapType(type);
    }

    @Override
    public void visitLdcInsn(Object cst) {
        remapper.mapValue(cst);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        remapper.mapDesc(desc);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, @Nullable String type) {
        if (type != null) {
            remapper.mapType(type);
        }
    }

    @Override
    public void visitLocalVariable(String name, String desc, @Nullable String signature,
            Label start, Label end, int index) {

        remapper.mapDesc(desc);
        remapper.mapSignature(signature, true);
    }
}

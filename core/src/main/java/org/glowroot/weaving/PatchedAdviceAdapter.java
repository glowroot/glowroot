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

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PatchedAdviceAdapter extends AdviceAdapter {

    protected PatchedAdviceAdapter(int api, MethodVisitor mv, int access, String name,
            String desc) {
        super(api, mv, access, name, desc);
    }

    @Override
    public void invokeVirtual(Type owner, Method method) {
        invokeInsn(INVOKEVIRTUAL, owner, method, false);
    }

    @Override
    public void invokeConstructor(Type type, Method method) {
        invokeInsn(INVOKESPECIAL, type, method, false);
    }

    @Override
    public void invokeStatic(Type owner, Method method) {
        invokeInsn(INVOKESTATIC, owner, method, false);
    }

    @Override
    public void invokeInterface(Type owner, Method method) {
        invokeInsn(INVOKEINTERFACE, owner, method, true);
    }

    private void invokeInsn(int opcode, Type type, Method method, boolean itf) {
        String owner = type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
        visitMethodInsn(opcode, owner, method.getName(), method.getDescriptor(), itf);
    }
}

/*
 * Copyright 2013-2017 the original author or authors.
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

import com.google.common.reflect.Reflection;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import org.glowroot.agent.weaving.targets.Misc;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.V1_7;

public class TroublesomeBytecodeMisc implements Misc {

    @Override
    public void execute1() {
        IsolatedWeavingClassLoader loader =
                (IsolatedWeavingClassLoader) getClass().getClassLoader();
        byte[] bytes = generateTroublesomeBytecode();
        Class<?> clazz = loader.weaveAndDefineClass("TroublesomeBytecode", bytes, null);
        Reflection.initialize(clazz);
    }

    @Override
    public String executeWithReturn() {
        return "";
    }

    @Override
    public void executeWithArgs(String one, int two) {}

    private static byte[] generateTroublesomeBytecode() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_7, ACC_PUBLIC + ACC_SUPER, "TroublesomeBytecode", null, "java/lang/Object",
                null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "test", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

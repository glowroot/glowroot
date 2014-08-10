/*
 * Copyright 2013-2014 the original author or authors.
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

import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import org.glowroot.weaving.Advice.AdviceConstructionException;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class AdviceFlowGenerator {

    private static final AtomicInteger counter = new AtomicInteger();

    private static final Type adviceFlowType = Type.getType(AdviceFlow.class);
    private static final Type adviceFlowOuterHolderType = Type.getType(AdviceFlowOuterHolder.class);

    private AdviceFlowGenerator() {}

    static LazyDefinedClass generate() throws AdviceConstructionException {
        String generatedInternalName = "org/glowroot/weaving/GeneratedAdviceFlow"
                + counter.incrementAndGet();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, generatedInternalName, null, "java/lang/Object",
                new String[] {adviceFlowType.getInternalName()});
        writeThreadLocalFields(cw);
        writeThreadLocalInitialization(cw, generatedInternalName);
        return new LazyDefinedClass(Type.getObjectType(generatedInternalName), cw.toByteArray());
    }

    private static void writeThreadLocalFields(ClassVisitor cv) {
        cv.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "adviceFlow",
                adviceFlowOuterHolderType.getDescriptor(), null, null);
    }

    private static void writeThreadLocalInitialization(ClassVisitor cv, String ownerInternalName) {
        MethodVisitor mv = cv.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        checkNotNull(mv);
        mv.visitCode();
        String holderInternalName = adviceFlowOuterHolderType.getInternalName();
        mv.visitMethodInsn(INVOKESTATIC, holderInternalName, "create",
                "()L" + holderInternalName + ";", false);
        mv.visitFieldInsn(PUTSTATIC, ownerInternalName, "adviceFlow",
                adviceFlowOuterHolderType.getInternalName());
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cv.visitEnd();
    }
}

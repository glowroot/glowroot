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
package org.glowroot.agent.weaving;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.F_NEW;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

class PointcutClassVisitor extends ClassVisitor {

    private final ClassWriter cw;
    private @Nullable String className;
    private @MonotonicNonNull PointcutMethodVisitor onBeforeMethodVisitor;

    private boolean constructorPointcut;

    PointcutClassVisitor(ClassWriter cw) {
        super(ASM7, cw);
        this.cw = cw;
    }

    boolean isConstructorPointcut() {
        return constructorPointcut;
    }

    boolean hasOnBeforeMethod() {
        return onBeforeMethodVisitor != null;
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String /*@Nullable*/ [] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.className = name;
    }

    @Override
    public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        AnnotationVisitor av = cw.visitAnnotation(desc, visible);
        if (desc.equals("Lorg/glowroot/agent/plugin/api/weaving/Pointcut;")) {
            return new PointcutAnnotationVisitor(av);
        } else {
            return av;
        }
    }

    @Override
    public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/ [] exceptions) {
        if (constructorPointcut) {
            return new PointcutMethodVisitor(
                    cw.visitMethod(access, name, desc, signature, exceptions), access, name, desc,
                    signature, exceptions);
        } else {
            // shortcut, no need to further analyze this class
            return null;
        }
    }

    @Override
    public void visitEnd() {
        if (onBeforeMethodVisitor != null) {
            int access = onBeforeMethodVisitor.access;
            String name = onBeforeMethodVisitor.name;
            String desc = "(Z" + onBeforeMethodVisitor.desc.substring(1);
            String signature = onBeforeMethodVisitor.signature;
            String[] exceptions = onBeforeMethodVisitor.exceptions;
            GeneratorAdapter mv = new GeneratorAdapter(
                    cw.visitMethod(access, name, desc, signature, exceptions), access, name,
                    desc);
            mv.visitCode();
            mv.visitVarInsn(ILOAD, 0);
            Label endWithDefaultLabel = new Label();
            mv.visitJumpInsn(IFEQ, endWithDefaultLabel);
            mv.loadArgs(1, mv.getArgumentTypes().length - 1);
            mv.visitMethodInsn(INVOKESTATIC, checkNotNull(className), name,
                    onBeforeMethodVisitor.desc, false);
            mv.returnValue();

            mv.visitLabel(endWithDefaultLabel);
            mv.visitFrame(F_NEW, 0, new Object[0], 0, new Object[0]);
            if (mv.getReturnType().getSort() != Type.VOID) {
                // return value will be ignored when !enabled, but need to return something
                WeavingMethodVisitor.pushDefault(mv, mv.getReturnType());
            }
            mv.returnValue();
            mv.endMethod();
        }
        super.visitEnd();
    }

    private class PointcutAnnotationVisitor extends AnnotationVisitor {

        private PointcutAnnotationVisitor(AnnotationVisitor av) {
            super(ASM7, av);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if ("methodName".equals(name) && "<init>".equals(value)) {
                constructorPointcut = true;
            }
            super.visit(name, value);
        }
    }

    private class PointcutMethodVisitor extends MethodVisitor {

        private final int access;
        private final String name;
        private final String desc;
        private final @Nullable String signature;
        private final String /*@Nullable*/ [] exceptions;

        private PointcutMethodVisitor(MethodVisitor mv, int access, String name,
                String desc, @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            super(ASM7, mv);
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.signature = signature;
            this.exceptions = exceptions;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals("Lorg/glowroot/agent/plugin/api/weaving/OnBefore;")) {
                onBeforeMethodVisitor = this;
            }
            return super.visitAnnotation(descriptor, visible);
        }
    }
}

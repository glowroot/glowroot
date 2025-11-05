/*
 * Copyright 2015-2025 the original author or authors.
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

import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ASM9;

class ThinClassVisitor extends ClassVisitor {

    private final ImmutableThinClass.Builder thinClassBuilder = ImmutableThinClass.builder();

    private int majorVersion;

    private @Nullable ThinClass thinClass;

    private boolean constructorPointcut;

    ThinClassVisitor() {
        super(ASM9);
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String /*@Nullable*/ [] interfaces) {
        majorVersion = version & 0xFFFF;
        thinClassBuilder.access(access);
        thinClassBuilder.name(name);
        thinClassBuilder.superName(superName);
        if (interfaces != null) {
            thinClassBuilder.addInterfaces(interfaces);
        }
        thinClassBuilder.genericClassInfo(GenericTypeResolver.parseMethodSignature(signature));
    }

    @Override
    public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        thinClassBuilder.addAnnotations(descriptor);
        if (descriptor.equals("Lorg/glowroot/agent/plugin/api/weaving/Pointcut;")) {
            return new PointcutAnnotationVisitor();
        } else if (descriptor.equals("Ljavax/ejb/Remote;") || descriptor.equals("Ljakarta/ejb/Remote;")) {
            return new RemoteAnnotationVisitor();
        } else {
            return null;
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
            @Nullable String signature, String /*@Nullable*/ [] exceptions) {
        ImmutableThinMethod.Builder thinMethodBuilder = ImmutableThinMethod.builder();
        thinMethodBuilder.access(access);
        thinMethodBuilder.name(name);
        thinMethodBuilder.descriptor(descriptor);
        thinMethodBuilder.signature(signature);
        if (exceptions != null) {
            thinMethodBuilder.addExceptions(exceptions);
        }
        return new AnnotationCaptureMethodVisitor(thinMethodBuilder);
    }

    @Override
    public void visitEnd() {
        thinClass = thinClassBuilder.build();
    }

    int getMajorVersion() {
        return majorVersion;
    }

    ThinClass getThinClass() {
        return checkNotNull(thinClass);
    }

    boolean isConstructorPointcut() {
        return constructorPointcut;
    }

    @Value.Immutable
    interface ThinClass {
        int access();
        String name();
        @Nullable
        String superName();
        List<String> interfaces();
        List<String> annotations();
        List<ThinMethod> nonBridgeMethods();
        List<ThinMethod> bridgeMethods();
        List<Type> ejbRemoteInterfaces();
        @Value.Auxiliary
        GenericClassVisitor.GenericClassInfo genericClassInfo();
    }

    @Value.Immutable
    interface ThinMethod {
        int access();
        String name();
        String descriptor();
        @Nullable
        String signature();
        List<String> exceptions();
        List<String> annotations();
    }

    private class PointcutAnnotationVisitor extends AnnotationVisitor {

        private PointcutAnnotationVisitor() {
            super(ASM9);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if ("methodName".equals(name) && "<init>".equals(value)) {
                constructorPointcut = true;
            }
        }
    }

    private class AnnotationCaptureMethodVisitor extends MethodVisitor {

        private final ImmutableThinMethod.Builder thinMethodBuilder;

        private AnnotationCaptureMethodVisitor(ImmutableThinMethod.Builder thinMethodBuilder) {
            super(ASM9);
            this.thinMethodBuilder = thinMethodBuilder;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            thinMethodBuilder.addAnnotations(descriptor);
            return null;
        }

        @Override
        public void visitEnd() {
            ThinMethod thinMethod = thinMethodBuilder.build();
            if ((thinMethod.access() & ACC_BRIDGE) != 0) {
                thinClassBuilder.addBridgeMethods(thinMethod);
            } else {
                thinClassBuilder.addNonBridgeMethods(thinMethod);
            }
        }
    }

    private class RemoteAnnotationVisitor extends AnnotationVisitor {

        private RemoteAnnotationVisitor() {
            super(ASM9);
        }

        @Override
        public @Nullable AnnotationVisitor visitArray(String name) {
            if (name.equals("value")) {
                return new ValueAnnotationVisitor();
            } else {
                return null;
            }
        }
    }

    private class ValueAnnotationVisitor extends AnnotationVisitor {

        private ValueAnnotationVisitor() {
            super(ASM9);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (name == null && value instanceof Type) {
                thinClassBuilder.addEjbRemoteInterfaces((Type) value);
            }
        }
    }
}

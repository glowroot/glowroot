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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.JSRInlinerAdapter;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM6;

class JSRInlinerClassVisitor extends ClassVisitor {

    JSRInlinerClassVisitor(ClassVisitor cv) {
        super(ASM6, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/ [] exceptions) {
        MethodVisitor mv =
                checkNotNull(cv).visitMethod(access, name, desc, signature, exceptions);
        return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
    }
}

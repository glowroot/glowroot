/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.weaving.preinit;

import javax.annotation.Nullable;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// from org.objectweb.asm.commons.RemappingClassAdapter
class MyRemappingClassAdapter extends ClassVisitor {

    private final TypeCollector typeCollector;
    @Nullable
    private String typeName;

    MyRemappingClassAdapter(TypeCollector remapper) {
        super(Opcodes.ASM4);
        this.typeCollector = remapper;
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String[] interfaces) {

        this.typeName = name;
        typeCollector.setSuperType(superName);
        typeCollector.setInterfaceTypes(interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, @Nullable String[] exceptions) {

        ReferencedMethod referencedMethod = ReferencedMethod.from(typeName, name, desc);
        MethodCollector methodCollector = new MethodCollector();
        if (exceptions != null) {
            for (String exception : exceptions) {
                methodCollector.map(exception);
            }
        }
        typeCollector.addMethod(referencedMethod, methodCollector);
        return new MyRemappingMethodAdapter(access, desc, methodCollector);
    }
}

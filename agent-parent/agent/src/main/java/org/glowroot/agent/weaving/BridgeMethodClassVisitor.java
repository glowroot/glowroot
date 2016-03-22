/*
 * Copyright 2016 the original author or authors.
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

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ASM5;

class BridgeMethodClassVisitor extends ClassVisitor {

    private final Map<String, String> bridgeTargetMethods = Maps.newHashMap();

    BridgeMethodClassVisitor() {
        super(ASM5);
    }

    public Map<String, String> getBridgeTargetMethods() {
        return bridgeTargetMethods;
    }

    @Override
    public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/[] exceptions) {
        if ((access & ACC_BRIDGE) == 0) {
            return null;
        }
        return new BridgeMethodVisitor(name, desc);
    }

    private class BridgeMethodVisitor extends MethodVisitor {

        private String bridgeMethodName;
        private String bridgeMethodDesc;
        private int bridgeMethodParamCount;

        private boolean found;

        private BridgeMethodVisitor(String bridgeMethodName, String bridgeMethodDesc) {
            super(ASM5);
            this.bridgeMethodName = bridgeMethodName;
            this.bridgeMethodDesc = bridgeMethodDesc;
            bridgeMethodParamCount = Type.getArgumentTypes(bridgeMethodDesc).length;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            if (found) {
                return;
            }
            if (!name.equals(bridgeMethodName)) {
                return;
            }
            if (Type.getArgumentTypes(desc).length != bridgeMethodParamCount) {
                return;
            }
            bridgeTargetMethods.put(this.bridgeMethodName + this.bridgeMethodDesc, name + desc);
            found = true;
        }
    }
}

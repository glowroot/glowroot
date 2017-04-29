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
package org.glowroot.agent.weaving;

import java.lang.reflect.Modifier;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

@Value.Immutable
abstract class AnalyzedMethod {

    abstract String name();
    // these are class names
    abstract ImmutableList<String> parameterTypes();
    abstract String returnType();
    abstract int modifiers();

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    abstract @Nullable String signature();
    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    abstract ImmutableList<String> exceptions();

    abstract ImmutableList<Advice> advisors();
    // this is for advisors that match everything except for @Pointcut subTypeRestriction
    abstract ImmutableList<Advice> subTypeRestrictedAdvisors();

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    String getDesc() {
        List<String> parameterTypes = parameterTypes();
        Type[] types = new Type[parameterTypes.size()];
        for (int i = 0; i < parameterTypes.size(); i++) {
            types[i] = getType(parameterTypes.get(i));
        }
        return Type.getMethodDescriptor(getType(returnType()), types);
    }

    // TODO there is a bit more to overriding, see
    // https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.5
    boolean isOverriddenBy(String methodName, List<Type> parameterTypes) {
        if (Modifier.isPrivate(modifiers())) {
            return false;
        }
        if (!methodName.equals(name())) {
            return false;
        }
        if (parameterTypes.size() != parameterTypes().size()) {
            return false;
        }
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (!parameterTypes.get(i).getClassName().equals(parameterTypes().get(i))) {
                return false;
            }
        }
        return true;
    }

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    @VisibleForTesting
    static Type getType(String type) {
        if (type.equals(Void.TYPE.getName())) {
            return Type.VOID_TYPE;
        } else if (type.equals(Boolean.TYPE.getName())) {
            return Type.BOOLEAN_TYPE;
        } else if (type.equals(Character.TYPE.getName())) {
            return Type.CHAR_TYPE;
        } else if (type.equals(Byte.TYPE.getName())) {
            return Type.BYTE_TYPE;
        } else if (type.equals(Short.TYPE.getName())) {
            return Type.SHORT_TYPE;
        } else if (type.equals(Integer.TYPE.getName())) {
            return Type.INT_TYPE;
        } else if (type.equals(Float.TYPE.getName())) {
            return Type.FLOAT_TYPE;
        } else if (type.equals(Long.TYPE.getName())) {
            return Type.LONG_TYPE;
        } else if (type.equals(Double.TYPE.getName())) {
            return Type.DOUBLE_TYPE;
        } else if (type.endsWith("[]")) {
            return getArrayType(type);
        } else {
            return Type.getObjectType(type.replace('.', '/'));
        }
    }

    private static Type getArrayType(String type) {
        StringBuilder sb = new StringBuilder();
        String remaining = type;
        while (remaining.endsWith("[]")) {
            sb.append("[");
            remaining = remaining.substring(0, remaining.length() - 2);
        }
        Type elementType = getType(remaining);
        sb.append(elementType.getDescriptor());
        return Type.getType(sb.toString());
    }
}

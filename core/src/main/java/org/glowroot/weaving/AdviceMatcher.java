/*
 * Copyright 2012-2015 the original author or authors.
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

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.MethodModifier;

@Value.Immutable
abstract class AdviceMatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdviceMatcher.class);

    static ImmutableList<AdviceMatcher> getAdviceMatchers(String className, List<Advice> advisors) {
        List<AdviceMatcher> adviceMatchers = Lists.newArrayList();
        for (Advice advice : advisors) {
            if (AdviceMatcher.isClassNameMatch(className, advice)) {
                adviceMatchers.add(ImmutableAdviceMatcher.of(advice));
            }
        }
        return ImmutableList.copyOf(adviceMatchers);
    }

    @Value.Parameter
    abstract Advice advice();

    boolean isMethodLevelMatch(String methodName, List<Type> parameterTypes, Type returnType,
            int modifiers) {
        if (!isMethodNameMatch(methodName) || !isMethodParameterTypesMatch(parameterTypes)) {
            return false;
        }
        return isMethodReturnMatch(returnType) && isMethodModifiersMatch(modifiers);
    }

    private boolean isMethodNameMatch(String methodName) {
        if (methodName.equals("<clinit>")) {
            // static initializers are not supported
            return false;
        }
        if (methodName.equals("<init>")) {
            // constructors only match by exact name (don't want patterns to match constructors)
            return advice().pointcut().methodName().equals("<init>");
        }
        Pattern pointcutMethodNamePattern = advice().pointcutMethodNamePattern();
        if (pointcutMethodNamePattern == null) {
            return advice().pointcut().methodName().equals(methodName);
        } else {
            return pointcutMethodNamePattern.matcher(methodName).matches();
        }
    }

    private boolean isMethodParameterTypesMatch(List<Type> parameterTypes) {
        String[] pointcutMethodParameterTypes = advice().pointcut().methodParameterTypes();
        for (int i = 0; i < pointcutMethodParameterTypes.length; i++) {
            String pointcutMethodParameterType = pointcutMethodParameterTypes[i];
            if (pointcutMethodParameterType.equals("..")) {
                if (i != pointcutMethodParameterTypes.length - 1) {
                    logger.warn("'..' can only be used at the end of methodParameterTypes");
                    return false;
                } else {
                    // ".." matches everything after this
                    return true;
                }
            }
            if (parameterTypes.size() == i) {
                // have run out of argument types to match
                return false;
            }
            if (!isMethodParameterTypeMatch(pointcutMethodParameterType, parameterTypes.get(i))) {
                return false;
            }
        }
        // need this final test since argumentTypes may still have unmatched elements
        return parameterTypes.size() == pointcutMethodParameterTypes.length;
    }

    private boolean isMethodParameterTypeMatch(String pointcutMethodParameterType,
            Type parameterType) {
        // only supporting * at this point
        return pointcutMethodParameterType.equals("*")
                || pointcutMethodParameterType.equals(parameterType.getClassName());
    }

    private boolean isMethodReturnMatch(Type returnType) {
        String pointcutMethodReturn = advice().pointcut().methodReturnType();
        return pointcutMethodReturn.isEmpty()
                || pointcutMethodReturn.equals(returnType.getClassName());
    }

    private boolean isMethodModifiersMatch(int modifiers) {
        for (MethodModifier methodModifier : advice().pointcut().methodModifiers()) {
            if (!isMethodModifierMatch(methodModifier, modifiers)) {
                return false;
            }
        }
        return true;
    }

    private boolean isMethodModifierMatch(MethodModifier methodModifier, int modifiers) {
        switch (methodModifier) {
            case PUBLIC:
                return Modifier.isPublic(modifiers);
            case STATIC:
                return Modifier.isStatic(modifiers);
            case NOT_STATIC:
                return !Modifier.isStatic(modifiers);
            default:
                return false;
        }
    }

    private static boolean isClassNameMatch(String className, Advice advice) {
        Pattern pointcutClassNamePattern = advice.pointcutClassNamePattern();
        if (pointcutClassNamePattern == null) {
            return advice.pointcut().className().equals(className);
        } else {
            return pointcutClassNamePattern.matcher(className).matches();
        }
    }
}

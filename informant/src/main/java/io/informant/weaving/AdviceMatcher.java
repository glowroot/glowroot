/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.weaving;

import io.informant.api.weaving.MethodModifier;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.regex.Pattern;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class AdviceMatcher {

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(AdviceMatcher.class);

    private final Advice advice;
    private final boolean targetTypeMatch;
    private final ImmutableList<ParsedType> preMatchedSuperTypes;

    AdviceMatcher(Advice advice, Type targetType, List<ParsedType> superTypes) {
        this.advice = advice;
        targetTypeMatch = isTypeMatch(targetType.getClassName(), advice);
        preMatchedSuperTypes = buildPreMatchedSuperTypes(superTypes);
    }

    boolean isClassLevelMatch() {
        return targetTypeMatch || !preMatchedSuperTypes.isEmpty();
    }

    boolean isMethodLevelMatch(int access, ParsedMethod parsedMethod) {
        if (!isMethodMatch(parsedMethod)) {
            return false;
        }
        if ((access & Opcodes.ACC_STATIC) == 0) {
            return isMethodOverrideMatch(parsedMethod);
        } else {
            // static methods only match at the target type (no inheritance worries)
            return targetTypeMatch;
        }
    }

    Advice getAdvice() {
        return advice;
    }

    private ImmutableList<ParsedType> buildPreMatchedSuperTypes(List<ParsedType> superTypes) {
        ImmutableList.Builder<ParsedType> builder = ImmutableList.builder();
        for (ParsedType superType : superTypes) {
            if (isTypeMatch(superType.getName(), advice)) {
                builder.add(superType);
            }
        }
        return builder.build();
    }

    private boolean isMethodMatch(ParsedMethod parsedMethod) {
        if (!isMethodNameMatch(parsedMethod.getName())) {
            return false;
        } else if (!isMethodArgTypesMatch(parsedMethod.getArgTypeNames())) {
            return false;
        } else if (!isMethodReturnMatch(parsedMethod.getReturnTypeName())) {
            return false;
        } else if (!isMethodModifiersMatch(parsedMethod.getModifiers())) {
            return false;
        }
        return true;
    }

    private boolean isMethodNameMatch(String name) {
        Pattern pointcutMethodPattern = advice.getPointcutMethodPattern();
        if (pointcutMethodPattern == null) {
            return advice.getPointcut().methodName().equals(name);
        } else if (name.equals("<init>") || name.equals("<clinit>")) {
            // constructors and static initializers are not supported (yet)
            return false;
        } else {
            return pointcutMethodPattern.matcher(name).matches();
        }
    }

    private boolean isMethodArgTypesMatch(@ReadOnly List<String> argTypeNames) {
        String[] pointcutMethodArgs = advice.getPointcut().methodArgs();
        for (int i = 0; i < pointcutMethodArgs.length; i++) {
            if (pointcutMethodArgs[i].equals("..")) {
                if (i != pointcutMethodArgs.length - 1) {
                    logger.warn("'..' can only be used at the end of methodArgs");
                    return false;
                } else {
                    // ".." matches everything after this
                    return true;
                }
            }
            if (argTypeNames.size() == i) {
                // have run out of argument types to match
                return false;
            }
            // only supporting * at this point
            if (!pointcutMethodArgs[i].equals("*")
                    && !pointcutMethodArgs[i].equals(argTypeNames.get(i))) {
                return false;
            }
        }
        // need this final test since argumentTypes may still have unmatched elements
        return argTypeNames.size() == pointcutMethodArgs.length;
    }

    private boolean isMethodReturnMatch(String returnTypeName) {
        String pointcutMethodReturn = advice.getPointcut().methodReturn();
        if (pointcutMethodReturn.equals("")) {
            return true;
        } else {
            return pointcutMethodReturn.equals(returnTypeName);
        }
    }

    private boolean isMethodModifiersMatch(int modifiers) {
        for (MethodModifier methodModifier : advice.getPointcut().methodModifiers()) {
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
            case PROTECTED:
                return Modifier.isProtected(modifiers);
            case PRIVATE:
                return Modifier.isPrivate(modifiers);
            case PACKAGE_PRIVATE:
                return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)
                        && !Modifier.isPrivate(modifiers);
            case STATIC:
                return Modifier.isStatic(modifiers);
            case NOT_STATIC:
                return !Modifier.isStatic(modifiers);
        }
        return false;
    }

    private boolean isMethodOverrideMatch(ParsedMethod parsedMethod) {
        if (targetTypeMatch) {
            return true;
        }
        for (ParsedType type : preMatchedSuperTypes) {
            if (type.getMethod(parsedMethod) != null) {
                // found overridden method in one of the matching super types
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        List<String> preMatchedSuperTypeNames = Lists.newArrayList();
        for (ParsedType preMatchedSuperType : preMatchedSuperTypes) {
            preMatchedSuperTypeNames.add(preMatchedSuperType.getName());
        }
        return Objects.toStringHelper(this)
                .add("advice", advice)
                .add("targetTypeMatch", targetTypeMatch)
                // shallow display of parsed types
                .add("preMatchedSuperTypes", preMatchedSuperTypeNames)
                .toString();
    }

    private static boolean isTypeMatch(String typeName, Advice advice) {
        Pattern pointcutTypePattern = advice.getPointcutTypePattern();
        if (pointcutTypePattern == null) {
            return advice.getPointcut().typeName().equals(typeName);
        } else {
            return pointcutTypePattern.matcher(typeName).matches();
        }
    }
}

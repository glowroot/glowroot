/*
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

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.regex.Pattern;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.MethodModifier;

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
        if (!isMethodNameMatch(parsedMethod.getName())
                || !isMethodArgTypesMatch(parsedMethod.getArgTypeNames())) {
            return false;
        }
        if (targetTypeMatch && isMethodReturnMatch(parsedMethod.getReturnTypeName())
                && isMethodModifiersMatch(parsedMethod.getModifiers())) {
            return true;
        }
        if (Modifier.isStatic(access)) {
            // non-static methods do not need to be tested against matching super types
            return false;
        }
        // need to test return match and modifiers match against overridden method
        for (ParsedType type : preMatchedSuperTypes) {
            ParsedMethod overriddenParsedMethod = type.getMethod(parsedMethod);
            if (overriddenParsedMethod != null
                    && isMethodReturnMatch(overriddenParsedMethod.getReturnTypeName())
                    && isMethodModifiersMatch(overriddenParsedMethod.getModifiers())) {
                // found overridden method in a matching super type, and the overridden method
                // has matching return type and modifiers
                return true;
            }
        }
        return false;
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

    private boolean isMethodNameMatch(String name) {
        if (name.equals("<clinit>")) {
            // static initializers are not supported
            return false;
        }
        if (name.equals("<init>")) {
            // constructors only match by exact name (don't want patterns to match constructors)
            return advice.getPointcut().methodName().equals("<init>");
        }
        Pattern pointcutMethodPattern = advice.getPointcutMethodPattern();
        if (pointcutMethodPattern == null) {
            return advice.getPointcut().methodName().equals(name);
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
        return pointcutMethodReturn.equals("") || pointcutMethodReturn.equals(returnTypeName);
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
            case ABSTRACT:
                return Modifier.isAbstract(modifiers);
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

/*
 * Copyright 2012-2014 the original author or authors.
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

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.dataflow.qual.Pure;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class AdviceMatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdviceMatcher.class);

    private final Advice advice;

    static ImmutableList<AdviceMatcher> getAdviceMatchers(String className, List<Advice> advisors) {
        List<AdviceMatcher> adviceMatchers = Lists.newArrayList();
        for (Advice advice : advisors) {
            if (AdviceMatcher.isTypeMatch(className, advice)) {
                adviceMatchers.add(new AdviceMatcher(advice));
            }
        }
        return ImmutableList.copyOf(adviceMatchers);
    }

    private AdviceMatcher(Advice advice) {
        this.advice = advice;
    }

    boolean isMethodLevelMatch(String methodName, List<Type> argTypes, Type returnType,
            int modifiers) {
        if (!isMethodNameMatch(methodName) || !isMethodArgTypesMatch(argTypes)) {
            return false;
        }
        return isMethodReturnMatch(returnType) && isMethodModifiersMatch(modifiers);
    }

    Advice getAdvice() {
        return advice;
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

    private boolean isMethodArgTypesMatch(List<Type> argTypes) {
        String[] pointcutMethodArgs = advice.getPointcut().methodArgTypes();
        for (int i = 0; i < pointcutMethodArgs.length; i++) {
            if (pointcutMethodArgs[i].equals("..")) {
                if (i != pointcutMethodArgs.length - 1) {
                    logger.warn("'..' can only be used at the end of methodArgTypes");
                    return false;
                } else {
                    // ".." matches everything after this
                    return true;
                }
            }
            if (argTypes.size() == i) {
                // have run out of argument types to match
                return false;
            }
            // only supporting * at this point
            if (!pointcutMethodArgs[i].equals("*")
                    && !pointcutMethodArgs[i].equals(argTypes.get(i).getClassName())) {
                return false;
            }
        }
        // need this final test since argumentTypes may still have unmatched elements
        return argTypes.size() == pointcutMethodArgs.length;
    }

    private boolean isMethodReturnMatch(Type returnType) {
        String pointcutMethodReturn = advice.getPointcut().methodReturnType();
        return pointcutMethodReturn.isEmpty()
                || pointcutMethodReturn.equals(returnType.getClassName());
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
            default:
                return false;
        }
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("advice", advice)
                .toString();
    }

    static boolean isTypeMatch(String className, Advice advice) {
        Pattern pointcutTypePattern = advice.getPointcutTypePattern();
        if (pointcutTypePattern == null) {
            return advice.getPointcut().type().equals(className);
        } else {
            return pointcutTypePattern.matcher(className).matches();
        }
    }
}

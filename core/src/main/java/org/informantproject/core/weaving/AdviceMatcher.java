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
package org.informantproject.core.weaving;

import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class AdviceMatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdviceMatcher.class);

    private final Advice advice;
    private final boolean targetTypeMatch;
    private final ImmutableList<ParsedType> preMatchedSuperTypes;

    AdviceMatcher(Advice advice, Type targetType, List<ParsedType> superTypes) {
        this.advice = advice;
        targetTypeMatch = isTypeMatch(targetType.getClassName());
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
            if (isTypeMatch(superType.getClassName())) {
                builder.add(superType);
            }
        }
        return builder.build();
    }

    private boolean isTypeMatch(String className) {
        // currently only exact matching is supported
        return advice.getPointcut().typeName().equals(className);
    }

    private boolean isMethodMatch(ParsedMethod parsedMethod) {
        if (!isMethodNameMatch(parsedMethod.getName())) {
            return false;
        } else {
            return isMethodArgsMatch(parsedMethod.getArgs());
        }
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

    private boolean isMethodArgsMatch(Type[] argumentTypes) {
        String[] pointcutMethodArgs = advice.getPointcut().methodArgs();
        for (int i = 0; i < pointcutMethodArgs.length; i++) {
            if (pointcutMethodArgs[i].equals("..")) {
                if (i != pointcutMethodArgs.length - 1) {
                    logger.error("'..' can only be used at the end of methodArgs");
                    return false;
                } else {
                    // ".." matches everything after this
                    return true;
                }
            }
            if (argumentTypes.length == i) {
                // have run out of argument types to match
                return false;
            }
            // only supporting /.*/ regex at this point
            if (!pointcutMethodArgs[i].equals("/.*/")
                    && !pointcutMethodArgs[i].equals(argumentTypes[i].getClassName())) {
                return false;
            }
        }
        // need this final test since argumentTypes may still have unmatched elements
        return argumentTypes.length == pointcutMethodArgs.length;
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
                .add("preMatchedSuperTypes", Joiner.on(", ").join(preMatchedSuperTypeNames))
                .toString();
    }
}

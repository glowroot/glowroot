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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AdviceMatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdviceMatcher.class);

    private final Advice advice;
    private final boolean targetTypeMatch;
    private final List<ParsedType> preMatchedSuperTypes;

    public AdviceMatcher(Advice advice, Type targetType, List<ParsedType> superTypes) {
        this.advice = advice;
        targetTypeMatch = isTypeMatch(targetType.getClassName());
        preMatchedSuperTypes = buildPreMatchedSuperTypes(superTypes);
    }

    public boolean isClassLevelMatch() {
        return targetTypeMatch || !preMatchedSuperTypes.isEmpty();
    }

    public boolean isMethodLevelMatch(int access, String name, String desc) {
        if (!isMethodNameMatch(name)) {
            return false;
        }
        if (!isMethodArgsMatch(desc)) {
            return false;
        }
        if ((access & Opcodes.ACC_STATIC) == 0) {
            return isMethodOverrideMatch(name, desc);
        } else {
            // static methods only match at the target type (no inheritance worries)
            return targetTypeMatch;
        }
    }

    public Advice getAdvice() {
        return advice;
    }

    private List<ParsedType> buildPreMatchedSuperTypes(List<ParsedType> superTypes) {
        List<ParsedType> preMatchedSuperTypes = Lists.newArrayList();
        for (ParsedType superType : superTypes) {
            if (isTypeMatch(superType.getClassName())) {
                preMatchedSuperTypes.add(superType);
            }
        }
        return preMatchedSuperTypes;
    }

    private boolean isTypeMatch(String className) {
        // currently only exact matching is supported
        return advice.getPointcut().typeName().equals(className);
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

    private boolean isMethodArgsMatch(String desc) {
        String[] pointcutMethodArgs = advice.getPointcut().methodArgs();
        Type[] argumentTypes = Type.getArgumentTypes(desc);
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
            if (!pointcutMethodArgs[i].equals("/.*/") && !pointcutMethodArgs[i].equals(
                    argumentTypes[i].getClassName())) {
                return false;
            }
        }
        // need this final test since argumentTypes may still have unmatched elements
        return argumentTypes.length == pointcutMethodArgs.length;
    }

    private boolean isMethodOverrideMatch(String name, String desc) {
        if (targetTypeMatch) {
            return true;
        }
        for (ParsedType type : preMatchedSuperTypes) {
            if (type.getMethod(name, Type.getArgumentTypes(desc)) != null) {
                // found overridden method in one of the matching super types
                return true;
            }
        }
        return false;
    }
}

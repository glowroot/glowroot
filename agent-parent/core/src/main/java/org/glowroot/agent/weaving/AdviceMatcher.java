/*
 * Copyright 2012-2016 the original author or authors.
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
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.weaving.MethodModifier;
import org.glowroot.common.util.Styles;

@Value.Immutable
@Styles.AllParameters
abstract class AdviceMatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdviceMatcher.class);

    static ImmutableList<AdviceMatcher> getAdviceMatchers(String className,
            List<String> classAnnotations, List<Advice> advisors) {
        List<AdviceMatcher> adviceMatchers = Lists.newArrayList();
        for (Advice advice : advisors) {
            if (isDeclaringClassMatch(className, classAnnotations, advice)) {
                adviceMatchers.add(ImmutableAdviceMatcher.of(advice));
            }
        }
        return ImmutableList.copyOf(adviceMatchers);
    }

    abstract Advice advice();

    boolean isMethodLevelMatch(String methodName, List<String> methodAnnotations,
            List<Type> parameterTypes, Type returnType, int modifiers) {
        if (!isMethodNameMatch(methodName) || !isAnnotationMatch(methodAnnotations,
                advice().pointcutMethodAnnotationPattern(), advice().pointcut().methodAnnotation())
                || !isMethodParameterTypesMatch(parameterTypes)) {
            return false;
        }
        return isMethodReturnMatch(returnType) && isMethodModifiersMatch(modifiers);
    }

    private boolean isMethodNameMatch(String methodName) {
        if (methodName.equals("<clinit>")) {
            // static initializers are not supported
            return false;
        }
        Pattern pointcutMethodNamePattern = advice().pointcutMethodNamePattern();
        if (pointcutMethodNamePattern != null) {
            // don't want patterns to match constructors
            return !methodName.equals("<init>")
                    && pointcutMethodNamePattern.matcher(methodName).matches();
        }
        String pointcutMethodName = advice().pointcut().methodName();
        return pointcutMethodName.isEmpty() || pointcutMethodName.equals(methodName);
    }

    private boolean isMethodParameterTypesMatch(List<Type> parameterTypes) {
        List<Object> pointcutMethodParameterTypes = advice().pointcutMethodParameterTypes();
        for (int i = 0; i < pointcutMethodParameterTypes.size(); i++) {
            Object pointcutMethodParameterType = pointcutMethodParameterTypes.get(i);
            if (pointcutMethodParameterType.equals("..")) {
                if (i != pointcutMethodParameterTypes.size() - 1) {
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
        return parameterTypes.size() == pointcutMethodParameterTypes.size();
    }

    private boolean isMethodParameterTypeMatch(Object pointcutMethodParameterType,
            Type parameterType) {
        String className = parameterType.getClassName();
        if (pointcutMethodParameterType instanceof String) {
            return pointcutMethodParameterType.equals(className);
        } else {
            return ((Pattern) pointcutMethodParameterType).matcher(className).matches();
        }
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

    private static boolean isDeclaringClassMatch(String className, List<String> classAnnotations,
            Advice advice) {
        if (!isAnnotationMatch(classAnnotations, advice.pointcutClassNameAnnotationPattern(),
                advice.pointcut().classAnnotation())) {
            return false;
        }
        Pattern methodDeclaringClassNamePattern = advice.pointcutMethodDeclaringClassNamePattern();
        if (methodDeclaringClassNamePattern != null) {
            return methodDeclaringClassNamePattern.matcher(className).matches();
        }
        String methodDeclaringClassName = advice.pointcutMethodDeclaringClassName();
        return methodDeclaringClassName.isEmpty() || methodDeclaringClassName.equals(className);
    }

    private static boolean isAnnotationMatch(List<String> annotations, @Nullable Pattern pattern,
            String strictMatch) {
        for (String annotation : annotations) {
            annotation = annotation.replace('/', '.').substring(1, annotation.length() - 1);
            if (pattern != null && pattern.matcher(annotation).matches()) {
                return true;
            }
            if (annotation.equals(strictMatch)) {
                return true;
            }
        }
        return strictMatch.isEmpty();
    }
}

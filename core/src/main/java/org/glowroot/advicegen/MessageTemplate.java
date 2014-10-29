/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.advicegen;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.UsedByGeneratedBytecode;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByGeneratedBytecode
public class MessageTemplate {

    private static final Logger logger = LoggerFactory.getLogger(MessageTemplate.class);

    private static final Pattern pattern = Pattern.compile("\\{\\{([^}]*)}}");

    private final ImmutableList<Part> allParts;
    private final ImmutableList<ValuePathPart> thisPathParts;
    private final ImmutableList<ArgPathPart> argPathParts;
    private final ImmutableList<ValuePathPart> returnPathParts;

    @UsedByGeneratedBytecode
    public static MessageTemplate create(String template, Class<?> declaringClass,
            Class<?> returnType, Class<?>[] parameterTypes) {
        List<Part> allParts = Lists.newArrayList();
        List<ValuePathPart> thisPathParts = Lists.newArrayList();
        List<ArgPathPart> argPathParts = Lists.newArrayList();
        List<ValuePathPart> returnPathParts = Lists.newArrayList();
        Matcher matcher = pattern.matcher(template);
        int curr = 0;
        while (matcher.find()) {
            if (matcher.start() > curr) {
                allParts.add(new ConstantPart(template.substring(curr, matcher.start())));
            }
            String group = matcher.group(1);
            checkNotNull(group);
            String path = group.trim();
            int index = path.indexOf('.');
            String base;
            String remaining;
            if (index == -1) {
                base = path;
                remaining = "";
            } else {
                base = path.substring(0, index);
                remaining = path.substring(index + 1);
            }
            if (base.equals("this")) {
                ValuePathPart part =
                        new ValuePathPart(PartType.THIS_PATH, declaringClass, remaining);
                allParts.add(part);
                thisPathParts.add(part);
            } else if (base.matches("[0-9]+")) {
                int argNumber = Integer.parseInt(base);
                if (argNumber < parameterTypes.length) {
                    ArgPathPart part =
                            new ArgPathPart(parameterTypes[argNumber], remaining, argNumber);
                    allParts.add(part);
                    argPathParts.add(part);
                } else {
                    allParts.add(new ConstantPart("<requested arg index out of bounds: "
                            + argNumber + ">"));
                }
            } else if (base.equals("_")) {
                ValuePathPart part = new ValuePathPart(PartType.RETURN_PATH, returnType, remaining);
                allParts.add(part);
                returnPathParts.add(part);
            } else if (base.equals("methodName")) {
                allParts.add(new Part(PartType.METHOD_NAME));
            } else {
                logger.warn("invalid template substitution: {}", path);
                allParts.add(new ConstantPart("{{" + path + "}}"));
            }
            curr = matcher.end();
        }
        if (curr < template.length()) {
            allParts.add(new ConstantPart(template.substring(curr)));
        }
        return new MessageTemplate(allParts, thisPathParts, argPathParts, returnPathParts);
    }

    private MessageTemplate(List<Part> allParts, List<ValuePathPart> thisPathParts,
            List<ArgPathPart> argPathParts, List<ValuePathPart> returnPathParts) {
        this.allParts = ImmutableList.copyOf(allParts);
        this.thisPathParts = ImmutableList.copyOf(thisPathParts);
        this.argPathParts = ImmutableList.copyOf(argPathParts);
        this.returnPathParts = ImmutableList.copyOf(returnPathParts);
    }

    ImmutableList<Part> getAllParts() {
        return allParts;
    }

    ImmutableList<ValuePathPart> getThisPathParts() {
        return thisPathParts;
    }

    ImmutableList<ArgPathPart> getArgPathParts() {
        return argPathParts;
    }

    ImmutableList<ValuePathPart> getReturnPathParts() {
        return returnPathParts;
    }

    enum PartType {
        CONSTANT, THIS_PATH, ARG_PATH, RETURN_PATH, METHOD_NAME;
    }

    static class Part {

        private final PartType type;

        private Part(PartType type) {
            this.type = type;
        }

        PartType getType() {
            return type;
        }
    }

    static class ConstantPart extends Part {

        private final String constant;

        private ConstantPart(String constant) {
            super(PartType.CONSTANT);
            this.constant = constant;
        }

        String getConstant() {
            return constant;
        }
    }

    static class ValuePathPart extends Part {

        private final PathEvaluator pathEvaluator;

        private ValuePathPart(PartType partType, Class<?> valueClass, String propertyPath) {
            super(partType);
            this.pathEvaluator = new PathEvaluator(valueClass, propertyPath);
        }

        String evaluatePart(@Nullable Object base) {
            if (base == null) {
                // this is same as String.valueOf((Object) null);
                return "null";
            }
            try {
                Object value = pathEvaluator.evaluateOnBase(base);
                if (value instanceof Object[]) {
                    StringBuilder sb = new StringBuilder();
                    valueOf((Object[]) value, sb);
                    return sb.toString();
                }
                return String.valueOf(value);
            } catch (IllegalArgumentException e) {
                logger.debug(e.getMessage(), e);
                return "<error evaluating: " + e.getMessage() + ">";
            } catch (IllegalAccessException e) {
                logger.debug(e.getMessage(), e);
                return "<error evaluating: " + e.getMessage() + ">";
            } catch (InvocationTargetException e) {
                logger.debug(e.getMessage(), e);
                return "<error evaluating: " + e.getMessage() + ">";
            }
        }

        private static void valueOf(Object object, StringBuilder sb) {
            if (object instanceof Object[]) {
                valueOf((Object[]) object, sb);
            } else {
                sb.append(String.valueOf(object));
            }
        }

        private static void valueOf(Object[] object, StringBuilder sb) {
            sb.append('[');
            for (int i = 0; i < object.length; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                valueOf(object[i], sb);
            }
            sb.append(']');
        }
    }

    static class ArgPathPart extends ValuePathPart {

        private final int argNumber;

        private ArgPathPart(Class<?> argClass, String propertyPath, int argNumber) {
            super(PartType.ARG_PATH, argClass, propertyPath);
            this.argNumber = argNumber;
        }

        int getArgNumber() {
            return argNumber;
        }
    }
}

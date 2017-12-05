/*
 * Copyright 2013-2017 the original author or authors.
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

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.UsedByGeneratedBytecode;

import static com.google.common.base.Preconditions.checkNotNull;

@UsedByGeneratedBytecode
public class MessageTemplate {

    private static final Logger logger = LoggerFactory.getLogger(MessageTemplate.class);

    private static final Pattern pattern = Pattern.compile("\\{\\{([^}]*)}}");

    private final ImmutableList<Part> allParts;
    private final ImmutableList<ValuePathPart> thisPathParts;
    private final ImmutableList<ArgPathPart> argPathParts;
    private final ImmutableList<ValuePathPart> returnPathParts;

    @UsedByGeneratedBytecode
    public static MessageTemplate create(String template, Method method) {
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
            String group = checkNotNull(matcher.group(1));
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
                ValuePathPart part = new ValuePathPart(PartType.THIS_PATH,
                        method.getDeclaringClass(), remaining);
                allParts.add(part);
                thisPathParts.add(part);
            } else if (base.matches("[0-9]+")) {
                int argNumber = Integer.parseInt(base);
                if (argNumber < method.getParameterTypes().length) {
                    ArgPathPart part = new ArgPathPart(method.getParameterTypes()[argNumber],
                            remaining, argNumber);
                    allParts.add(part);
                    argPathParts.add(part);
                } else {
                    allParts.add(new ConstantPart(
                            "<requested arg index out of bounds: " + argNumber + ">"));
                }
            } else if (base.equals("_")) {
                ValuePathPart part =
                        new ValuePathPart(PartType.RETURN_PATH, method.getReturnType(), remaining);
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
                return valueOf(pathEvaluator.evaluateOnBase(base));
            } catch (InvocationTargetException e) {
                logger.debug(e.getMessage(), e);
                // InvocationTargetException has the problem of obscuring the original message
                // to try to use cause
                Throwable t = MoreObjects.firstNonNull(e.getCause(), e);
                // using toString() instead of getMessage() in order to capture exception class name
                return "<error evaluating: " + t.toString() + ">";
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
                // using toString() instead of getMessage() in order to capture exception class name
                return "<error evaluating: " + e.toString() + ">";
            }
        }

        private static String valueOf(@Nullable Object value) {
            if (value == null || !value.getClass().isArray()) {
                // shortcut the common case
                return String.valueOf(value);
            }
            StringBuilder sb = new StringBuilder();
            valueOfArray(value, sb);
            return sb.toString();
        }

        private static void valueOfArray(Object array, StringBuilder sb) {
            sb.append('[');
            int len = Array.getLength(array);
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                valueOf(Array.get(array, i), sb);
            }
            sb.append(']');
        }

        private static void valueOf(Object object, StringBuilder sb) {
            if (object.getClass().isArray()) {
                valueOfArray(object, sb);
            } else {
                sb.append(String.valueOf(object));
            }
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

    @VisibleForTesting
    static class PathEvaluator {

        private static final Splitter splitter = Splitter.on('.').omitEmptyStrings();

        private final Accessor[] accessors;
        private final String /*@Nullable*/ [] remainingPath;

        PathEvaluator(Class<?> baseClass, String path) {
            List<String> parts = Lists.newArrayList(splitter.split(path));
            List<Accessor> accessors = Lists.newArrayList();
            Class<?> currClass = baseClass;
            while (!parts.isEmpty()) {
                String currPart = parts.remove(0);
                Accessor accessor = Beans.loadPossiblyArrayBasedAccessor(currClass, currPart);
                if (accessor == null) {
                    parts.add(0, currPart);
                    break;
                }
                accessors.add(accessor);
                currClass = accessor.getValueType();
            }
            this.accessors = accessors.toArray(new Accessor[accessors.size()]);
            if (parts.isEmpty()) {
                remainingPath = null;
            } else {
                remainingPath = parts.toArray(new String[parts.size()]);
            }
        }

        @Nullable
        Object evaluateOnBase(Object base) throws Exception {
            Object curr = base;
            for (Accessor accessor : accessors) {
                curr = accessor.evaluate(curr);
                if (curr == null) {
                    return null;
                }
            }
            if (remainingPath != null) {
                // too bad, revert to slow Beans
                return Beans.value(curr, remainingPath);
            }
            return curr;
        }
    }
}

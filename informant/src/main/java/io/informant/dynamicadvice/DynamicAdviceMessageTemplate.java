/**
 * Copyright 2013 the original author or authors.
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
package io.informant.dynamicadvice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.UsedByGeneratedBytecode;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicAdviceMessageTemplate {

    private static final Logger logger =
            LoggerFactory.getLogger(DynamicAdviceMessageTemplate.class);

    private static final Pattern pattern = Pattern.compile("\\{\\{([^}]*)}}");

    private final ImmutableList<Part> allParts;
    private final ImmutableList<ValuePathPart> thisPathParts;
    private final ImmutableList<ArgPathPart> argPathParts;
    private final ImmutableList<ValuePathPart> returnPathParts;

    @UsedByGeneratedBytecode
    public static DynamicAdviceMessageTemplate create(String template) {
        ImmutableList.Builder<Part> allParts = ImmutableList.builder();
        ImmutableList.Builder<ValuePathPart> thisPathParts = ImmutableList.builder();
        ImmutableList.Builder<ArgPathPart> argPathParts = ImmutableList.builder();
        ImmutableList.Builder<ValuePathPart> returnPathParts = ImmutableList.builder();
        Matcher matcher = pattern.matcher(template);
        int curr = 0;
        while (matcher.find()) {
            if (matcher.start() > curr) {
                allParts.add(new ConstantPart(template.substring(curr, matcher.start())));
            }
            String[] path = matcher.group(1).trim().split("\\.");
            if (path[0].equals("this")) {
                String[] shiftedPath = new String[path.length - 1];
                System.arraycopy(path, 1, shiftedPath, 0, path.length - 1);
                ValuePathPart part = new ValuePathPart(PartType.THIS_PATH, shiftedPath);
                allParts.add(part);
                thisPathParts.add(part);
            } else if (path[0].matches("[0-9]+")) {
                int argNumber = Integer.parseInt(path[0]);
                String[] shiftedPath = new String[path.length - 1];
                System.arraycopy(path, 1, shiftedPath, 0, path.length - 1);
                ArgPathPart part = new ArgPathPart(argNumber, shiftedPath);
                allParts.add(part);
                argPathParts.add(part);
            } else if (path[0].equals("ret")) {
                String[] shiftedPath = new String[path.length - 1];
                System.arraycopy(path, 1, shiftedPath, 0, path.length - 1);
                ValuePathPart part = new ValuePathPart(PartType.RETURN_PATH, shiftedPath);
                allParts.add(part);
                returnPathParts.add(part);
            } else if (path[0].equals("methodName")) {
                allParts.add(new Part(PartType.METHOD_NAME));
            } else {
                String original = Joiner.on('.').join(path);
                logger.warn("invalid template substitution: {}", original);
                allParts.add(new ConstantPart("{{" + original + "}}"));
            }
            curr = matcher.end();
        }
        if (curr < template.length()) {
            allParts.add(new ConstantPart(template.substring(curr)));
        }
        return new DynamicAdviceMessageTemplate(allParts.build(), thisPathParts.build(),
                argPathParts.build(), returnPathParts.build());
    }

    private DynamicAdviceMessageTemplate(ImmutableList<Part> allParts,
            ImmutableList<ValuePathPart> thisPathParts, ImmutableList<ArgPathPart> argPathParts,
            ImmutableList<ValuePathPart> returnPathParts) {
        this.allParts = allParts;
        this.thisPathParts = thisPathParts;
        this.argPathParts = argPathParts;
        this.returnPathParts = returnPathParts;
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

    static class ArgPathPart extends Part {

        private final int argNumber;
        private final String[] propertyPath;

        private ArgPathPart(int argNumber, String[] propertyPath) {
            super(PartType.ARG_PATH);
            this.argNumber = argNumber;
            this.propertyPath = propertyPath;
        }

        int getArgNumber() {
            return argNumber;
        }

        String[] getPropertyPath() {
            return propertyPath;
        }
    }

    static class ValuePathPart extends Part {

        private final String[] propertyPath;

        private ValuePathPart(PartType partType, String[] propertyPath) {
            super(partType);
            this.propertyPath = propertyPath;
        }

        String[] getPropertyPath() {
            return propertyPath;
        }
    }
}

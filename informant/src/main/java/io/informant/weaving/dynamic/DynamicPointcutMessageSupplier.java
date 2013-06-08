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
package io.informant.weaving.dynamic;

import checkers.nullness.quals.Nullable;

import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.api.Span;
import io.informant.markers.UsedByGeneratedBytecode;
import io.informant.weaving.dynamic.DynamicPointcutMessageTemplate.ArgPathPart;
import io.informant.weaving.dynamic.DynamicPointcutMessageTemplate.ConstantPart;
import io.informant.weaving.dynamic.DynamicPointcutMessageTemplate.Part;
import io.informant.weaving.dynamic.DynamicPointcutMessageTemplate.ValuePathPart;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicPointcutMessageSupplier extends MessageSupplier {

    private final DynamicPointcutMessageTemplate template;
    private final String[] resolvedThisPathParts;
    private final String[] resolvedArgPathParts;
    @Nullable
    private volatile String[] resolvedReturnValuePathParts;
    private final String methodName;

    @UsedByGeneratedBytecode
    public static DynamicPointcutMessageSupplier create(DynamicPointcutMessageTemplate template,
            Object target, String methodName, Object... args) {
        // render paths to strings immediately in case the objects are mutable
        String[] resolvedThisPathParts = new String[template.getThisPathParts().size()];
        int i = 0;
        for (ValuePathPart part : template.getThisPathParts()) {
            resolvedThisPathParts[i++] =
                    String.valueOf(Beans.value(target, part.getPropertyPath()));
        }
        String[] resolvedArgPathParts = new String[template.getArgPathParts().size()];
        i = 0;
        for (ArgPathPart part : template.getArgPathParts()) {
            resolvedArgPathParts[i++] =
                    String.valueOf(Beans.value(args[part.getArgNumber()], part.getPropertyPath()));
        }
        return new DynamicPointcutMessageSupplier(template, resolvedThisPathParts,
                resolvedArgPathParts, methodName);
    }

    private DynamicPointcutMessageSupplier(DynamicPointcutMessageTemplate template,
            String[] resolvedThisPathParts, String[] resolvedArgPathParts, String methodName) {
        this.template = template;
        this.resolvedThisPathParts = resolvedThisPathParts;
        this.resolvedArgPathParts = resolvedArgPathParts;
        this.methodName = methodName;
    }

    public void setReturnValue(Object returnValue) {
        // render the return value to strings immediately in case it is mutable
        String[] resolvedReturnValuePathParts =
                new String[template.getReturnPathParts().size()];
        int i = 0;
        for (ValuePathPart part : template.getReturnPathParts()) {
            resolvedReturnValuePathParts[i++] =
                    String.valueOf(Beans.value(returnValue, part.getPropertyPath()));
        }
        this.resolvedReturnValuePathParts = resolvedReturnValuePathParts;
    }

    @Override
    public Message get() {
        return Message.from(getMessageText());
    }

    @UsedByGeneratedBytecode
    public String getMessageText() {
        StringBuilder sb = new StringBuilder();
        int thisPathPartIndex = 0;
        int argPathPartIndex = 0;
        int returnValuePathPartIndex = 0;
        for (Part part : template.getAllParts()) {
            switch (part.getType()) {
                case CONSTANT:
                    sb.append(((ConstantPart) part).getConstant());
                    break;
                case THIS_PATH:
                    sb.append(resolvedThisPathParts[thisPathPartIndex++]);
                    break;
                case ARG_PATH:
                    sb.append(resolvedArgPathParts[argPathPartIndex++]);
                    break;
                case RETURN_PATH:
                    if (resolvedReturnValuePathParts != null) {
                        sb.append(resolvedReturnValuePathParts[returnValuePathPartIndex++]);
                    }
                    break;
                case METHOD_NAME:
                    sb.append(methodName);
                    break;
            }
        }
        return sb.toString();
    }

    @UsedByGeneratedBytecode
    public static void updateWithReturnValue(Span span, Object returnValue) {
        ((DynamicPointcutMessageSupplier) span.getMessageSupplier()).setReturnValue(String
                .valueOf(returnValue));
    }
}

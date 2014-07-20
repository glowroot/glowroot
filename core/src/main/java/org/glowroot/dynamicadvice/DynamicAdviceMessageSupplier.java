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
package org.glowroot.dynamicadvice;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.Span;
import org.glowroot.dynamicadvice.DynamicAdviceMessageTemplate.ArgPathPart;
import org.glowroot.dynamicadvice.DynamicAdviceMessageTemplate.ConstantPart;
import org.glowroot.dynamicadvice.DynamicAdviceMessageTemplate.Part;
import org.glowroot.dynamicadvice.DynamicAdviceMessageTemplate.PartType;
import org.glowroot.dynamicadvice.DynamicAdviceMessageTemplate.ValuePathPart;
import org.glowroot.markers.UsedByGeneratedBytecode;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByGeneratedBytecode
public class DynamicAdviceMessageSupplier extends MessageSupplier {

    private final DynamicAdviceMessageTemplate template;
    private final String[] resolvedReceiverPathParts;
    private final String[] resolvedArgPathParts;
    private volatile String/*@Nullable*/[] resolvedReturnValuePathParts;
    private final String methodName;

    @UsedByGeneratedBytecode
    public static DynamicAdviceMessageSupplier create(DynamicAdviceMessageTemplate template,
            Object receiver, String methodName, @Nullable Object... args) {
        // render paths to strings immediately in case the objects are mutable
        String[] resolvedReceiverPathParts = new String[template.getThisPathParts().size()];
        int i = 0;
        for (ValuePathPart part : template.getThisPathParts()) {
            resolvedReceiverPathParts[i++] = part.evaluatePart(receiver);
        }
        String[] resolvedArgPathParts = new String[template.getArgPathParts().size()];
        i = 0;
        for (ArgPathPart part : template.getArgPathParts()) {
            if (part.getArgNumber() >= args.length) {
                resolvedArgPathParts[i++] =
                        "<requested arg index out of bounds: " + part.getArgNumber() + ">";
            } else {
                resolvedArgPathParts[i++] = part.evaluatePart(args[part.getArgNumber()]);
            }
        }
        return new DynamicAdviceMessageSupplier(template, resolvedReceiverPathParts,
                resolvedArgPathParts, methodName);
    }

    private DynamicAdviceMessageSupplier(DynamicAdviceMessageTemplate template,
            String[] resolvedReceiverPathParts, String[] resolvedArgPathParts, String methodName) {
        this.template = template;
        this.resolvedReceiverPathParts = resolvedReceiverPathParts;
        this.resolvedArgPathParts = resolvedArgPathParts;
        this.methodName = methodName;
    }

    public void setReturnValue(@Nullable Object returnValue) {
        // render the return value to strings immediately in case it is mutable
        String[] parts = new String[template.getReturnPathParts().size()];
        int i = 0;
        for (ValuePathPart part : template.getReturnPathParts()) {
            parts[i++] = part.evaluatePart(returnValue);
        }
        this.resolvedReturnValuePathParts = parts;
    }

    @Override
    public Message get() {
        return Message.from(getMessageText());
    }

    @UsedByGeneratedBytecode
    public String getMessageText() {
        StringBuilder sb = new StringBuilder();
        int receiverPathPartIndex = 0;
        int argPathPartIndex = 0;
        int returnValuePathPartIndex = 0;
        for (Part part : template.getAllParts()) {
            PartType partType = part.getType();
            switch (partType) {
                case CONSTANT:
                    sb.append(((ConstantPart) part).getConstant());
                    break;
                case THIS_PATH:
                    sb.append(resolvedReceiverPathParts[receiverPathPartIndex++]);
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
                default:
                    throw new AssertionError("Unknown PartType enum: " + partType);
            }
        }
        return sb.toString();
    }

    @UsedByGeneratedBytecode
    public static void updateWithReturnValue(Span span, @Nullable Object returnValue) {
        DynamicAdviceMessageSupplier messageSupplier =
                (DynamicAdviceMessageSupplier) span.getMessageSupplier();
        if (messageSupplier != null) {
            // can be null if max spans was exceeded
            messageSupplier.setReturnValue(returnValue);
        }
    }
}

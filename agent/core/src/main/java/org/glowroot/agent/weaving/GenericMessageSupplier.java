/*
 * Copyright 2013-2016 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.weaving.MessageTemplate.ArgPathPart;
import org.glowroot.agent.weaving.MessageTemplate.ConstantPart;
import org.glowroot.agent.weaving.MessageTemplate.Part;
import org.glowroot.agent.weaving.MessageTemplate.PartType;
import org.glowroot.agent.weaving.MessageTemplate.ValuePathPart;
import org.glowroot.common.util.UsedByGeneratedBytecode;

@UsedByGeneratedBytecode
public class GenericMessageSupplier extends MessageSupplier {

    private final MessageTemplate template;
    private final String[] resolvedReceiverPathParts;
    private final String[] resolvedArgPathParts;
    private volatile String /*@MonotonicNonNull*/[] resolvedReturnValuePathParts;
    private final String methodName;

    @UsedByGeneratedBytecode
    public static GenericMessageSupplier create(MessageTemplate template, Object receiver,
            String methodName, @Nullable Object... args) {
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
        return new GenericMessageSupplier(template, resolvedReceiverPathParts, resolvedArgPathParts,
                methodName);
    }

    private GenericMessageSupplier(MessageTemplate template, String[] resolvedReceiverPathParts,
            String[] resolvedArgPathParts, String methodName) {
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
        return Message.create(getMessageText());
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
    public static void updateWithReturnValue(TraceEntry traceEntry, @Nullable Object returnValue) {
        GenericMessageSupplier messageSupplier =
                (GenericMessageSupplier) traceEntry.getMessageSupplier();
        if (messageSupplier != null) {
            // messageSupplier can be null if max trace entries was exceeded
            messageSupplier.setReturnValue(returnValue);
        }
    }
}

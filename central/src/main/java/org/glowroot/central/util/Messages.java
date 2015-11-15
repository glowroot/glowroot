/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.central.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import com.google.common.collect.Lists;
import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.Parser;

public class Messages {

    private Messages() {}

    public static ByteBuffer toByteBuffer(List<? extends AbstractMessageLite> messages)
            throws IOException {
        // TODO optimize byte copying
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (AbstractMessageLite message : messages) {
            message.writeDelimitedTo(output);
        }
        return ByteBuffer.wrap(output.toByteArray());
    }

    public static <T extends /*@NonNull*/AbstractMessageLite> List<T> parseDelimitedFrom(
            ByteBuffer byteBuf, Parser<T> parser) throws IOException {
        List<T> messages = Lists.newArrayList();
        try (InputStream input = new ByteBufferInputStream(byteBuf)) {
            T message;
            while ((message = parser.parseDelimitedFrom(input)) != null) {
                messages.add(message);
            }
        }
        return messages;
    }
}

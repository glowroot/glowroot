/*
 * Copyright 2015-2016 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Parser;

import org.glowroot.common.util.SizeLimitBypassingParser;

public class Messages {

    private Messages() {}

    public static ByteBuffer toByteBuffer(List<? extends AbstractMessage> messages)
            throws IOException {
        // TODO optimize byte copying
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (AbstractMessage message : messages) {
            message.writeDelimitedTo(output);
        }
        return ByteBuffer.wrap(output.toByteArray());
    }

    public static <T extends /*@NonNull*/ AbstractMessage> List<T> parseDelimitedFrom(
            @Nullable ByteBuffer byteBuf, Parser<T> parser) throws IOException {
        if (byteBuf == null) {
            return ImmutableList.of();
        }
        SizeLimitBypassingParser<T> sizeLimitBypassingParser =
                new SizeLimitBypassingParser<>(parser);
        List<T> messages = Lists.newArrayList();
        try (InputStream input = new ByteBufferInputStream(byteBuf)) {
            T message;
            while ((message = sizeLimitBypassingParser.parseDelimitedFrom(input)) != null) {
                messages.add(message);
            }
        }
        return messages;
    }
}

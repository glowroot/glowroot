/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.api;

import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.internal.ReadableMessage;

/**
 * The detail map can contain only {@link String}, {@link Double}, {@link Boolean} and null values.
 * It can also contain nested lists of {@link String}, {@link Double}, {@link Boolean} and null
 * values (in particular, lists elements cannot be other lists or maps). And it can contain any
 * level of nested maps whose keys are {@link String} and whose values are one of the above types
 * (including lists). The detail map cannot have null keys.
 * 
 * Lists are supported to simulate multimaps, e.g. for http request parameters and http headers,
 * both of which can have multiple values for the same key.
 * 
 * As an extra bonus, detail map can also contain org.glowroot.api.Optional values which is useful
 * for Maps that do not accept null values, e.g.
 * org.glowroot.shaded.google.common.collect.ImmutableMap.
 * 
 * The detail map does not need to be thread safe as long as it is only instantiated in response to
 * either MessageSupplier.get() or Message.getDetail() which are called by the thread that needs the
 * map.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class Message {

    private static final int MESSAGE_CHAR_LIMIT;

    private static final ImmutableMap<String, Object> EMPTY_DETAIL = ImmutableMap.of();

    static {
        // default is 512k characters so that memory limit is 1mb since 1 character = 2 bytes
        MESSAGE_CHAR_LIMIT = Integer.getInteger("glowroot.message.char.limit", 512 * 1024);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static Message from(@Nullable String message) {
        return new MessageImpl(message, new String[0], EMPTY_DETAIL);
    }

    // does not copy args
    public static Message from(String template, @Nullable String... args) {
        return new MessageImpl(template, args, EMPTY_DETAIL);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static Message withDetail(@Nullable String message,
            Map<String, ? extends /*@Nullable*/Object> detail) {
        return new MessageImpl(message, new String[0], detail);
    }

    private Message() {}

    // implementing ReadableMessage is just a way to access this class from glowroot without making
    // it (obviously) accessible to plugin implementations
    private static class MessageImpl extends Message implements ReadableMessage {

        private static final Logger logger = LoggerFactory.getLogger(MessageImpl.class);

        @Nullable
        private final String template;
        private final/*@Nullable*/String[] args;
        private final Map<String, ? extends /*@Nullable*/Object> detail;

        private MessageImpl(@Nullable String template, @Nullable String[] args,
                Map<String, ? extends /*@Nullable*/Object> detail) {
            this.template = truncateToMessageCharLimit(template);
            for (int i = 0; i < args.length; i++) {
                args[i] = truncateToMessageCharLimit(args[i]);
            }
            this.args = args;
            this.detail = detail;
        }

        @Override
        public String getText() {
            if (template == null) {
                return "";
            }
            if (args.length == 0) {
                return template;
            }
            // Matcher.appendReplacement() can't be used here since appendReplacement() applies
            // special meaning to slashes '\' and dollar signs '$' in the replacement text.
            // These special characters can be escaped in the replacement text via
            // Matcher.quoteReplacemenet(), but the implementation below feels slightly more
            // performant and not much more complex
            StringBuilder text = new StringBuilder();
            int curr = 0;
            int next;
            int argIndex = 0;
            while ((next = template.indexOf("{}", curr)) != -1) {
                text.append(template.substring(curr, next));
                if (argIndex >= args.length) {
                    text.append("-- not enough args provided for template --");
                    logger.warn("not enough args provided for template: {}", template);
                    break;
                }
                // arg may be null but that is ok, StringBuilder will append "null"
                text.append(args[argIndex++]);
                curr = next + 2; // +2 to skip over "{}"
            }
            text.append(template.substring(curr));
            return truncateToMessageCharLimit(text.toString());
        }

        @Override
        public Map<String, ? extends /*@Nullable*/Object> getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("template", template)
                    .add("args", args)
                    .add("detail", detail)
                    .toString();
        }

        @PolyNull
        private static String truncateToMessageCharLimit(@PolyNull String s) {
            if (s == null || s.length() <= MESSAGE_CHAR_LIMIT) {
                return s;
            } else {
                return s.substring(0, MESSAGE_CHAR_LIMIT) + " [truncated to " + MESSAGE_CHAR_LIMIT
                        + " characters]";
            }
        }
    }
}

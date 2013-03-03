/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.api;

import io.informant.api.internal.ReadableMessage;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

/**
 * The detail map can contain only {@link String}, {@link Double}, {@link Boolean} and null value
 * types. It can also contain nested maps (which have the same restrictions on value types,
 * including additional levels of nested maps). The detail map cannot have null keys.
 * 
 * As an extra bonus, detail map can also contain io.informant.api.Optional values which is useful
 * for Maps that do not accept null values, e.g.
 * io.informant.shaded.google.common.collect.ImmutableMap.
 * 
 * The detail map does not need to be thread safe as long as it is only instantiated in response to
 * either MessageSupplier.get() or Message.getDetail() which are called by the thread that needs the
 * map.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class Message {

    private static final ImmutableMap<String, Object> EMPTY_DETAIL = ImmutableMap.of();

    public static Message from(@Nullable String message) {
        return new MessageImpl(message, new String[0], EMPTY_DETAIL);
    }

    public static Message from(String template, @Nullable String... args) {
        return new MessageImpl(template, args, EMPTY_DETAIL);
    }

    public static Message withDetail(@Nullable String message,
            @ReadOnly Map<String, ? extends /*@Nullable*/Object> detail) {
        return new MessageImpl(message, new String[0], detail);
    }

    // implementing ReadableMessage is just a way to access this class from io.informant.core
    // package without making it (obviously) accessible to plugin implementations
    private static class MessageImpl extends Message implements ReadableMessage {

        private static final Logger logger = LoggerFactory.getLogger(MessageImpl.class);

        @Nullable
        private final String template;
        private final/*@Nullable*/String[] args;
        @ReadOnly
        private final Map<String, ? extends /*@Nullable*/Object> detail;

        private MessageImpl(@Nullable String template, @Nullable String[] args,
                @ReadOnly Map<String, ? extends /*@Nullable*/Object> detail) {
            this.template = template;
            this.args = args;
            this.detail = detail;
        }

        public String getText() {
            if (template == null) {
                return "";
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
            return text.toString();
        }

        @ReadOnly
        public Map<String, ? extends /*@Nullable*/Object> getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("template", template)
                    .add("args", args)
                    .add("detail", detail)
                    .toString();
        }
    }
}

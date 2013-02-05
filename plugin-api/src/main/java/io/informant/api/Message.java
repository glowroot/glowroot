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

import java.util.Map;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

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

    public abstract String getText();

    @ReadOnly
    @Nullable
    public abstract Map<String, ? extends /*@Nullable*/Object> getDetail();

    // keep constructor protected, if plugins could subclass, then extra validation would be
    // required to ensure the Message contract is maintained
    protected Message() {}

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("text", getText())
                .add("detail", getDetail())
                .toString();
    }

    public static Message from(String message) {
        return new TemplateMessage(message, null, null);
    }

    public static Message from(String template, String... args) {
        return new TemplateMessage(template, args, null);
    }

    public static Message withDetail(String message,
            @ReadOnly Map<String, ? extends /*@Nullable*/Object> detail) {
        return new TemplateMessage(message, null, detail);
    }

    private static class TemplateMessage extends Message {

        private static final Logger logger = LoggerFactory.getLogger(TemplateMessage.class);

        private final String template;
        private final String/*@Nullable*/[] args;
        @ReadOnly
        @Nullable
        private final Map<String, ? extends /*@Nullable*/Object> detail;

        private TemplateMessage(String template, String/*@Nullable*/[] args,
                @ReadOnly @Nullable Map<String, ? extends /*@Nullable*/Object> detail) {
            this.template = template;
            this.args = args;
            this.detail = detail;
        }

        @Override
        public String getText() {
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
                if (args == null || argIndex >= args.length) {
                    text.append("-- not enough args provided for template --");
                    logger.warn("not enough args provided for template: {}", template);
                    break;
                }
                text.append(args[argIndex++]);
                curr = next + 2; // +2 to skip over "{}"
            }
            text.append(template.substring(curr));
            return text.toString();
        }

        @Override
        @ReadOnly
        @Nullable
        public Map<String, ? extends /*@Nullable*/Object> getDetail() {
            return detail;
        }
    }
}

/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.api;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Objects;

/**
 * The detail map can contain {@link String}, {@link Double}, {@link Boolean} and null value types.
 * It can also contain nested maps (which have the same restrictions on value types, including
 * additional levels of nested maps). The detail map cannot have null keys.
 * 
 * As an extra bonus, detail map can also contain
 * org.informantproject.shaded.google.common.base.Optional values which is useful for Maps that do
 * not accept null values, e.g. org.informantproject.shaded.google.common.collect.ImmutableMap.
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

    @Nullable
    public abstract Map<String, ?> getDetail();

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

    // the objects in args must be thread safe
    public static Message from(String template, Object... args) {
        return new TemplateMessage(template, args, null);
    }

    // the objects in detail must be thread safe
    public static Message withDetail(String message, Map<String, ?> detail) {
        return new TemplateMessage(message, null, detail);
    }

    private static class TemplateMessage extends Message {

        private static final Logger logger = LoggerFactory.getLogger(TemplateMessage.class);

        private final String template;
        @Nullable
        private final Object[] args;
        @Nullable
        private final Map<String, ?> detail;

        private TemplateMessage(String template, @Nullable Object[] args,
                @Nullable Map<String, ?> detail) {
            this.template = template;
            this.args = args;
            this.detail = detail;
        }

        @Override
        public String getText() {
            StringBuilder text = new StringBuilder();
            int curr = 0;
            int next;
            int argIndex = 0;
            while ((next = template.indexOf("{{", curr)) != -1) {
                text.append(template.substring(curr, next));
                curr = next;
                next = template.indexOf("}}", curr);
                if (next == -1) {
                    logger.error("unclosed {{ in template: {}", template);
                    text.append(" -- error, unclosed {{ --");
                    break;
                }
                if (args == null || argIndex >= args.length) {
                    logger.error("not enough args provided for template: {}", template);
                    text.append(" -- error, not enough args --");
                    break;
                }
                String argText;
                try {
                    argText = String.valueOf(args[argIndex++]);
                } catch (Throwable t) {
                    argText = "an error occurred calling toString() on an instance of "
                            + args[argIndex - 1].getClass().getName();
                    logger.warn(argText + ", while rendering template: " + template, t);
                }
                text.append(argText);
                curr = next + 2; // +2 to skip over "}}"
            }
            text.append(template.substring(curr));
            return text.toString();
        }

        @Override
        @Nullable
        public Map<String, ?> getDetail() {
            return detail;
        }
    }
}

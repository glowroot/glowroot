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

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For convenience, this is both a Message and a Supplier<Message> (supplying itself).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TemplateMessage implements Message, Supplier<Message> {

    private static final Logger logger = LoggerFactory.getLogger(TemplateMessage.class);

    private final String template;
    @Nullable
    private final Object[] args;
    @Nullable
    private final Map<String, ?> detail;

    public static TemplateMessage of(String message) {
        return new TemplateMessage(message, null, null);
    }

    public static TemplateMessage of(String template, Object... args) {
        return new TemplateMessage(template, args, null);
    }

    // the objects in args must be thread safe
    public static TemplateMessage of(String template, List<?> args) {
        return new TemplateMessage(template, args.toArray(), null);
    }

    // the objects in detail must be thread safe
    public static TemplateMessage of(String message, Map<String, ?> detail) {
        return new TemplateMessage(message, null, detail);
    }

    // the objects in args and detail must be thread safe
    public static TemplateMessage of(String template, List<?> args, Map<String, ?> detail) {
        return new TemplateMessage(template, args.toArray(), detail);
    }

    private TemplateMessage(String template, @Nullable Object[] args,
            @Nullable Map<String, ?> detail) {
        this.template = template;
        this.args = args;
        this.detail = detail;
    }

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
            text.append(args[argIndex++]);
            curr = next + 2; // +2 to skip over "}}"
        }
        text.append(template.substring(curr));
        return text.toString();
    }

    @Nullable
    public Map<String, ?> getDetail() {
        return detail;
    }

    public Message get() {
        return this;
    }
}

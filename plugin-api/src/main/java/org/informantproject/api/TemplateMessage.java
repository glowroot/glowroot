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
import javax.annotation.concurrent.Immutable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For convenience, this is both a Message and a Supplier<Message> (supplying itself).
 * 
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TemplateMessage implements Message, Supplier<Message> {

    private static final Logger logger = LoggerFactory.getLogger(TemplateMessage.class);

    private final String template;
    @Nullable
    private final Object[] args;
    @Nullable
    private final Map<String, ?> contextMap;

    public static TemplateMessage of(String message) {
        return new TemplateMessage(message, null, null);
    }

    public static TemplateMessage of(String template, Object... args) {
        return new TemplateMessage(template, args, null);
    }

    public static TemplateMessage of(String template, List<?> args) {
        return new TemplateMessage(template, args.toArray(), null);
    }

    public static TemplateMessage of(String message, Map<String, ?> contextMap) {
        return new TemplateMessage(message, null, contextMap);
    }

    public static TemplateMessage of(String template, Object[] args, Map<String, ?> contextMap) {
        return new TemplateMessage(template, args, contextMap);
    }

    private TemplateMessage(String template, @Nullable Object[] args,
            @Nullable Map<String, ?> contextMap) {
        this.template = template;
        this.args = args;
        this.contextMap = contextMap;
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
    public Map<String, ?> getContextMap() {
        return contextMap;
    }

    public Message get() {
        return this;
    }
}

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

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Message {

    private static final Logger logger = LoggerFactory.getLogger(Message.class);

    private final String template;
    private final Object[] args;
    private final ContextMap context;

    private Message(String template, @Nullable Object[] args, @Nullable ContextMap context) {
        this.template = template;
        this.args = args;
        this.context = context;
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

    public String getTemplate() {
        return template;
    }

    @Nullable
    public Object[] getArgs() {
        return args;
    }

    @Nullable
    public ContextMap getContext() {
        return context;
    }

    public static Message of(String message) {
        return new Message(message, null, null);
    }

    public static Message of(String template, Object... args) {
        return new Message(template, args, null);
    }

    public static Message of(String template, List<?> args) {
        return new Message(template, args.toArray(), null);
    }

    public static Message withContext(String message, ContextMap context) {
        return new Message(message, null, context);
    }

    public static Message withContextMap(String template, Object[] args, ContextMap context) {
        return new Message(template, args, context);
    }
}

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
 * The context map can contain {@link String}, {@link Double}, {@link Boolean} and null value types.
 * It can also contain nested maps (which have the same restrictions on value types, including
 * additional levels of nested maps). The context map cannot have null keys.
 * 
 * As an extra bonus, it also handles org.informantproject.shaded.google.common.base.Optional which
 * is useful for Maps that do not accept null values, e.g.
 * org.informantproject.shaded.google.common.collect.ImmutableMap.
 * 
 * The context map does not need to be thread safe as long as it is only instantiated in response to
 * either Supplier<Message>.get() or Message.getContext() which are called by the thread that needs
 * the map.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Message {

    private static final Logger logger = LoggerFactory.getLogger(Message.class);

    private final String template;
    @Nullable
    private final Object[] args;
    @Nullable
    private final Map<String, ?> contextMap;

    private Message(String template, @Nullable Object[] args, @Nullable Map<String, ?> contextMap) {
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

    public String getTemplate() {
        return template;
    }

    @Nullable
    public Object[] getArgs() {
        return args;
    }

    @Nullable
    public Map<String, ?> getContextMap() {
        return contextMap;
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

    public static Message withContextMap(String message, Map<String, ?> contextMap) {
        return new Message(message, null, contextMap);
    }

    public static Message withContextMap(String template, Object[] args,
            Map<String, ?> contextMap) {

        return new Message(template, args, contextMap);
    }
}

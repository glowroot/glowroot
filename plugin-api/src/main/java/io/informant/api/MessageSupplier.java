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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class MessageSupplier {

    public abstract Message get();

    protected MessageSupplier() {}

    public static MessageSupplier from(final String message) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                return Message.from(message);
            }
        };
    }

    // the objects in args must be thread safe
    public static MessageSupplier from(final String template, final String... args) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                return Message.from(template, args);
            }
        };
    }

    // the objects in detail must be thread safe
    public static MessageSupplier withDetail(final String message, final Map<String, ?> detail) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                return Message.withDetail(message, detail);
            }
        };
    }
}

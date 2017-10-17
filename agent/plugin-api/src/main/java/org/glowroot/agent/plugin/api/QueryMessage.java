/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.plugin.api;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.plugin.api.internal.ReadableQueryMessage;

/**
 * The detail map can contain only {@link String}, {@link Number}, {@link Boolean} and null values.
 * It can also contain nested lists of {@link String}, {@link Number}, {@link Boolean} and null
 * values (in particular, lists elements cannot other lists or maps). And it can contain any level
 * of nested maps whose keys are {@link String} and whose values are one of the above types
 * (including lists). The detail map cannot have null keys.
 * 
 * Lists are supported to simulate multimaps, e.g. for http request parameters and http headers,
 * both of which can have multiple values for the same key.
 * 
 * As an extra bonus, detail map can also contain
 * org.glowroot.agent.shaded.org.google.common.base.Optional values which is useful for Maps that do
 * not accept null values, e.g. org.glowroot.agent.shaded.org.google.common.collect.ImmutableMap.
 * 
 * The detail map does not need to be thread safe as long as it is only instantiated in response to
 * either MessageSupplier.get() or Message.getDetail() which are called by the thread that needs the
 * map.
 */
public abstract class QueryMessage {

    private static final ImmutableMap<String, Object> EMPTY_DETAIL = ImmutableMap.of();

    public static QueryMessage create(String prefix) {
        return new QueryMessageImpl(prefix, "", EMPTY_DETAIL);
    }

    public static QueryMessage create(String prefix, String suffix) {
        return new QueryMessageImpl(prefix, suffix, EMPTY_DETAIL);
    }

    public static QueryMessage create(String prefix, String suffix,
            Map<String, ?> detail) {
        return new QueryMessageImpl(prefix, suffix, detail);
    }

    private QueryMessage() {}

    // implementing ReadableQueryMessage is just a way to access this class from glowroot without
    // making it (obviously) accessible to plugin implementations
    private static class QueryMessageImpl extends QueryMessage implements ReadableQueryMessage {

        private final String prefix;
        private final String suffix;
        private final Map<String, ?> detail;

        private QueryMessageImpl(String prefix, String suffix,
                Map<String, ?> detail) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.detail = detail;
        }

        @Override
        public String getPrefix() {
            return prefix;
        }

        @Override
        public String getSuffix() {
            return suffix;
        }

        @Override
        public Map<String, ?> getDetail() {
            return detail;
        }
    }
}

/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.local.store;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;

class ObjectMappers {

    private ObjectMappers() {}

    static <T> T readRequiredValue(ObjectMapper mapper, String content, Class<T> valueType)
            throws IOException {
        T value = mapper.readValue(content, valueType);
        if (value == null) {
            throw new JsonMappingException("Content is json null");
        }
        return value;
    }

    @EnsuresNonNull("#1")
    static <T> void checkRequiredProperty(T reference, String fieldName)
            throws JsonMappingException {
        if (reference == null) {
            throw new JsonMappingException("Null value not allowed for field: " + fieldName);
        }
    }

    @SuppressWarnings("return.type.incompatible")
    static <T> List</*@NonNull*/T> orEmpty(@Nullable List<T> list, String fieldName)
            throws JsonMappingException {
        if (list == null) {
            return ImmutableList.of();
        }
        for (T item : list) {
            if (item == null) {
                throw new JsonMappingException("Null items are not allowed in array field: "
                        + fieldName);
            }
        }
        return list;
    }
}

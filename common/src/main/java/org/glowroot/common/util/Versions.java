/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.google.protobuf.AbstractMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Versions {

    private static final Logger logger = LoggerFactory.getLogger(Versions.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private Versions() {}

    public static String getJsonVersion(Object obj) {
        try {
            return Hashing.sha1().hashString(mapper.writeValueAsString(obj), Charsets.UTF_8)
                    .toString();
        } catch (JsonProcessingException e) {
            logger.error(e.getMessage(), e);
            return "0000000000000000000000000000000000000000";
        }
    }

    public static String getVersion(AbstractMessage obj) {
        return Hashing.sha1().hashBytes(obj.toByteArray()).toString();
    }
}

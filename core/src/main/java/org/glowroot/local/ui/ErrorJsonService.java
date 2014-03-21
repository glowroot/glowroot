/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.ErrorAggregateQuery;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.markers.Singleton;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read error data, bound to /backend/error.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class ErrorJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorJsonService.class);

    private final SnapshotDao snapshotDao;

    ErrorJsonService(SnapshotDao snapshotDao) {
        this.snapshotDao = snapshotDao;
    }

    @GET("/backend/error/aggregates")
    String getAggregates(String content) throws IOException {
        logger.debug("getAggregates(): content={}", content);
        ObjectMapper mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        ErrorAggregatesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, ErrorAggregatesRequest.class);
        ErrorAggregateQuery query = new ErrorAggregateQuery(request.getFrom(), request.getTo(),
                request.getIncludes(), request.getExcludes(), request.getLimit());
        return mapper.writeValueAsString(snapshotDao.readErrorAggregates(query));
    }

    private static class ErrorAggregatesRequest {

        private final long from;
        private final long to;
        private final List<String> includes;
        private final List<String> excludes;
        private final int limit;

        @JsonCreator
        ErrorAggregatesRequest(
                @JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("includes") @Nullable List<String> includes,
                @JsonProperty("excludes") @Nullable List<String> excludes,
                @JsonProperty("limit") @Nullable Integer limit)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(limit, "limit");
            this.from = from;
            this.to = to;
            this.includes = orEmpty(includes);
            this.excludes = orEmpty(excludes);
            this.limit = limit;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        private List<String> getIncludes() {
            return includes;
        }

        private List<String> getExcludes() {
            return excludes;
        }

        private int getLimit() {
            return limit;
        }

        @ReadOnly
        private static <T extends /*@NonNull*/Object> List<T> orEmpty(
                @ReadOnly @Nullable List<T> list) {
            if (list == null) {
                return ImmutableList.of();
            }
            return list;
        }
    }
}

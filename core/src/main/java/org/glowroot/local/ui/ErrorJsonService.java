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
import java.util.Locale;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.ErrorAggregate;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.local.store.TracePointQuery.StringComparator;
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
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final SnapshotDao snapshotDao;

    ErrorJsonService(SnapshotDao snapshotDao) {
        this.snapshotDao = snapshotDao;
    }

    @GET("/backend/error/aggregates")
    String getAggregates(String content) throws IOException {
        logger.debug("getAggregates(): content={}", content);
        AggregatesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, AggregatesRequest.class);
        StringComparator comparator = null;
        String errorComparator = request.getErrorComparator();
        if (errorComparator != null) {
            comparator = StringComparator.valueOf(errorComparator.toUpperCase(Locale.ENGLISH));
        }
        List<ErrorAggregate> errorAggregates = snapshotDao.readErrorAggregates(request.getFrom(),
                request.getTo(), comparator, request.getError(), request.getLimit());
        return mapper.writeValueAsString(errorAggregates);
    }

    private static class AggregatesRequest {

        private final long from;
        private final long to;
        @Nullable
        private final String errorComparator;
        @Nullable
        private final String error;
        private final int limit;

        @JsonCreator
        AggregatesRequest(
                @JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("errorComparator") @Nullable String errorComparator,
                @JsonProperty("error") @Nullable String error,
                @JsonProperty("limit") @Nullable Integer limit)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(limit, "limit");
            this.from = from;
            this.to = to;
            this.errorComparator = errorComparator;
            this.error = error;
            this.limit = limit;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        @Nullable
        private String getErrorComparator() {
            return errorComparator;
        }

        @Nullable
        private String getError() {
            return error;
        }

        private int getLimit() {
            return limit;
        }
    }
}

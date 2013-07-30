/*
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
package io.informant.local.ui;

import java.io.IOException;
import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.local.store.AggregateDao;
import io.informant.local.store.AggregatePoint;
import io.informant.local.store.GroupingAggregate;
import io.informant.markers.Singleton;

import static io.informant.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read aggregate data, bound to /backend/aggregate.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class AggregateJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AggregateJsonService.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateDao aggregateDao;

    private final long fixedAggregateIntervalSeconds;

    AggregateJsonService(AggregateDao aggregateDao, long fixedAggregateIntervalSeconds) {
        this.aggregateDao = aggregateDao;
        this.fixedAggregateIntervalSeconds = fixedAggregateIntervalSeconds;
    }

    @JsonServiceMethod
    String getPoints(String content) throws IOException {
        logger.debug("getPoints(): content={}", content);
        PointsRequest request =
                ObjectMappers.readRequiredValue(mapper, content, PointsRequest.class);
        List<AggregatePoint> points =
                aggregateDao.readAggregates(request.getFrom(), request.getTo());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeArrayFieldStart("points");
        for (AggregatePoint point : points) {
            jg.writeStartArray();
            jg.writeNumber(point.getCaptureTime());
            long durationAverage;
            if (point.getTraceCount() == 0) {
                durationAverage = 0;
            } else {
                durationAverage = point.getDurationTotal() / point.getTraceCount();
            }
            jg.writeNumber(durationAverage / 1000000000.0);
            jg.writeNumber(point.getTraceCount());
            jg.writeEndArray();
        }
        jg.writeEndArray();
        jg.writeNumberField("fixedAggregateIntervalSeconds", fixedAggregateIntervalSeconds);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getGroupings(String content) throws IOException {
        logger.debug("getGroupings(): content={}", content);
        GroupingsRequest request =
                ObjectMappers.readRequiredValue(mapper, content, GroupingsRequest.class);
        List<GroupingAggregate> groupings = aggregateDao.readGroupingAggregates(request.getFrom(),
                request.getTo(), request.getLimit());
        return mapper.writeValueAsString(groupings);
    }

    private static class PointsRequest {

        private final long from;
        private final long to;

        @JsonCreator
        PointsRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to) throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            this.from = from;
            this.to = to;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }
    }

    private static class GroupingsRequest {

        private final long from;
        private final long to;
        private final int limit;

        @JsonCreator
        GroupingsRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("limit") @Nullable Integer limit) throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(limit, "limit");
            this.from = from;
            this.to = to;
            this.limit = limit;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        public int getLimit() {
            return limit;
        }
    }
}

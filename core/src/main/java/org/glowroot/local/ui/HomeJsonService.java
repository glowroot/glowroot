/*
 * Copyright 2011-2014 the original author or authors.
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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregatePoint;
import org.glowroot.local.store.TransactionAggregate;
import org.glowroot.markers.Singleton;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read aggregate data, bound to /backend/home.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class HomeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(HomeJsonService.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateDao aggregateDao;

    HomeJsonService(AggregateDao aggregateDao) {
        this.aggregateDao = aggregateDao;
    }

    @GET("/backend/home/points")
    String getPoints(String content) throws IOException {
        logger.debug("getPoints(): content={}", content);
        PointsRequest request =
                ObjectMappers.readRequiredValue(mapper, content, PointsRequest.class);
        List<AggregatePoint> points =
                aggregateDao.readPoints(request.getFrom(), request.getTo());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeArrayFieldStart("points");
        for (AggregatePoint point : points) {
            jg.writeStartArray();
            jg.writeNumber(point.getCaptureTime());
            long average;
            if (point.getCount() == 0) {
                average = 0;
            } else {
                average = point.getTotalMillis() / point.getCount();
            }
            jg.writeNumber(average / 1000000000.0);
            jg.writeNumber(point.getCount());
            jg.writeEndArray();
        }
        jg.writeEndArray();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/home/transaction-aggregates")
    String getTransactionAggregates(String content) throws IOException {
        logger.debug("getTransactionAggregates(): content={}", content);
        TransactionsRequest request =
                ObjectMappers.readRequiredValue(mapper, content, TransactionsRequest.class);
        List<TransactionAggregate> transactionAggregates = aggregateDao.readTransactionAggregates(
                request.getFrom(), request.getTo(), request.getLimit());
        return mapper.writeValueAsString(transactionAggregates);
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

    private static class TransactionsRequest {

        private final long from;
        private final long to;
        private final int limit;

        @JsonCreator
        TransactionsRequest(@JsonProperty("from") @Nullable Long from,
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

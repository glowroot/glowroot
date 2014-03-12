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
import java.util.Map;
import java.util.Map.Entry;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.SortDirection;
import org.glowroot.local.store.AggregateDao.TransactionAggregateSortColumn;
import org.glowroot.local.store.AggregatePoint;
import org.glowroot.local.store.OverallAggregate;
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
    private final long fixedAggregationIntervalMillis;

    HomeJsonService(AggregateDao aggregateDao, long fixedAggregationIntervalSeconds) {
        this.aggregateDao = aggregateDao;
        fixedAggregationIntervalMillis = fixedAggregationIntervalSeconds * 1000;
    }

    @GET("/backend/home/points")
    String getPoints(String content) throws IOException {
        logger.debug("getPoints(): content={}", content);
        ObjectMapper mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        PointsRequest request =
                ObjectMappers.readRequiredValue(mapper, content, PointsRequest.class);
        List<AggregatePoint> points =
                aggregateDao.readPoints(request.getFrom(), request.getTo());
        Map<String, Map<Long, AggregatePoint>> transactionPoints =
                aggregateDao.readTransactionPoints(request.getFrom(), request.getTo(),
                        request.getTransactionNames());

        List</*@Nullable*/Long> captureTimes = Lists.newArrayList();
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeArrayFieldStart("points");
        AggregatePoint lastPoint = null;
        for (AggregatePoint point : points) {
            writePoint(jg, point);
            captureTimes.add(point.getCaptureTime());
            if (lastPoint == null) {
                continue;
            }
            long millisecondsSinceLastPoint = point.getCaptureTime() - lastPoint.getCaptureTime();
            if (millisecondsSinceLastPoint > fixedAggregationIntervalMillis * 1.5) {
                // add gap when missing collection points (probably jvm was down)
                writeNullPoint(jg);
                captureTimes.add(null);
            }
        }
        jg.writeEndArray();
        jg.writeObjectFieldStart("transactionPoints");
        for (Entry<String, Map<Long, AggregatePoint>> entry : transactionPoints.entrySet()) {
            jg.writeArrayFieldStart(entry.getKey());
            for (Long captureTime : captureTimes) {
                if (captureTime == null) {
                    writeNullPoint(jg);
                    continue;
                }
                AggregatePoint point = entry.getValue().get(captureTime);
                if (point == null) {
                    writeZeroPoint(jg, captureTime);
                    continue;
                }
                writePoint(jg, point);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static void writePoint(JsonGenerator jg, AggregatePoint point) throws IOException {
        jg.writeStartArray();
        jg.writeNumber(point.getCaptureTime());
        double average;
        if (point.getCount() == 0) {
            average = 0;
        } else {
            average = point.getTotalMillis() / point.getCount();
        }
        jg.writeNumber(average / 1000.0);
        jg.writeNumber(point.getCount());
        jg.writeEndArray();
    }

    private static void writeNullPoint(JsonGenerator jg) throws IOException {
        jg.writeNull();
    }

    private static void writeZeroPoint(JsonGenerator jg, long captureTime) throws IOException {
        jg.writeStartArray();
        jg.writeNumber(captureTime);
        jg.writeNumber(0);
        jg.writeNumber(0);
        jg.writeEndArray();
    }

    @GET("/backend/home/aggregates")
    String getAggregates(String content) throws IOException {
        logger.debug("getAggregates(): content={}", content);
        AggregatesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, AggregatesRequest.class);
        OverallAggregate overallAggregate =
                aggregateDao.readOverallAggregate(request.getFrom(), request.getTo());
        String sortAttribute =
                CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, request.getSortAttribute());
        TransactionAggregateSortColumn sortColumn =
                TransactionAggregateSortColumn.valueOf(sortAttribute);
        List<TransactionAggregate> transactionAggregates = aggregateDao.readTransactionAggregates(
                request.getFrom(), request.getTo(), sortColumn, request.getSortDirection(),
                request.getTransactionAggregatesLimit());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("overallAggregate", overallAggregate);
        jg.writeObjectField("transactionAggregates", transactionAggregates);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @ReadOnly
    private static <T extends /*@NonNull*/Object> List<T> orEmpty(
            @ReadOnly @Nullable List<T> list) {
        if (list == null) {
            return ImmutableList.of();
        }
        return list;
    }

    private static class PointsRequest {

        private final long from;
        private final long to;
        private final List<String> transactionNames;

        @JsonCreator
        PointsRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionNames") @Nullable List<String> transactionNames)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            this.from = from;
            this.to = to;
            this.transactionNames = orEmpty(transactionNames);
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        private List<String> getTransactionNames() {
            return transactionNames;
        }
    }

    private static class AggregatesRequest {

        private final long from;
        private final long to;
        private final String sortAttribute;
        private final SortDirection sortDirection;
        private final int transactionAggregatesLimit;

        @JsonCreator
        AggregatesRequest(
                @JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("sortAttribute") @Nullable String sortAttribute,
                @JsonProperty("sortDirection") @Nullable SortDirection sortDirection,
                @JsonProperty("transactionAggregatesLimit") @Nullable Integer transactionAggregatesLimit)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(sortAttribute, "sortAttribute");
            checkRequiredProperty(sortDirection, "sortDirection");
            checkRequiredProperty(transactionAggregatesLimit, "transactionAggregatesLimit");
            this.from = from;
            this.to = to;
            this.sortAttribute = sortAttribute;
            this.sortDirection = sortDirection;
            this.transactionAggregatesLimit = transactionAggregatesLimit;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        private String getSortAttribute() {
            return sortAttribute;
        }

        private SortDirection getSortDirection() {
            return sortDirection;
        }

        private int getTransactionAggregatesLimit() {
            return transactionAggregatesLimit;
        }
    }
}

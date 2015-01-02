/*
 * Copyright 2014-2015 the original author or authors.
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

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.common.Clock;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.ErrorSummaryQuery;
import org.glowroot.local.store.AggregateDao.ErrorSummarySortOrder;
import org.glowroot.local.store.ErrorMessageCount;
import org.glowroot.local.store.ErrorMessageCountMarshaler;
import org.glowroot.local.store.ErrorMessageQuery;
import org.glowroot.local.store.ErrorPoint;
import org.glowroot.local.store.ErrorSummary;
import org.glowroot.local.store.ErrorSummaryMarshaler;
import org.glowroot.local.store.ImmutableErrorMessageQuery;
import org.glowroot.local.store.ImmutableErrorSummaryQuery;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TraceDao;

@JsonService
class ErrorJsonService {

    private static final ObjectMapper mapper;

    static {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final long fixedAggregateIntervalMillis;
    private final DataSeriesHelper dataSeriesHelper;

    ErrorJsonService(AggregateDao aggregateDao, TraceDao traceDao, Clock clock,
            long fixedAggregateIntervalSeconds) {
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
        dataSeriesHelper = new DataSeriesHelper(clock, fixedAggregateIntervalMillis);
    }

    @GET("/backend/error/data")
    String getData(String queryString) throws Exception {
        ErrorDataRequest request =
                QueryStrings.decode(queryString, ErrorDataRequest.class);

        ErrorMessageQuery query = ImmutableErrorMessageQuery.builder()
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .addAllIncludes(request.includes())
                .addAllExcludes(request.excludes())
                .limit(request.errorMessageLimit())
                .build();
        QueryResult<ErrorMessageCount> queryResult = traceDao.readErrorMessageCounts(query);
        List<ErrorPoint> unfilteredErrorPoints = getUnfilteredErrorPoints(query);
        DataSeries dataSeries = new DataSeries(null);
        Map<Long, Long[]> dataSeriesExtra = Maps.newHashMap();
        if (query.includes().isEmpty() && query.excludes().isEmpty()) {
            populateDataSeries(query, unfilteredErrorPoints, dataSeries, dataSeriesExtra);
        } else {
            Map<Long, Long> transactionCountMap = Maps.newHashMap();
            for (ErrorPoint unfilteredErrorPoint : unfilteredErrorPoints) {
                transactionCountMap.put(unfilteredErrorPoint.getCaptureTime(),
                        unfilteredErrorPoint.getTransactionCount());
            }
            ImmutableList<ErrorPoint> errorPoints =
                    traceDao.readErrorPoints(query, fixedAggregateIntervalMillis);
            for (ErrorPoint errorPoint : errorPoints) {
                Long transactionCount = transactionCountMap.get(errorPoint.getCaptureTime());
                if (transactionCount != null) {
                    errorPoint.setTransactionCount(transactionCount);
                }
            }
            populateDataSeries(query, errorPoints, dataSeries, dataSeriesExtra);
        }

        ImmutableErrorSummaryQuery summaryQuery = ImmutableErrorSummaryQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .sortOrder(request.summarySortOrder())
                .limit(request.summaryLimit())
                .build();
        ErrorSummary sidebarOverallErrorSummary =
                aggregateDao.readOverallErrorSummary(request.from(), request.to());
        QueryResult<ErrorSummary> sidebarQueryResult =
                aggregateDao.readTransactionErrorSummaries(summaryQuery);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeries);
        jg.writeObjectField("dataSeriesExtra", dataSeriesExtra);
        jg.writeFieldName("errorMessages");
        ErrorMessageCountMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreErrorMessagesAvailable", queryResult.moreAvailable());
        jg.writeFieldName("overallSummary");
        ErrorSummaryMarshaler.instance().marshalInstance(jg, sidebarOverallErrorSummary);
        jg.writeFieldName("transactionSummaries");
        ErrorSummaryMarshaler.instance().marshalIterable(jg, sidebarQueryResult.records());
        jg.writeBooleanField("moreSummariesAvailable", sidebarQueryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/error/summaries")
    String getSummaries(String queryString) throws Exception {
        ErrorSummaryQuery query = QueryStrings.decode(queryString, ErrorSummaryQuery.class);
        QueryResult<ErrorSummary> queryResult = aggregateDao.readTransactionErrorSummaries(query);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("transactionSummaries");
        ErrorSummaryMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreSummariesAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private List<ErrorPoint> getUnfilteredErrorPoints(ErrorMessageQuery query)
            throws SQLException {
        String transactionName = query.transactionName();
        List<ErrorPoint> unfilteredErrorPoints;
        if (Strings.isNullOrEmpty(transactionName)) {
            unfilteredErrorPoints = aggregateDao.readOverallErrorPoints(query.transactionType(),
                    query.from(), query.to());
        } else {
            unfilteredErrorPoints = aggregateDao.readTransactionErrorPoints(
                    query.transactionType(), transactionName, query.from(), query.to());
        }
        return unfilteredErrorPoints;
    }

    private void populateDataSeries(ErrorMessageQuery request,
            List<ErrorPoint> unfilteredErrorPoints, DataSeries dataSeries,
            Map<Long, Long[]> dataSeriesExtra) {
        ErrorPoint lastErrorPoint = null;
        for (ErrorPoint unfilteredErrorPoint : unfilteredErrorPoints) {
            if (lastErrorPoint == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslope(request.from(),
                        unfilteredErrorPoint.getCaptureTime(), dataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastErrorPoint.getCaptureTime(),
                        unfilteredErrorPoint.getCaptureTime(), dataSeries);
            }
            lastErrorPoint = unfilteredErrorPoint;
            long transactionCount = unfilteredErrorPoint.getTransactionCount();
            if (transactionCount == -1) {
                // make unknown really big?
                dataSeries.add(unfilteredErrorPoint.getCaptureTime(), 100);
            } else {
                dataSeries.add(unfilteredErrorPoint.getCaptureTime(),
                        100 * unfilteredErrorPoint.getErrorCount() / (double) transactionCount);
            }
            dataSeriesExtra.put(unfilteredErrorPoint.getCaptureTime(),
                    new Long[] {unfilteredErrorPoint.getErrorCount(), transactionCount});
        }
        if (lastErrorPoint != null) {
            dataSeriesHelper.addFinalDownslope(request.to(), dataSeries,
                    lastErrorPoint.getCaptureTime());
        }
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class ErrorDataRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        public abstract List<String> includes();
        public abstract List<String> excludes();
        abstract int errorMessageLimit();
        abstract ErrorSummarySortOrder summarySortOrder();
        abstract int summaryLimit();
    }
}

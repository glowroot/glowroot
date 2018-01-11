/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.collector.Collector.TraceVisitor;
import org.glowroot.agent.embedded.repo.TracePointQueryBuilder.ParameterizedSql;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcUpdate;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.RowMappers;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.model.Result;
import org.glowroot.common.repo.ImmutableErrorMessageCount;
import org.glowroot.common.repo.ImmutableErrorMessagePoint;
import org.glowroot.common.repo.ImmutableErrorMessageResult;
import org.glowroot.common.repo.ImmutableHeaderPlus;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castUntainted;

public class TraceDao implements TraceRepository {

    private static final String AGENT_ID = "";

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final ImmutableList<Column> traceColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("id", ColumnType.VARCHAR),
            ImmutableColumn.of("partial", ColumnType.BOOLEAN),
            ImmutableColumn.of("slow", ColumnType.BOOLEAN),
            ImmutableColumn.of("error", ColumnType.BOOLEAN),
            ImmutableColumn.of("start_time", ColumnType.BIGINT),
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("duration_nanos", ColumnType.BIGINT), // nanoseconds
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
            ImmutableColumn.of("headline", ColumnType.VARCHAR),
            ImmutableColumn.of("user", ColumnType.VARCHAR),
            ImmutableColumn.of("error_message", ColumnType.VARCHAR),
            ImmutableColumn.of("header", ColumnType.VARBINARY), // protobuf
            ImmutableColumn.of("entries_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("shared_query_texts_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("main_thread_profile_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("aux_thread_profile_capped_id", ColumnType.BIGINT));

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> traceAttributeColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("trace_id", ColumnType.VARCHAR),
                    ImmutableColumn.of("name", ColumnType.VARCHAR),
                    ImmutableColumn.of("value", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            // duration_nanos, id and error columns are included so database can return the
            // result set directly from the index without having to reference the table for each row
            //
            // trace_overall_slow_idx is for readSlowCount() and readSlowPoints()
            ImmutableIndex.of("trace_overall_slow_idx",
                    ImmutableList.of("transaction_type", "slow", "capture_time", "duration_nanos",
                            "error", "id")),
            // trace_transaction_slow_idx is for readSlowCount() and readSlowPoints()
            ImmutableIndex.of("trace_transaction_slow_idx",
                    ImmutableList.of("transaction_type", "transaction_name", "slow", "capture_time",
                            "duration_nanos", "error", "id")),
            // trace_overall_error_idx is for readErrorCount() and readErrorPoints()
            ImmutableIndex.of("trace_error_idx",
                    ImmutableList.of("transaction_type", "error", "capture_time", "duration_nanos",
                            "error", "id")),
            // trace_transaction_error_idx is for readErrorCount() and readErrorPoints()
            ImmutableIndex.of("trace_transaction_error_idx",
                    ImmutableList.of("transaction_type", "transaction_name", "error",
                            "capture_time", "duration_nanos", "id")),
            // trace_capture_time_idx is for reaper, this is very important when trace table is huge
            // e.g. after leaving slow threshold at 0 for a while
            ImmutableIndex.of("trace_capture_time_idx", ImmutableList.of("capture_time")),
            // trace_idx is for trace header lookup
            ImmutableIndex.of("trace_idx", ImmutableList.of("id")));

    private static final ImmutableList<Index> traceAttributeIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("trace_attribute_idx", ImmutableList.of("trace_id")));

    private final DataSource dataSource;
    private final CappedDatabase traceCappedDatabase;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final TraceAttributeNameDao traceAttributeNameDao;

    TraceDao(DataSource dataSource, CappedDatabase traceCappedDatabase,
            TransactionTypeDao transactionTypeDao, FullQueryTextDao fullQueryTextDao,
            TraceAttributeNameDao traceAttributeNameDao) throws Exception {
        this.dataSource = dataSource;
        this.traceCappedDatabase = traceCappedDatabase;
        this.traceAttributeNameDao = traceAttributeNameDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        if (dataSource.tableExists("trace")
                && !dataSource.columnExists("trace", "shared_query_texts_capped_id")) {
            // upgrade to 0.9.3
            startupLogger.info("upgrading glowroot schema, this may delay glowroot startup for a"
                    + " few minutes (depending on data size)...");
            dataSource.execute("alter table trace add column shared_query_texts_capped_id bigint");
            startupLogger.info("glowroot schema upgrade complete");
        }
        dataSource.syncTable("trace", traceColumns);
        dataSource.syncIndexes("trace", traceIndexes);
        dataSource.syncTable("trace_attribute", traceAttributeColumns);
        dataSource.syncIndexes("trace_attribute", traceAttributeIndexes);
    }

    public void store(TraceReader traceReader) throws Exception {
        final long captureTime = traceReader.captureTime();
        final Trace.Builder builder = Trace.newBuilder()
                .setId(traceReader.traceId())
                .setUpdate(traceReader.update());

        TraceVisitorImpl traceVisitor = new TraceVisitorImpl(captureTime, builder);
        traceReader.accept(traceVisitor);
        Trace trace = builder.build();
        Trace.Header header = trace.getHeader();

        dataSource.update(new TraceMerge(trace, traceVisitor.sharedQueryTexts));
        if (header.getAttributeCount() > 0) {
            if (trace.getUpdate()) {
                dataSource.update("delete from trace_attribute where trace_id = ?", trace.getId());
            }
            dataSource.batchUpdate(new TraceAttributeInsert(trace));
            for (Trace.Attribute attribute : header.getAttributeList()) {
                traceAttributeNameDao.updateLastCaptureTime(header.getTransactionType(),
                        attribute.getName(), header.getCaptureTime());
            }
        }
        transactionTypeDao.updateLastCaptureTime(header.getTransactionType(),
                header.getCaptureTime());
    }

    @Override
    public Result<TracePoint> readSlowPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        return readPoints(TraceKind.SLOW, query, filter, limit);
    }

    @Override
    public Result<TracePoint> readErrorPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        return readPoints(TraceKind.ERROR, query, filter, limit);
    }

    @Override
    public long readSlowCount(String agentRollupId, TraceQuery query) throws Exception {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ? and slow = ?",
                    query.transactionType(), query.from(), query.to(), true);
        } else {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ? and slow = ?",
                    query.transactionType(), transactionName, query.from(), query.to(), true);
        }
    }

    @Override
    public long readErrorCount(String agentRollupId, TraceQuery query) throws Exception {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ? and error = ?",
                    query.transactionType(), query.from(), query.to(), true);
        } else {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ? and error = ?",
                    query.transactionType(), transactionName, query.from(), query.to(), true);
        }
    }

    @Override
    public ErrorMessageResult readErrorMessages(String agentRollupId, TraceQuery query,
            ErrorMessageFilter filter, long resolutionMillis, int limit) throws Exception {
        List<ErrorMessagePoint> points =
                dataSource.query(new ErrorPointQuery(query, filter, resolutionMillis));
        List<ErrorMessageCount> counts =
                dataSource.query(new ErrorMessageCountQuery(query, filter, limit + 1));
        // one extra record over the limit is fetched above to identify if the limit was hit
        return ImmutableErrorMessageResult.builder()
                .addAllPoints(points)
                .counts(Result.create(counts, limit))
                .build();
    }

    @Override
    public @Nullable HeaderPlus readHeaderPlus(String agentId, String traceId) throws Exception {
        return dataSource.queryAtMostOne(new TraceHeaderQuery(traceId));
    }

    @Override
    public @Nullable Entries readEntries(String agentId, String traceId) throws Exception {
        return dataSource.query(new EntriesQuery(traceId));
    }

    // since this is only used by export, SharedQueryTexts are always returned with fullTrace
    // (never with truncatedText/truncatedEndText/fullTraceSha1)
    @Override
    public @Nullable Entries readEntriesForExport(String agentId, String traceId) throws Exception {
        Entries entries = dataSource.query(new EntriesQuery(traceId));
        if (entries == null) {
            return null;
        }
        List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        for (Trace.SharedQueryText sharedQueryText : entries.sharedQueryTexts()) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                sharedQueryTexts.add(sharedQueryText);
            } else {
                String fullText = fullQueryTextDao.getFullText(fullTextSha1);
                if (fullText == null) {
                    sharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                            .setFullText(sharedQueryText.getTruncatedText()
                                    + " ... [full query text has expired] ... "
                                    + sharedQueryText.getTruncatedEndText())
                            .build());
                } else {
                    sharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                            .setFullText(fullText)
                            .build());
                }
            }
        }
        return ImmutableEntries.builder()
                .copyFrom(entries)
                .sharedQueryTexts(sharedQueryTexts)
                .build();
    }

    @Override
    public @Nullable Profile readMainThreadProfile(String agentId, String traceId)
            throws Exception {
        Long cappedId = dataSource.queryForOptionalLong(
                "select main_thread_profile_capped_id from trace where id = ?", traceId);
        if (cappedId == null) {
            // trace must have just expired while user was viewing it, or data source is closing
            return null;
        }
        return traceCappedDatabase.readMessage(cappedId, Profile.parser());
    }

    @Override
    public @Nullable Profile readAuxThreadProfile(String agentId, String traceId) throws Exception {
        Long cappedId = dataSource.queryForOptionalLong(
                "select aux_thread_profile_capped_id from trace where id = ?", traceId);
        if (cappedId == null) {
            // trace must have just expired while user was viewing it, or data source is closing
            return null;
        }
        return traceCappedDatabase.readMessage(cappedId, Profile.parser());
    }

    void deleteBefore(long captureTime) throws Exception {
        traceAttributeNameDao.deleteBefore(captureTime);
        dataSource.deleteBefore("trace", captureTime);
        dataSource.deleteBefore("trace_attribute", captureTime);
    }

    private Result<TracePoint> readPoints(TraceKind traceKind, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        ParameterizedSql parameterizedSql =
                new TracePointQueryBuilder(traceKind, query, filter, limit).getParameterizedSql();
        List<TracePoint> points = dataSource.query(new TracePointQuery(parameterizedSql));
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.create(points, limit);
    }

    private static void appendQueryAndFilter(StringBuilder sql, TraceQuery query,
            ErrorMessageFilter filter) {
        sql.append(" and transaction_type = ?");
        String transactionName = query.transactionName();
        if (transactionName != null) {
            sql.append(" and transaction_name = ?");
        }
        sql.append(" and capture_time > ? and capture_time <= ?");
        for (int i = 0; i < filter.includes().size(); i++) {
            sql.append(" and upper(error_message) like ?");
        }
        for (int i = 0; i < filter.excludes().size(); i++) {
            sql.append(" and upper(error_message) not like ?");
        }
    }

    private static int bindQueryAndFilter(PreparedStatement preparedStatement, int startIndex,
            TraceQuery query, ErrorMessageFilter filter) throws SQLException {
        int i = startIndex;
        preparedStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            preparedStatement.setString(i++, transactionName);
        }
        preparedStatement.setLong(i++, query.from());
        preparedStatement.setLong(i++, query.to());
        for (String include : filter.includes()) {
            preparedStatement.setString(i++, '%' + include.toUpperCase(Locale.ENGLISH) + '%');
        }
        for (String exclude : filter.excludes()) {
            preparedStatement.setString(i++, '%' + exclude.toUpperCase(Locale.ENGLISH) + '%');
        }
        return i;
    }

    private class TraceVisitorImpl implements TraceVisitor {

        private final long captureTime;
        private final Trace.Builder builder;
        private final List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        private final Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();

        private TraceVisitorImpl(long captureTime, Trace.Builder builder) {
            this.captureTime = captureTime;
            this.builder = builder;
        }
        @Override
        public int visitSharedQueryText(String sharedQueryText) throws SQLException {
            Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(sharedQueryText);
            if (sharedQueryTextIndex != null) {
                return sharedQueryTextIndex;
            }
            sharedQueryTextIndex = sharedQueryTextIndexes.size();
            sharedQueryTextIndexes.put(sharedQueryText, sharedQueryTextIndex);
            if (sharedQueryText.length() > 2 * StorageConfig.TRACE_QUERY_TEXT_TRUNCATE) {
                String truncatedText =
                        sharedQueryText.substring(0, StorageConfig.TRACE_QUERY_TEXT_TRUNCATE);
                String truncatedEndText = sharedQueryText.substring(
                        sharedQueryText.length() - StorageConfig.TRACE_QUERY_TEXT_TRUNCATE,
                        sharedQueryText.length());
                String fullTextSha1 =
                        fullQueryTextDao.updateLastCaptureTime(sharedQueryText, captureTime);
                sharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                        .setTruncatedText(truncatedText)
                        .setTruncatedEndText(truncatedEndText)
                        .setFullTextSha1(fullTextSha1)
                        .build());
            } else {
                sharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                        .setFullText(sharedQueryText)
                        .build());
            }
            return sharedQueryTextIndex;
        }
        @Override
        public void visitEntry(Trace.Entry entry) {
            builder.addEntry(entry);
        }
        @Override
        public void visitMainThreadProfile(Profile profile) {
            builder.setMainThreadProfile(profile);
        }
        @Override
        public void visitAuxThreadProfile(Profile profile) {
            builder.setAuxThreadProfile(profile);
        }
        @Override
        public void visitHeader(Trace.Header header) {
            builder.setHeader(header);
        }
    }

    private class TraceMerge implements JdbcUpdate {

        private final String traceId;
        private final Trace.Header header;
        private final @Nullable Long entriesCappedId;
        private final @Nullable Long sharedQueryTextsCappedId;
        private final @Nullable Long mainThreadProfileId;
        private final @Nullable Long auxThreadProfileId;

        private TraceMerge(Trace trace, List<Trace.SharedQueryText> sharedQueryTexts)
                throws IOException {
            this.traceId = trace.getId();
            this.header = trace.getHeader();

            List<Trace.Entry> entries = trace.getEntryList();
            if (entries.isEmpty()) {
                entriesCappedId = null;
            } else {
                entriesCappedId = traceCappedDatabase.writeMessages(entries,
                        TraceCappedDatabaseStats.TRACE_ENTRIES);
            }
            if (sharedQueryTexts.isEmpty()) {
                sharedQueryTextsCappedId = null;
            } else {
                sharedQueryTextsCappedId = traceCappedDatabase.writeMessages(sharedQueryTexts,
                        TraceCappedDatabaseStats.TRACE_SHARED_QUERY_TEXTS);
            }
            if (trace.hasMainThreadProfile()) {
                mainThreadProfileId = traceCappedDatabase.writeMessage(trace.getMainThreadProfile(),
                        TraceCappedDatabaseStats.TRACE_PROFILES);
            } else {
                mainThreadProfileId = null;
            }
            if (trace.hasAuxThreadProfile()) {
                auxThreadProfileId = traceCappedDatabase.writeMessage(trace.getAuxThreadProfile(),
                        TraceCappedDatabaseStats.TRACE_PROFILES);
            } else {
                auxThreadProfileId = null;
            }
        }

        @Override
        public @Untainted String getSql() {
            return "merge into trace (id, partial, slow, error, start_time, capture_time,"
                    + " duration_nanos, transaction_type, transaction_name, headline, user,"
                    + " error_message, header, entries_capped_id, shared_query_texts_capped_id,"
                    + " main_thread_profile_capped_id, aux_thread_profile_capped_id) key (id)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        // minimal work inside this method as it is called with active connection
        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setString(i++, traceId);
            preparedStatement.setBoolean(i++, header.getPartial());
            preparedStatement.setBoolean(i++, header.getSlow());
            preparedStatement.setBoolean(i++, header.hasError());
            preparedStatement.setLong(i++, header.getStartTime());
            preparedStatement.setLong(i++, header.getCaptureTime());
            preparedStatement.setLong(i++, header.getDurationNanos());
            preparedStatement.setString(i++, header.getTransactionType());
            preparedStatement.setString(i++, header.getTransactionName());
            preparedStatement.setString(i++, header.getHeadline());
            preparedStatement.setString(i++, Strings.emptyToNull(header.getUser()));
            if (header.hasError()) {
                preparedStatement.setString(i++, header.getError().getMessage());
            } else {
                preparedStatement.setNull(i++, Types.VARCHAR);
            }
            // clear the headline and user in protobuf since they are stored as separate columns
            // already, and headline and user can be necessary to mask before submitting the
            // glowroot agent database when using glowroot agent to record an issue with glowroot
            // central
            preparedStatement.setBytes(i++, header.toBuilder()
                    .setHeadline("")
                    .setUser("")
                    .build()
                    .toByteArray());
            RowMappers.setLong(preparedStatement, i++, entriesCappedId);
            RowMappers.setLong(preparedStatement, i++, sharedQueryTextsCappedId);
            RowMappers.setLong(preparedStatement, i++, mainThreadProfileId);
            RowMappers.setLong(preparedStatement, i++, auxThreadProfileId);
        }
    }

    private static class TraceAttributeInsert implements JdbcUpdate {

        private final Trace trace;

        private TraceAttributeInsert(Trace trace) {
            this.trace = trace;
        }

        @Override
        public @Untainted String getSql() {
            return "insert into trace_attribute (trace_id, name, value, capture_time)"
                    + " values (?, ?, ?, ?)";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            Trace.Header header = trace.getHeader();
            for (Trace.Attribute attribute : header.getAttributeList()) {
                for (String value : attribute.getValueList()) {
                    int i = 1;
                    preparedStatement.setString(i++, trace.getId());
                    preparedStatement.setString(i++, attribute.getName());
                    preparedStatement.setString(i++, value);
                    preparedStatement.setLong(i++, header.getCaptureTime());
                    preparedStatement.addBatch();
                }
            }

        }
    }

    private static class TracePointQuery implements JdbcRowQuery<TracePoint> {

        private final ParameterizedSql parameterizedSql;

        private TracePointQuery(ParameterizedSql parameterizedSql) {
            this.parameterizedSql = parameterizedSql;
        }

        @Override
        public @Untainted String getSql() {
            return castUntainted(parameterizedSql.sql());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            for (Object obj : parameterizedSql.args()) {
                preparedStatement.setObject(i++, obj);
            }
        }

        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            int i = 1;
            String traceId = checkNotNull(resultSet.getString(i++));
            return ImmutableTracePoint.builder()
                    .agentId(AGENT_ID)
                    .traceId(traceId)
                    .captureTime(resultSet.getLong(i++))
                    .durationNanos(resultSet.getLong(i++))
                    .partial(resultSet.getBoolean(i++))
                    .error(resultSet.getBoolean(i++))
                    .build();
        }
    }

    private class TraceHeaderQuery implements JdbcRowQuery<HeaderPlus> {

        private final String traceId;

        private TraceHeaderQuery(String traceId) {
            this.traceId = traceId;
        }

        @Override
        public @Untainted String getSql() {
            return "select headline, user, header, entries_capped_id,"
                    + " main_thread_profile_capped_id, aux_thread_profile_capped_id from trace"
                    + " where id = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, traceId);
        }

        @Override
        public HeaderPlus mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            String headline = checkNotNull(resultSet.getString(i++));
            String user = resultSet.getString(i++);
            byte[] headerBytes = checkNotNull(resultSet.getBytes(i++));
            Existence entriesExistence =
                    RowMappers.getExistence(resultSet, i++, traceCappedDatabase);
            Existence mainThreadProfileExistence =
                    RowMappers.getExistence(resultSet, i++, traceCappedDatabase);
            Existence auxThreadProfileExistence =
                    RowMappers.getExistence(resultSet, i++, traceCappedDatabase);
            Existence profileExistence;
            if (mainThreadProfileExistence == Existence.EXPIRED
                    || auxThreadProfileExistence == Existence.EXPIRED) {
                profileExistence = Existence.EXPIRED;
            } else if (mainThreadProfileExistence == Existence.YES
                    || auxThreadProfileExistence == Existence.YES) {
                profileExistence = Existence.YES;
            } else {
                profileExistence = Existence.NO;
            }
            return ImmutableHeaderPlus.builder()
                    .header(Trace.Header.parseFrom(headerBytes).toBuilder()
                            .setHeadline(headline)
                            .setUser(Strings.nullToEmpty(user))
                            .build())
                    .entriesExistence(entriesExistence)
                    .profileExistence(profileExistence)
                    .build();
        }
    }

    private class EntriesQuery implements JdbcQuery</*@Nullable*/ Entries> {

        private final String traceId;

        private EntriesQuery(String traceId) {
            this.traceId = traceId;
        }

        @Override
        public @Untainted String getSql() {
            return "select entries_capped_id, shared_query_texts_capped_id from trace where id = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, traceId);
        }

        @Override
        public @Nullable Entries processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                return null;
            }
            int i = 1;
            Long entriesCappedId = RowMappers.getLong(resultSet, i++);
            Long sharedQueryTextsCappedId = RowMappers.getLong(resultSet, i++);
            if (entriesCappedId == null) {
                return null;
            }
            List<Trace.Entry> entries =
                    traceCappedDatabase.readMessages(entriesCappedId, Trace.Entry.parser());
            if (entries.isEmpty()) {
                return null;
            }
            ImmutableEntries.Builder result = ImmutableEntries.builder()
                    .addAllEntries(entries);
            if (sharedQueryTextsCappedId != null) {
                result.addAllSharedQueryTexts(traceCappedDatabase
                        .readMessages(sharedQueryTextsCappedId, Trace.SharedQueryText.parser()));
            }
            return result.build();
        }

        @Override
        public @Nullable Entries valueIfDataSourceClosed() {
            return null;
        }
    }

    private static class ErrorPointQuery implements JdbcRowQuery<ErrorMessagePoint> {

        private final TraceQuery query;
        private final ErrorMessageFilter filter;
        private final long resolutionMillis;

        private ErrorPointQuery(TraceQuery query, ErrorMessageFilter filter,
                long resolutionMillis) {
            this.query = query;
            this.filter = filter;
            this.resolutionMillis = resolutionMillis;
        }

        @Override
        public @Untainted String getSql() {
            // need ".0" to force double result
            String captureTimeSql = castUntainted(
                    "ceil(capture_time / " + resolutionMillis + ".0) * " + resolutionMillis);
            StringBuilder sql = new StringBuilder();
            sql.append("select " + captureTimeSql + ", count(*) from trace where error = ?");
            appendQueryAndFilter(sql, query, filter);
            sql.append(" group by " + captureTimeSql + " order by " + captureTimeSql);
            return castUntainted(sql.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setBoolean(i++, true);
            bindQueryAndFilter(preparedStatement, i, query, filter);
        }

        @Override
        public ErrorMessagePoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long errorCount = resultSet.getLong(2);
            return ImmutableErrorMessagePoint.of(captureTime, errorCount);
        }
    }

    private static class ErrorMessageCountQuery implements JdbcRowQuery<ErrorMessageCount> {

        private final TraceQuery query;
        private final ErrorMessageFilter filter;
        private final int limit;

        private ErrorMessageCountQuery(TraceQuery query, ErrorMessageFilter filter, int limit) {
            this.query = query;
            this.filter = filter;
            this.limit = limit;
        }

        @Override
        public @Untainted String getSql() {
            StringBuilder sql = new StringBuilder();
            sql.append("select error_message, count(*) from trace where error = ?");
            appendQueryAndFilter(sql, query, filter);
            sql.append(" group by error_message order by count(*) desc limit ?");
            return castUntainted(sql.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setBoolean(i++, true);
            i = bindQueryAndFilter(preparedStatement, i, query, filter);
            preparedStatement.setInt(i++, limit);
        }

        @Override
        public ErrorMessageCount mapRow(ResultSet resultSet) throws SQLException {
            return ImmutableErrorMessageCount.builder()
                    .message(Strings.nullToEmpty(resultSet.getString(1)))
                    .count(resultSet.getLong(2))
                    .build();
        }
    }
}

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
package org.glowroot.central.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.immutables.value.Value;

import org.glowroot.central.util.Messages;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.ImmutableErrorMessageCount;
import org.glowroot.storage.repo.ImmutableErrorMessagePoint;
import org.glowroot.storage.repo.ImmutableErrorMessageResult;
import org.glowroot.storage.repo.ImmutableHeaderPlus;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.util.ServerRollups;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class TraceDao implements TraceRepository {

    private final Session session;
    private final ServerDao serverDao;
    private final TransactionTypeDao transactionTypeDao;

    private final PreparedStatement insertOverallSlowPoint;
    private final PreparedStatement insertTransactionSlowPoint;
    private final PreparedStatement insertOverallErrorPoint;
    private final PreparedStatement insertTransactionErrorPoint;

    private final PreparedStatement insertOverallErrorMessage;
    private final PreparedStatement insertTransactionErrorMessage;

    private final PreparedStatement insertHeader;
    private final PreparedStatement insertEntries;
    private final PreparedStatement insertMainThreadProfile;
    private final PreparedStatement insertAuxThreadProfile;

    private final PreparedStatement readOverallSlowPoint;
    private final PreparedStatement readTransactionSlowPoint;
    private final PreparedStatement readOverallErrorPoint;
    private final PreparedStatement readTransactionErrorPoint;

    private final PreparedStatement readOverallErrorMessage;
    private final PreparedStatement readTransactionErrorMessage;

    private final PreparedStatement readHeader;

    private final PreparedStatement deletePartialOverallSlowPoint;
    private final PreparedStatement deletePartialTransactionSlowPoint;

    public TraceDao(Session session, ServerDao serverDao, TransactionTypeDao transactionTypeDao) {
        this.session = session;
        this.serverDao = serverDao;
        this.transactionTypeDao = transactionTypeDao;

        session.execute("create table if not exists trace_overall_slow_point"
                + " (server_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " server_id varchar, trace_id varchar, duration_nanos bigint, error boolean,"
                + " user varchar, attributes blob, primary key ((server_rollup, transaction_type),"
                + " capture_time, server_id, trace_id))");

        session.execute("create table if not exists trace_transaction_slow_point"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, server_id varchar, trace_id varchar,"
                + " duration_nanos bigint, error boolean, user varchar, attributes blob, "
                + " primary key ((server_rollup, transaction_type, transaction_name), capture_time,"
                + " server_id, trace_id))");

        session.execute("create table if not exists trace_overall_error_point"
                + " (server_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " server_id varchar, trace_id varchar, duration_nanos bigint,"
                + " error_message varchar, user varchar, attributes blob, primary key"
                + " ((server_rollup, transaction_type), capture_time, server_id, trace_id))");

        session.execute("create table if not exists trace_transaction_error_point"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, server_id varchar, trace_id varchar,"
                + " duration_nanos bigint, error_message varchar, user varchar, attributes blob,"
                + " primary key ((server_rollup, transaction_type, transaction_name), capture_time,"
                + " server_id, trace_id))");

        session.execute("create table if not exists trace_overall_error_message"
                + " (server_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " server_id varchar, trace_id varchar, error_message varchar, primary key"
                + " ((server_rollup, transaction_type), capture_time, server_id, trace_id))");

        session.execute("create table if not exists trace_transaction_error_message"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, server_id varchar, trace_id varchar,"
                + " error_message varchar, primary key ((server_rollup, transaction_type,"
                + " transaction_name), capture_time, server_id, trace_id))");

        session.execute("create table if not exists trace_header (server_id varchar,"
                + " trace_id varchar, header blob, primary key (server_id, trace_id))");

        // entries is cassandra reserved word
        session.execute("create table if not exists trace_entries (server_id varchar,"
                + " trace_id varchar, entriesx blob, primary key (server_id, trace_id))");

        session.execute("create table if not exists trace_main_thread_profile (server_id varchar,"
                + " trace_id varchar, profile blob, primary key (server_id, trace_id))");

        session.execute("create table if not exists trace_aux_thread_profile (server_id varchar,"
                + " trace_id varchar, profile blob, primary key (server_id, trace_id))");

        // server_rollup/capture_time is not necessarily unique
        // using a counter would be nice since only need sum over capture_time range
        // but counter has no TTL, see https://issues.apache.org/jira/browse/CASSANDRA-2103
        // so adding trace_id to provide uniqueness
        session.execute("create table if not exists trace_overall_slow (server_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, server_id varchar,"
                + " trace_id varchar, primary key ((server_rollup, transaction_type), capture_time,"
                + " server_id, trace_id))");

        session.execute("create table if not exists trace_transaction_slow (server_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " server_id varchar, trace_id varchar, primary key ((server_rollup,"
                + " transaction_type, transaction_name), capture_time, server_id, trace_id))");

        session.execute("create table if not exists trace_overall_error (server_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, server_id varchar,"
                + " trace_id varchar, primary key ((server_rollup, transaction_type), capture_time,"
                + " server_id, trace_id))");

        session.execute("create table if not exists trace_transaction_error (server_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " server_id varchar, trace_id varchar, primary key ((server_rollup,"
                + " transaction_type, transaction_name), capture_time, server_id, trace_id))");

        insertOverallSlowPoint = session.prepare("insert into trace_overall_slow_point"
                + " (server_rollup, transaction_type, capture_time, server_id, trace_id,"
                + " duration_nanos, error, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertTransactionSlowPoint = session.prepare("insert into trace_transaction_slow_point"
                + " (server_rollup, transaction_type, transaction_name, capture_time, server_id,"
                + " trace_id, duration_nanos, error, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertOverallErrorPoint = session.prepare("insert into trace_overall_error_point"
                + " (server_rollup, transaction_type, capture_time, server_id, trace_id,"
                + " duration_nanos, error_message, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertTransactionErrorPoint = session.prepare("insert into trace_transaction_error_point"
                + " (server_rollup, transaction_type, transaction_name, capture_time, server_id,"
                + " trace_id, duration_nanos, error_message, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertOverallErrorMessage = session.prepare("insert into trace_overall_error_message"
                + " (server_rollup, transaction_type, capture_time, server_id, trace_id,"
                + " error_message) values (?, ?, ?, ?, ?, ?)");

        insertTransactionErrorMessage = session.prepare(
                "insert into trace_transaction_error_message (server_rollup, transaction_type,"
                        + " transaction_name, capture_time, server_id, trace_id, error_message)"
                        + " values (?, ?, ?, ?, ?, ?, ?)");

        insertHeader = session.prepare("insert into trace_header (server_id, trace_id, header)"
                + " values (?, ?, ?)");

        insertEntries = session.prepare("insert into trace_entries (server_id, trace_id, entriesx)"
                + " values (?, ?, ?)");

        insertMainThreadProfile = session.prepare("insert into trace_main_thread_profile"
                + " (server_id, trace_id, profile) values (?, ?, ?)");

        insertAuxThreadProfile = session.prepare("insert into trace_aux_thread_profile"
                + " (server_id, trace_id, profile) values (?, ?, ?)");

        readOverallSlowPoint = session.prepare("select server_id, trace_id, capture_time,"
                + " duration_nanos, error, user, attributes from trace_overall_slow_point"
                + " where server_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?");

        readTransactionSlowPoint = session.prepare("select server_id, trace_id, capture_time,"
                + " duration_nanos, error, user, attributes from trace_transaction_slow_point"
                + " where server_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?");

        readOverallErrorPoint = session.prepare("select server_id, trace_id, capture_time,"
                + " duration_nanos, error_message, user, attributes from trace_overall_error_point"
                + " where server_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?");

        readTransactionErrorPoint = session.prepare("select server_id, trace_id, capture_time,"
                + " duration_nanos, error_message, user, attributes from"
                + " trace_transaction_error_point where server_rollup = ? and transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readOverallErrorMessage = session.prepare(
                "select capture_time, error_message from trace_overall_error_message"
                        + " where server_rollup = ? and transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ?");

        readTransactionErrorMessage = session.prepare(
                "select capture_time, error_message from trace_transaction_error_message"
                        + " where server_rollup = ? and transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readHeader = session
                .prepare("select header from trace_header where server_id = ? and trace_id = ?");

        deletePartialOverallSlowPoint = session.prepare("delete from trace_overall_slow_point"
                + " where server_rollup = ? and transaction_type = ? and capture_time = ?"
                + " and server_id = ? and trace_id = ?");

        deletePartialTransactionSlowPoint = session.prepare(
                "delete from trace_transaction_slow_point where server_rollup = ?"
                        + " and transaction_type = ? and transaction_name = ?"
                        + " and capture_time = ? and server_id = ? and trace_id = ?");
    }

    @Override
    public void collect(String serverId, Trace trace) throws IOException {

        Trace.Header priorHeader = readHeader(serverId, trace.getId());

        Trace.Header header = trace.getHeader();

        // unlike aggregates and gauge values, traces can get written to server rollups immediately
        List<String> serverRollups = ServerRollups.getServerRollups(serverId);

        for (String serverRollup : serverRollups) {
            if (header.getSlow()) {
                BoundStatement boundStatement = insertOverallSlowPoint.bind();
                int i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setBool(i++, header.hasError());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                boundStatement.setBytes(i++, Messages.toByteBuffer(header.getAttributeList()));
                session.execute(boundStatement);

                boundStatement = insertTransactionSlowPoint.bind();
                i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setBool(i++, header.hasError());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                boundStatement.setBytes(i++, Messages.toByteBuffer(header.getAttributeList()));
                session.execute(boundStatement);

                if (priorHeader != null) {
                    boundStatement = deletePartialOverallSlowPoint.bind();
                    i = 0;
                    boundStatement.setString(i++, serverRollup);
                    boundStatement.setString(i++, priorHeader.getTransactionType());
                    boundStatement.setTimestamp(i++, new Date(priorHeader.getCaptureTime()));
                    boundStatement.setString(i++, serverId);
                    boundStatement.setString(i++, trace.getId());
                    session.execute(boundStatement);

                    boundStatement = deletePartialTransactionSlowPoint.bind();
                    i = 0;
                    boundStatement.setString(i++, serverRollup);
                    boundStatement.setString(i++, priorHeader.getTransactionType());
                    boundStatement.setString(i++, priorHeader.getTransactionName());
                    boundStatement.setTimestamp(i++, new Date(priorHeader.getCaptureTime()));
                    boundStatement.setString(i++, serverId);
                    boundStatement.setString(i++, trace.getId());
                    session.execute(boundStatement);
                }
            }
            if (header.hasError()) {
                BoundStatement boundStatement = insertOverallErrorMessage.bind();
                int i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setString(i++, header.getError().getMessage());
                session.execute(boundStatement);

                boundStatement = insertTransactionErrorMessage.bind();
                i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setString(i++, header.getError().getMessage());
                session.execute(boundStatement);

                boundStatement = insertOverallErrorPoint.bind();
                i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setString(i++, header.getError().getMessage());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                boundStatement.setBytes(i++, Messages.toByteBuffer(header.getAttributeList()));
                session.execute(boundStatement);

                boundStatement = insertTransactionErrorPoint.bind();
                i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setString(i++, header.getError().getMessage());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                boundStatement.setBytes(i++, Messages.toByteBuffer(header.getAttributeList()));
                session.execute(boundStatement);
            }
            serverDao.updateLastCaptureTime(serverRollup, serverRollup.equals(serverId));
            transactionTypeDao.updateLastCaptureTime(serverRollup, header.getTransactionType());
        }

        BoundStatement boundStatement = insertHeader.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setString(1, trace.getId());
        boundStatement.setBytes(2, trace.getHeader().toByteString().asReadOnlyByteBuffer());
        session.execute(boundStatement);

        List<Trace.Entry> entries = trace.getEntryList();
        if (!entries.isEmpty()) {
            boundStatement = insertEntries.bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, trace.getId());
            boundStatement.setBytes(2, Messages.toByteBuffer(entries));
            session.execute(boundStatement);
        }

        if (trace.hasMainThreadProfile()) {
            boundStatement = insertMainThreadProfile.bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, trace.getId());
            boundStatement.setBytes(2,
                    trace.getMainThreadProfile().toByteString().asReadOnlyByteBuffer());
            session.execute(boundStatement);
        }

        if (trace.hasAuxThreadProfile()) {
            boundStatement = insertAuxThreadProfile.bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, trace.getId());
            boundStatement.setBytes(2,
                    trace.getAuxThreadProfile().toByteString().asReadOnlyByteBuffer());
            session.execute(boundStatement);
        }
    }

    @Override
    public List<String> readTraceAttributeNames(String serverRollup) {
        // FIXME
        return ImmutableList.of();
    }

    @Override
    public Result<TracePoint> readSlowPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws IOException {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            BoundStatement boundStatement = readOverallSlowPoint.bind();
            boundStatement.setString(0, query.serverRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setTimestamp(2, new Date(query.from()));
            boundStatement.setTimestamp(3, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, false);
        } else {
            BoundStatement boundStatement = readTransactionSlowPoint.bind();
            boundStatement.setString(0, query.serverRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(query.from()));
            boundStatement.setTimestamp(4, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, false);
        }
    }

    @Override
    public Result<TracePoint> readErrorPoints(TraceQuery query, TracePointFilter filter,
            int limit) throws IOException {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            BoundStatement boundStatement = readOverallErrorPoint.bind();
            boundStatement.setString(0, query.serverRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setTimestamp(2, new Date(query.from()));
            boundStatement.setTimestamp(3, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, true);
        } else {
            BoundStatement boundStatement = readTransactionErrorPoint.bind();
            boundStatement.setString(0, query.serverRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(query.from()));
            boundStatement.setTimestamp(4, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, true);
        }
    }

    @Override
    public long readSlowCount(TraceQuery query) {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            ResultSet results = session.execute(
                    "select count(*) from trace_overall_slow where server_rollup = ?"
                            + " and transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ?",
                    query.serverRollup(), query.transactionType(), query.from(), query.to());
            return results.one().getLong(0);
        } else {
            ResultSet results = session.execute(
                    "select count(*) from trace_transaction_slow where server_rollup = ?"
                            + " and transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ?",
                    query.serverRollup(), query.transactionType(), transactionName, query.from(),
                    query.to());
            return results.one().getLong(0);
        }
    }

    @Override
    public long readErrorCount(TraceQuery query) {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            ResultSet results = session.execute(
                    "select count(*) from trace_overall_error where server_rollup = ?"
                            + " and transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ?",
                    query.serverRollup(), query.transactionType(), query.from(), query.to());
            return results.one().getLong(0);
        } else {
            ResultSet results = session.execute(
                    "select count(*) from trace_transaction_error where server_rollup = ?"
                            + " and transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ?",
                    query.serverRollup(), query.transactionType(), transactionName, query.from(),
                    query.to());
            return results.one().getLong(0);
        }
    }

    @Override
    public ErrorMessageResult readErrorMessages(TraceQuery query, ErrorMessageFilter filter,
            long resolutionMillis, long liveCaptureTime, int limit) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorMessage.bind();
            boundStatement.setString(0, query.serverRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setTimestamp(2, new Date(query.from()));
            boundStatement.setTimestamp(3, new Date(query.to()));
        } else {
            boundStatement = readTransactionErrorMessage.bind();
            boundStatement.setString(0, query.serverRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(query.from()));
            boundStatement.setTimestamp(4, new Date(query.to()));
        }
        ResultSet results = session.execute(boundStatement);
        // rows are already in order by captureTime, so saving sort step by using linked hash map
        Map<Long, MutableLong> pointCounts = Maps.newLinkedHashMap();
        Map<String, MutableLong> messageCounts = Maps.newHashMap();
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String errorMessage = checkNotNull(row.getString(1));
            captureTime =
                    (long) Math.ceil(captureTime / (double) resolutionMillis) * resolutionMillis;
            pointCounts.computeIfAbsent(captureTime, k -> new MutableLong()).increment();
            messageCounts.computeIfAbsent(errorMessage, k -> new MutableLong()).increment();
        }
        List<ErrorMessagePoint> points = pointCounts.entrySet().stream()
                .map(e -> ImmutableErrorMessagePoint.of(e.getKey(), e.getValue().value))
                .sorted(Comparator.comparingLong(ErrorMessagePoint::captureTime))
                // explicit type on this line is needed for Checker Framework
                // see https://github.com/typetools/checker-framework/issues/531
                .collect(Collectors.<ErrorMessagePoint>toList());
        List<ErrorMessageCount> counts = messageCounts.entrySet().stream()
                .map(e -> ImmutableErrorMessageCount.of(e.getKey(), e.getValue().value))
                // explicit type on this line is needed for Checker Framework
                // see https://github.com/typetools/checker-framework/issues/531
                .collect(Collectors.<ErrorMessageCount>toList());

        if (counts.size() <= limit) {
            return ImmutableErrorMessageResult.builder()
                    .addAllPoints(points)
                    .counts(new Result<>(counts, false))
                    .build();
        } else {
            return ImmutableErrorMessageResult.builder()
                    .addAllPoints(points)
                    .counts(new Result<>(counts.subList(0, limit), true))
                    .build();
        }
    }

    @Override
    public @Nullable HeaderPlus readHeaderPlus(String serverId, String traceId)
            throws InvalidProtocolBufferException {
        Trace.Header header = readHeader(serverId, traceId);
        if (header == null) {
            return null;
        }
        ResultSet results = session.execute("select count(*) from trace_entries where server_id = ?"
                + " and trace_id = ?", serverId, traceId);
        Existence entriesExistence = results.one().getLong(0) == 0 ? Existence.NO : Existence.YES;
        Existence profileExistence;
        results = session.execute("select count(*) from trace_main_thread_profile"
                + " where server_id = ? and trace_id = ?", serverId, traceId);
        if (results.one().getLong(0) == 0) {
            results = session.execute("select count(*) from trace_aux_thread_profile"
                    + " where server_id = ? and trace_id = ?", serverId, traceId);
            profileExistence = results.one().getLong(0) == 0 ? Existence.NO : Existence.YES;
        } else {
            profileExistence = Existence.YES;
        }
        return ImmutableHeaderPlus.of(header, entriesExistence, profileExistence);
    }

    @Override
    public List<Trace.Entry> readEntries(String serverId, String traceId) throws IOException {
        ResultSet results = session.execute("select entriesx from trace_entries where server_id = ?"
                + " and trace_id = ?", serverId, traceId);
        Row row = results.one();
        if (row == null) {
            return ImmutableList.of();
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Messages.parseDelimitedFrom(bytes, Trace.Entry.parser());
    }

    @Override
    public @Nullable Profile readMainThreadProfile(String serverId, String traceId)
            throws InvalidProtocolBufferException {
        ResultSet results = session.execute("select profile from trace_main_thread_profile"
                + " where server_id = ? and trace_id = ?", serverId, traceId);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Profile.parseFrom(ByteString.copyFrom(bytes));
    }

    @Override
    public @Nullable Profile readAuxThreadProfile(String serverId, String traceId)
            throws InvalidProtocolBufferException {
        ResultSet results = session.execute("select profile from trace_aux_thread_profile"
                + " where server_id = ? and trace_id = ?", serverId, traceId);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Profile.parseFrom(ByteString.copyFrom(bytes));
    }

    @Override
    public void deleteAll(String serverRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    private Trace.Header readHeader(String serverId, String traceId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readHeader.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Trace.Header.parseFrom(ByteString.copyFrom(bytes));
    }

    private Result<TracePoint> processPoints(ResultSet results, TracePointFilter filter,
            int limit, boolean errorPoints) throws IOException {
        List<TracePoint> tracePoints = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            String serverId = checkNotNull(row.getString(i++));
            String traceId = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long durationNanos = row.getLong(i++);
            boolean error;
            String errorMessage;
            if (errorPoints) {
                error = true;
                // error points are defined by having an error message, so safe to checkNotNull
                errorMessage = checkNotNull(row.getString(i++));
            } else {
                error = row.getBool(i++);
                errorMessage = ""; // non-error query doesn't support filtering on error message
            }
            String user = Strings.nullToEmpty(row.getString(i++));
            ByteBuffer attributeBytes = row.getBytes(i++);
            List<Trace.Attribute> attrs =
                    Messages.parseDelimitedFrom(attributeBytes, Trace.Attribute.parser());
            Map<String, List<String>> attributes = attrs.stream().collect(
                    Collectors.toMap(Trace.Attribute::getName, Trace.Attribute::getValueList));
            if (filter.matchesDuration(durationNanos)
                    && filter.matchesError(errorMessage)
                    && filter.matchesUser(user)
                    && filter.matchesAttributes(attributes)) {
                tracePoints.add(ImmutableTracePoint.builder()
                        .serverId(serverId)
                        .traceId(traceId)
                        .captureTime(captureTime)
                        .durationNanos(durationNanos)
                        .error(error)
                        .build());
            }
        }
        // remove duplicates (partially stored traces) since there is (small) window between updated
        // insert (with new capture time) and the delete of prior insert (with prior capture time)
        Set<TraceKey> traceKeys = Sets.newHashSet();
        ListIterator<TracePoint> i = tracePoints.listIterator(tracePoints.size());
        while (i.hasPrevious()) {
            TracePoint trace = i.previous();
            TraceKey traceKey = ImmutableTraceKey.of(trace.serverId(), trace.traceId());
            if (!traceKeys.add(traceKey)) {
                i.remove();
            }
        }
        // apply limit and re-sort if needed
        if (tracePoints.size() > limit) {
            tracePoints = tracePoints.stream()
                    .sorted(Comparator.comparingLong(TracePoint::durationNanos).reversed())
                    .limit(limit)
                    .sorted(Comparator.comparingLong(TracePoint::captureTime))
                    // explicit type on this line is needed for Checker Framework
                    // see https://github.com/typetools/checker-framework/issues/531
                    .collect(Collectors.<TracePoint>toList());
            return new Result<>(tracePoints, true);
        } else {
            return new Result<>(tracePoints, false);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceKey {
        String serverId();
        String traceId();
    }

    private static class MutableLong {
        private long value;
        private void increment() {
            value++;
        }
    }
}

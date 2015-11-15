/*
 * Copyright 2015 the original author or authors.
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
import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.glowroot.central.util.Messages;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointCriteria;
import org.glowroot.storage.repo.ImmutableHeaderPlus;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.util.ServerRollups;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public class TraceDao implements TraceRepository {

    private final Session session;
    private final ServerDao serverDao;
    private final TransactionTypeDao transactionTypeDao;

    private final PreparedStatement insertOverallSlowPoint;
    private final PreparedStatement insertTransactionSlowPoint;
    private final PreparedStatement insertOverallErrorPoint;
    private final PreparedStatement insertTransactionErrorPoint;

    private final PreparedStatement insertHeader;
    private final PreparedStatement insertEntries;
    private final PreparedStatement insertProfile;

    private final PreparedStatement insertOverallSlow;
    private final PreparedStatement insertTransactionSlow;
    private final PreparedStatement insertOverallError;
    private final PreparedStatement insertTransactionError;

    public TraceDao(Session session, ServerDao serverDao, TransactionTypeDao transactionTypeDao) {
        this.session = session;
        this.serverDao = serverDao;
        this.transactionTypeDao = transactionTypeDao;

        session.execute("create table if not exists trace_overall_slow_point"
                + " (server_rollup varchar, transaction_type varchar, capture_time bigint,"
                + " server_id varchar, trace_id varchar, duration_nanos bigint, error boolean,"
                + " transaction_name varchar, headline varchar, user varchar, primary key"
                + " ((server_rollup, transaction_type), capture_time, trace_id))");

        session.execute("create table if not exists trace_transaction_slow_point"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time bigint, server_id varchar, trace_id varchar,"
                + " duration_nanos bigint, error boolean, headline varchar, user varchar,"
                + " primary key ((server_rollup, transaction_type, transaction_name), capture_time,"
                + " trace_id))");

        session.execute("create table if not exists trace_overall_error_point"
                + " (server_rollup varchar, transaction_type varchar, capture_time bigint,"
                + " server_id varchar, trace_id varchar, duration_nanos bigint,"
                + " error_message varchar, transaction_name varchar, headline varchar,"
                + " user varchar, primary key ((server_rollup, transaction_type), capture_time,"
                + " trace_id))");

        session.execute("create table if not exists trace_transaction_error_point"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time bigint, server_id varchar, trace_id varchar,"
                + " duration_nanos bigint, error_message varchar, headline varchar, user varchar,"
                + " primary key ((server_rollup, transaction_type, transaction_name), capture_time,"
                + " trace_id))");

        session.execute("create table if not exists trace_header (server_id varchar,"
                + " trace_id varchar, header blob, primary key (server_id, trace_id))");

        // entries is cassandra reserved word
        session.execute("create table if not exists trace_entries (server_id varchar,"
                + " trace_id varchar, entriesx blob, primary key (server_id, trace_id))");

        session.execute("create table if not exists trace_profile (server_id varchar,"
                + " trace_id varchar, profile blob, primary key (server_id, trace_id))");

        // server_rollup/capture_time is not necessarily unique
        // using a counter would be nice since only need sum over capture_time range
        // but counter has no TTL, see https://issues.apache.org/jira/browse/CASSANDRA-2103
        // so adding trace_id to provide uniqueness
        session.execute("create table if not exists trace_overall_slow (server_rollup varchar,"
                + " transaction_type varchar, capture_time bigint, trace_id varchar,"
                + " primary key ((server_rollup, transaction_type), capture_time, trace_id))");

        session.execute("create table if not exists trace_transaction_slow (server_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time bigint,"
                + " trace_id varchar, primary key ((server_rollup, transaction_type,"
                + " transaction_name), capture_time, trace_id))");

        session.execute("create table if not exists trace_overall_error (server_rollup varchar,"
                + " transaction_type varchar, capture_time bigint, trace_id varchar,"
                + " primary key ((server_rollup, transaction_type), capture_time, trace_id))");

        session.execute("create table if not exists trace_transaction_error (server_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time bigint,"
                + " trace_id varchar, primary key ((server_rollup, transaction_type,"
                + " transaction_name), capture_time, trace_id))");

        insertOverallSlowPoint = session.prepare("insert into trace_overall_slow_point"
                + " (server_rollup, transaction_type, capture_time, server_id, trace_id,"
                + " duration_nanos, error, transaction_name, headline, user) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertTransactionSlowPoint = session.prepare("insert into trace_transaction_slow_point"
                + " (server_rollup, transaction_type, transaction_name, capture_time, server_id,"
                + " trace_id, duration_nanos, error, headline, user) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertOverallErrorPoint = session.prepare("insert into trace_overall_error_point"
                + " (server_rollup, transaction_type, capture_time, server_id, trace_id,"
                + " duration_nanos, error_message, transaction_name, headline, user) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertTransactionErrorPoint = session.prepare("insert into trace_transaction_error_point"
                + " (server_rollup, transaction_type, transaction_name, capture_time, server_id,"
                + " trace_id, duration_nanos, error_message, headline, user) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        insertHeader = session.prepare("insert into trace_header (server_id, trace_id, header)"
                + " values (?, ?, ?)");

        insertEntries = session.prepare("insert into trace_entries (server_id, trace_id, entriesx)"
                + " values (?, ?, ?)");

        insertProfile = session.prepare("insert into trace_profile (server_id, trace_id, profile)"
                + " values (?, ?, ?)");

        insertOverallSlow = session.prepare("insert into trace_overall_slow (server_rollup,"
                + " transaction_type, capture_time, trace_id) values (?, ?, ?, ?)");

        insertTransactionSlow = session.prepare("insert into trace_transaction_slow (server_rollup,"
                + " transaction_type, transaction_name, capture_time, trace_id)"
                + " values (?, ?, ?, ?, ?)");

        insertOverallError = session.prepare("insert into trace_overall_error (server_rollup,"
                + " transaction_type, capture_time, trace_id) values (?, ?, ?, ?)");

        insertTransactionError =
                session.prepare("insert into trace_transaction_error (server_rollup,"
                        + " transaction_type, transaction_name, capture_time, trace_id)"
                        + " values (?, ?, ?, ?, ?)");
    }

    @Override
    public void collect(String serverId, Trace trace) throws IOException {

        Trace.Header header = trace.getHeader();

        List<String> serverRollups = ServerRollups.getServerRollups(serverId);

        for (String serverRollup : serverRollups) {
            if (header.getSlow()) {
                BoundStatement boundStatement = insertOverallSlowPoint.bind();
                int i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setLong(i++, header.getCaptureTime());
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setBool(i++, header.hasError());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setString(i++, header.getHeadline());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                session.execute(boundStatement);

                boundStatement = insertTransactionSlowPoint.bind();
                i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setLong(i++, header.getCaptureTime());
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setBool(i++, header.hasError());
                boundStatement.setString(i++, header.getHeadline());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                session.execute(boundStatement);
            }
            if (header.hasError()) {
                BoundStatement boundStatement = insertOverallErrorPoint.bind();
                int i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setLong(i++, header.getCaptureTime());
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                if (header.hasError()) {
                    boundStatement.setString(i++, header.getError().getMessage());
                } else {
                    boundStatement.setToNull(i++);
                }
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setString(i++, header.getHeadline());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                session.execute(boundStatement);

                boundStatement = insertTransactionErrorPoint.bind();
                i = 0;
                boundStatement.setString(i++, serverRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setLong(i++, header.getCaptureTime());
                boundStatement.setString(i++, serverId);
                boundStatement.setString(i++, trace.getId());
                boundStatement.setLong(i++, header.getDurationNanos());
                if (header.hasError()) {
                    boundStatement.setString(i++, header.getError().getMessage());
                } else {
                    boundStatement.setToNull(i++);
                }
                boundStatement.setString(i++, header.getHeadline());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
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

        if (trace.hasProfile()) {
            boundStatement = insertProfile.bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, trace.getId());
            boundStatement.setBytes(2, trace.getProfile().toByteString().asReadOnlyByteBuffer());
            session.execute(boundStatement);
        }

        if (header.getSlow()) {
            for (String serverRollup : serverRollups) {
                boundStatement = insertOverallSlow.bind();
                boundStatement.setString(0, serverRollup);
                boundStatement.setString(1, header.getTransactionType());
                boundStatement.setLong(2, header.getCaptureTime());
                boundStatement.setString(3, trace.getId());
                session.execute(boundStatement);

                boundStatement = insertTransactionSlow.bind();
                boundStatement.setString(0, serverRollup);
                boundStatement.setString(1, header.getTransactionType());
                boundStatement.setString(2, header.getTransactionName());
                boundStatement.setLong(3, header.getCaptureTime());
                boundStatement.setString(4, trace.getId());
                session.execute(boundStatement);
            }
        }
        if (header.hasError()) {
            for (String serverRollup : serverRollups) {
                boundStatement = insertOverallError.bind();
                boundStatement.setString(0, serverRollup);
                boundStatement.setString(1, header.getTransactionType());
                boundStatement.setLong(2, header.getCaptureTime());
                boundStatement.setString(3, trace.getId());
                session.execute(boundStatement);

                boundStatement = insertTransactionError.bind();
                boundStatement.setString(0, serverRollup);
                boundStatement.setString(1, header.getTransactionType());
                boundStatement.setString(2, header.getTransactionName());
                boundStatement.setLong(3, header.getCaptureTime());
                boundStatement.setString(4, trace.getId());
                session.execute(boundStatement);
            }
        }
    }

    @Override
    public List<String> readTraceAttributeNames(String serverRollup) {
        // FIXME
        return ImmutableList.of();
    }

    @Override
    public Result<TracePoint> readOverallSlowPoints(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo, TracePointCriteria criteria, int limit) {
        ResultSet results = session.execute("select server_id, trace_id, capture_time,"
                + " duration_nanos, error from trace_overall_slow_point where server_rollup = ?"
                + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        return processSlowPoints(limit, results);
    }

    @Override
    public Result<TracePoint> readTransactionSlowPoints(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo,
            TracePointCriteria criteria, int limit) {
        ResultSet results = session.execute("select server_id, trace_id, capture_time,"
                + " duration_nanos, error from trace_overall_slow_point where server_rollup = ?"
                + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ?", serverRollup, transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
        return processSlowPoints(limit, results);
    }

    @Override
    public Result<TracePoint> readOverallErrorPoints(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo, TracePointCriteria criteria, int limit) {
        ResultSet results = session.execute("select server_id, trace_id, capture_time,"
                + " duration_nanos from trace_overall_error_point where server_rollup = ?"
                + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        return processErrorPoints(limit, results);
    }

    @Override
    public Result<TracePoint> readTransactionErrorPoints(String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, TracePointCriteria criteria, int limit) {
        ResultSet results = session.execute("select server_id, trace_id, capture_time,"
                + " duration_nanos from trace_overall_error_point where server_rollup = ?"
                + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ?", serverRollup, transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
        return processSlowPoints(limit, results);
    }

    @Override
    public long readOverallSlowCount(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo) {
        ResultSet results = session.execute("select count(*) from trace_overall_slow"
                + " where server_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?", serverRollup, transactionType, captureTimeFrom,
                captureTimeTo);
        return results.one().getLong(0);
    }

    @Override
    public long readTransactionSlowCount(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) {
        ResultSet results = session.execute("select count(*) from trace_overall_slow"
                + " where server_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?", serverRollup, transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
        return results.one().getLong(0);
    }

    @Override
    public long readOverallErrorCount(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo) {
        ResultSet results = session.execute("select count(*) from trace_overall_error"
                + " where server_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?", serverRollup, transactionType, captureTimeFrom,
                captureTimeTo);
        return results.one().getLong(0);
    }

    @Override
    public long readTransactionErrorCount(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) {
        ResultSet results = session.execute("select count(*) from trace_overall_error"
                + " where server_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?", serverRollup, transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
        return results.one().getLong(0);
    }

    @Override
    public List<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query, long resolutionMillis,
            long liveCaptureTime) {
        // FIXME
        return ImmutableList.of();
    }

    @Override
    public Result<ErrorMessageCount> readErrorMessageCounts(ErrorMessageQuery query) {
        // FIXME
        return new Result<>(ImmutableList.<ErrorMessageCount>of(), false);
    }

    @Override
    public @Nullable HeaderPlus readHeader(String serverId, String traceId)
            throws InvalidProtocolBufferException {
        ResultSet results = session.execute("select header from trace_header where server_id = ?"
                + " and trace_id = ?", serverId, traceId);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        Trace.Header header = Trace.Header.parseFrom(ByteString.copyFrom(row.getBytes(0)));
        results = session.execute("select count(*) from trace_entries where server_id = ?"
                + " and trace_id = ?", serverId, traceId);
        Existence entriesExistence = results.one().getLong(0) == 0 ? Existence.NO : Existence.YES;
        results = session.execute("select count(*) from trace_PROFILE where server_id = ?"
                + " and trace_id = ?", serverId, traceId);
        Existence profileExistence = results.one().getLong(0) == 0 ? Existence.NO : Existence.YES;
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
        return Messages.parseDelimitedFrom(row.getBytes(0), Trace.Entry.parser());
    }

    @Override
    public @Nullable Profile readProfile(String serverId, String traceId)
            throws InvalidProtocolBufferException {
        ResultSet results = session.execute("select header from trace_profile where server_id = ?"
                + " and trace_id = ?", serverId, traceId);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return Profile.parseFrom(ByteString.copyFrom(row.getBytes(0)));
    }

    @Override
    public void deleteAll(String serverRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    @Override
    public long count(String serverRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    private Result<TracePoint> processSlowPoints(int limit, ResultSet results) {
        List<TracePoint> tracePoints = Lists.newArrayList();
        for (Row row : results) {
            // FIXME need to at least filter in-memory for now
            String serverId = row.getString(0);
            String traceId = row.getString(1);
            long captureTime = row.getLong(2);
            long durationNanos = row.getLong(3);
            boolean error = row.getBool(4);
            tracePoints.add(ImmutableTracePoint.builder()
                    .serverId(serverId)
                    .traceId(traceId)
                    .captureTime(captureTime)
                    .durationNanos(durationNanos)
                    .error(error)
                    .build());
        }
        if (tracePoints.size() > limit) {
            tracePoints = tracePoints.subList(0, limit);
            return new Result<>(tracePoints, true);
        } else {
            return new Result<>(tracePoints, false);
        }
    }

    private Result<TracePoint> processErrorPoints(int limit, ResultSet results) {
        List<TracePoint> tracePoints = Lists.newArrayList();
        for (Row row : results) {
            // FIXME need to at least filter in-memory for now
            String serverId = row.getString(0);
            String traceId = row.getString(1);
            long captureTime = row.getLong(2);
            long durationNanos = row.getLong(3);
            tracePoints.add(ImmutableTracePoint.builder()
                    .serverId(serverId)
                    .traceId(traceId)
                    .captureTime(captureTime)
                    .durationNanos(durationNanos)
                    .error(true)
                    .build());
        }
        if (tracePoints.size() > limit) {
            tracePoints = tracePoints.subList(0, limit);
            return new Result<>(tracePoints, true);
        } else {
            return new Result<>(tracePoints, false);
        }
    }
}

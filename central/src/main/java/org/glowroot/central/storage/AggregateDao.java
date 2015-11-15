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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;

import org.glowroot.central.util.ByteBufferInputStream;
import org.glowroot.central.util.Messages;
import org.glowroot.common.live.ImmutableOverallErrorSummary;
import org.glowroot.common.live.ImmutableOverallSummary;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.util.ServerRollups;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class AggregateDao implements AggregateRepository {

    private final Session session;
    private final ServerDao serverDao;
    private final TransactionTypeDao transactionTypeDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertOverallOverviewPS;
    private final ImmutableList<PreparedStatement> insertTransactionOverviewPS;

    private final ImmutableList<PreparedStatement> insertOverallHistogramPS;
    private final ImmutableList<PreparedStatement> insertTransactionHistogramPS;

    private final ImmutableList<PreparedStatement> insertOverallProfilePS;
    private final ImmutableList<PreparedStatement> insertTransactionProfilePS;

    private final ImmutableList<PreparedStatement> insertOverallQueriesPS;
    private final ImmutableList<PreparedStatement> insertTransactionQueriesPS;

    public AggregateDao(Session session, ServerDao serverDao,
            TransactionTypeDao transactionTypeDao) {
        this.session = session;
        this.serverDao = serverDao;
        this.transactionTypeDao = transactionTypeDao;

        session.execute("create table if not exists aggregate_overall_overview_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, capture_time bigint,"
                + " total_nanos double, transaction_count bigint, total_cpu_nanos double,"
                + " total_blocked_nanos double, total_waited_nanos double,"
                + " total_allocated_bytes double, root_timers blob, primary key ((server_rollup,"
                + " transaction_type), capture_time))");

        session.execute("create table if not exists aggregate_transaction_overview_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time bigint, total_nanos double, transaction_count bigint,"
                + " total_cpu_nanos double, total_blocked_nanos double, total_waited_nanos double,"
                + " total_allocated_bytes double, root_timers blob, primary key ((server_rollup,"
                + " transaction_type), capture_time))");

        session.execute("create table if not exists aggregate_overall_histogram_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, capture_time bigint,"
                + " total_nanos double, transaction_count bigint, histogram blob,"
                + " primary key ((server_rollup, transaction_type), capture_time))");

        session.execute("create table if not exists aggregate_transaction_histogram_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time bigint, total_nanos double, transaction_count bigint,"
                + " histogram blob, primary key ((server_rollup, transaction_type,"
                + " transaction_name), capture_time))");

        session.execute("create table if not exists aggregate_overall_profile_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, capture_time bigint,"
                + " profile blob, primary key ((server_rollup, transaction_type), capture_time))");

        session.execute("create table if not exists aggregate_transaction_profile_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time bigint, profile blob, primary key ((server_rollup,"
                + " transaction_type, transaction_name), capture_time))");

        session.execute("create table if not exists aggregate_overall_queries_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, capture_time bigint,"
                + " queries blob, primary key ((server_rollup, transaction_type), capture_time))");

        session.execute("create table if not exists aggregate_transaction_queries_rollup_0"
                + " (server_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time bigint, queries blob, primary key ((server_rollup,"
                + " transaction_type, transaction_name), capture_time))");

        List<PreparedStatement> insertOverallOverviewPS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertOverallOverviewPS.add(session.prepare(
                    "insert into aggregate_overall_overview_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, capture_time, total_nanos,"
                            + " transaction_count, total_cpu_nanos, total_blocked_nanos,"
                            + " total_waited_nanos, total_allocated_bytes, root_timers)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
        }
        this.insertOverallOverviewPS = ImmutableList.copyOf(insertOverallOverviewPS);

        List<PreparedStatement> insertTransactionOverviewPS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertTransactionOverviewPS.add(session.prepare(
                    "insert into aggregate_transaction_overview_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, transaction_name, capture_time,"
                            + " total_nanos, transaction_count, total_cpu_nanos,"
                            + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                            + " root_timers) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
        }
        this.insertTransactionOverviewPS = ImmutableList.copyOf(insertTransactionOverviewPS);

        List<PreparedStatement> insertOverallHistogramPS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertOverallHistogramPS.add(session.prepare(
                    "insert into aggregate_overall_histogram_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, capture_time, total_nanos,"
                            + " transaction_count, histogram) values (?, ?, ?, ?, ?, ?)"));
        }
        this.insertOverallHistogramPS = ImmutableList.copyOf(insertOverallHistogramPS);

        List<PreparedStatement> insertTransactionHistogramPS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertTransactionHistogramPS.add(session.prepare(
                    "insert into aggregate_transaction_histogram_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, transaction_name, capture_time,"
                            + " total_nanos, transaction_count, histogram)"
                            + " values (?, ?, ?, ?, ?, ?, ?)"));
        }
        this.insertTransactionHistogramPS = ImmutableList.copyOf(insertTransactionHistogramPS);

        List<PreparedStatement> insertOverallProfilePS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertOverallProfilePS.add(session.prepare(
                    "insert into aggregate_overall_profile_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, capture_time, profile)"
                            + " values (?, ?, ?, ?)"));
        }
        this.insertOverallProfilePS = ImmutableList.copyOf(insertOverallProfilePS);

        List<PreparedStatement> insertTransactionProfilePS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertTransactionProfilePS.add(session.prepare(
                    "insert into aggregate_transaction_profile_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, transaction_name, capture_time,"
                            + " profile) values (?, ?, ?, ?, ?)"));
        }
        this.insertTransactionProfilePS = ImmutableList.copyOf(insertTransactionProfilePS);

        List<PreparedStatement> insertOverallQueriesPS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertOverallQueriesPS.add(session.prepare(
                    "insert into aggregate_overall_queries_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, capture_time, queries)"
                            + " values (?, ?, ?, ?)"));
        }
        this.insertOverallQueriesPS = ImmutableList.copyOf(insertOverallQueriesPS);

        List<PreparedStatement> insertTransactionQueriesPS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertTransactionQueriesPS.add(session.prepare(
                    "insert into aggregate_transaction_queries_rollup_" + castUntainted(i)
                            + " (server_rollup, transaction_type, transaction_name, capture_time,"
                            + " queries) values (?, ?, ?, ?, ?)"));
        }
        this.insertTransactionQueriesPS = ImmutableList.copyOf(insertTransactionQueriesPS);
    }

    @Override
    public void store(String serverId, long captureTime, List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws IOException {
        List<String> serverRollups = ServerRollups.getServerRollups(serverId);
        for (String serverRollup : serverRollups) {
            storeToServerRollup(serverRollup, captureTime, overallAggregates,
                    transactionAggregates);
            serverDao.updateLastCaptureTime(serverRollup, serverRollup.equals(serverId));
        }
    }

    @Override
    public OverallSummary readOverallSummary(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo) {
        // FIXME
        return ImmutableOverallSummary.builder()
                .totalNanos(0).transactionCount(0)
                .build();
    }

    @Override
    public Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query) {
        // FIXME
        return new Result<>(ImmutableList.<TransactionSummary>of(), false);
    }

    @Override
    public OverallErrorSummary readOverallErrorSummary(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) {
        // FIXME
        return ImmutableOverallErrorSummary.builder()
                .errorCount(0)
                .transactionCount(0)
                .build();
    }

    @Override
    public Result<TransactionErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query) {
        // FIXME
        return new Result<>(ImmutableList.<TransactionErrorSummary>of(), false);
    }

    @Override
    public List<OverviewAggregate> readOverallOverviewAggregates(String serverRollup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws IOException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, total_nanos, transaction_count, total_cpu_nanos,"
                        + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                        + " root_timers from aggregate_overall_overview_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        return buildOverviewAggregates(results);
    }

    @Override
    public List<PercentileAggregate> readOverallPercentileAggregates(String serverRollup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws InvalidProtocolBufferException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, total_nanos, transaction_count, histogram"
                        + " from aggregate_overall_histogram_rollup_" + castUntainted(rollupLevel)
                        + " where server_rollup = ? and transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        return buildPercentileAggregates(results);
    }

    @Override
    public List<ThroughputAggregate> readOverallThroughputAggregates(String serverRollup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws IOException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, transaction_count from aggregate_overall_overview_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        return buildThroughputAggregates(results);
    }

    @Override
    public List<OverviewAggregate> readTransactionOverviewAggregates(String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws IOException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, total_nanos, transaction_count, total_cpu_nanos,"
                        + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                        + " root_timers from aggregate_transaction_overview_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                        + " and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        return buildOverviewAggregates(results);
    }

    @Override
    public List<PercentileAggregate> readTransactionPercentileAggregates(String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws InvalidProtocolBufferException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, total_nanos, transaction_count, histogram"
                        + " from aggregate_transaction_histogram_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                        + " and capture_time <= ?",
                serverRollup, transactionType, transactionName, captureTimeFrom, captureTimeTo);
        return buildPercentileAggregates(results);
    }

    @Override
    public List<ThroughputAggregate> readTransactionThroughputAggregates(String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws IOException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, transaction_count from aggregate_transaction_overview_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                        + " and capture_time <= ?",
                serverRollup, transactionType, transactionName, captureTimeFrom, captureTimeTo);
        return buildThroughputAggregates(results);
    }

    @Override
    public void mergeInOverallProfiles(ProfileCollector mergedProfile, String serverRollup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws InvalidProtocolBufferException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, profile from aggregate_overall_profile_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        mergeInProfiles(mergedProfile, results);
    }

    @Override
    public void mergeInTransactionProfiles(ProfileCollector mergedProfile, String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws InvalidProtocolBufferException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, profile from aggregate_transaction_profile_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        mergeInProfiles(mergedProfile, results);
    }

    @Override
    public void mergeInOverallQueries(QueryCollector mergedQueries, String serverRollup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws IOException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, queries from aggregate_overall_queries_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        mergeInQueries(mergedQueries, results);
    }

    @Override
    public void mergeInTransactionQueries(QueryCollector mergedQueries, String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws IOException {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute(
                "select capture_time, queries from aggregate_transaction_queries_rollup_"
                        + castUntainted(rollupLevel) + " where server_rollup = ?"
                        + " and transaction_type = ? and capture_time > ? and capture_time <= ?",
                serverRollup, transactionType, captureTimeFrom, captureTimeTo);
        mergeInQueries(mergedQueries, results);
    }

    @Override
    public List<ErrorPoint> readOverallErrorPoints(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) {
        return ImmutableList.of();
    }

    @Override
    public List<ErrorPoint> readTransactionErrorPoints(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel) {
        return ImmutableList.of();
    }

    @Override
    public boolean shouldHaveOverallProfile(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo) {
        return false;
    }

    @Override
    public boolean shouldHaveTransactionProfile(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) {
        return false;
    }

    @Override
    public boolean shouldHaveOverallQueries(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo) {
        return false;
    }

    @Override
    public boolean shouldHaveTransactionQueries(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) {
        return false;
    }

    @Override
    public void deleteAll(String serverRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    private void storeToServerRollup(String serverRollup, long captureTime,
            List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws IOException {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        for (OverallAggregate overallAggregate : overallAggregates) {
            storeOverallAggregate(0, serverRollup, overallAggregate.getTransactionType(),
                    captureTime, overallAggregate.getAggregate());
            transactionTypeDao.updateLastCaptureTime(serverRollup,
                    overallAggregate.getTransactionType());
        }
        for (TransactionAggregate transactionAggregate : transactionAggregates) {
            storeTransactionAggregate(0, serverRollup,
                    transactionAggregate.getTransactionType(),
                    transactionAggregate.getTransactionName(), captureTime,
                    transactionAggregate.getAggregate());
        }
    }

    private void storeOverallAggregate(int rollupLevel, String serverRollup,
            String transactionType, long captureTime, Aggregate aggregate) throws IOException {

        BoundStatement boundStatement = insertOverallOverviewPS.get(rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setLong(2, captureTime);
        bindAggregate(boundStatement, aggregate, 3);
        session.execute(boundStatement);

        boundStatement = insertOverallHistogramPS.get(rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setLong(2, captureTime);
        boundStatement.setDouble(3, aggregate.getTotalNanos());
        boundStatement.setLong(4, aggregate.getTransactionCount());
        boundStatement.setBytes(5,
                aggregate.getTotalNanosHistogram().toByteString().asReadOnlyByteBuffer());
        session.execute(boundStatement);

        if (aggregate.hasProfile()) {
            Profile profile = aggregate.getProfile();
            boundStatement = insertOverallProfilePS.get(rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setLong(2, captureTime);
            boundStatement.setBytes(3, profile.toByteString().asReadOnlyByteBuffer());
            session.execute(boundStatement);
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            // TODO optimize byte copying
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Aggregate.QueriesByType queriesByType : queriesByTypeList) {
                queriesByType.writeDelimitedTo(output);
            }
            boundStatement = insertOverallQueriesPS.get(rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setLong(2, captureTime);
            boundStatement.setBytes(3, ByteBuffer.wrap(output.toByteArray()));
            session.execute(boundStatement);
        }
    }

    private void storeTransactionAggregate(int rollupLevel, String serverRollup,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate)
                    throws IOException {

        BoundStatement boundStatement = insertTransactionOverviewPS.get(rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setLong(3, captureTime);
        bindAggregate(boundStatement, aggregate, 4);
        session.execute(boundStatement);

        boundStatement = insertTransactionHistogramPS.get(rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setLong(3, captureTime);
        boundStatement.setDouble(4, aggregate.getTotalNanos());
        boundStatement.setLong(5, aggregate.getTransactionCount());
        boundStatement.setBytes(6,
                aggregate.getTotalNanosHistogram().toByteString().asReadOnlyByteBuffer());
        session.execute(boundStatement);

        if (aggregate.hasProfile()) {
            Profile profile = aggregate.getProfile();
            boundStatement = insertTransactionProfilePS.get(rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setLong(3, captureTime);
            boundStatement.setBytes(4, profile.toByteString().asReadOnlyByteBuffer());
            session.execute(boundStatement);
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            // TODO optimize byte copying
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Aggregate.QueriesByType queriesByType : queriesByTypeList) {
                queriesByType.writeDelimitedTo(output);
            }
            boundStatement = insertTransactionQueriesPS.get(rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setLong(3, captureTime);
            boundStatement.setBytes(4, ByteBuffer.wrap(output.toByteArray()));
            session.execute(boundStatement);
        }
    }

    private void mergeInProfiles(ProfileCollector mergedProfile, ResultSet results)
            throws InvalidProtocolBufferException {
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, row.getLong(0));
            ByteBuffer bytes = row.getBytes(1);
            // TODO optimize this byte copying
            Profile profile = Profile.parseFrom(ByteString.copyFrom(bytes));
            mergedProfile.mergeProfile(profile);
            mergedProfile.updateLastCaptureTime(captureTime);
        }
    }

    private void mergeInQueries(QueryCollector mergedQueries, ResultSet results)
            throws IOException {
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, row.getLong(0));
            ByteBuffer byteBuf = row.getBytes(1);
            try (InputStream input = new ByteBufferInputStream(byteBuf)) {
                Parser<QueriesByType> parser = Aggregate.QueriesByType.parser();
                QueriesByType message;
                while ((message = parser.parseDelimitedFrom(input)) != null) {
                    mergedQueries.mergeQueries(message);
                    mergedQueries.updateLastCaptureTime(captureTime);
                }
            }
        }
    }

    private void bindAggregate(BoundStatement boundStatement, Aggregate aggregate, int startIndex)
            throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        if (aggregate.hasTotalCpuNanos()) {
            boundStatement.setDouble(i++, aggregate.getTotalCpuNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasTotalBlockedNanos()) {
            boundStatement.setDouble(i++, aggregate.getTotalBlockedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasTotalWaitedNanos()) {
            boundStatement.setDouble(i++, aggregate.getTotalWaitedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasTotalAllocatedBytes()) {
            boundStatement.setDouble(i++, aggregate.getTotalAllocatedBytes().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        boundStatement.setBytes(i++, Messages.toByteBuffer(aggregate.getRootTimerList()));
    }

    private static List<OverviewAggregate> buildOverviewAggregates(ResultSet results)
            throws IOException {
        List<OverviewAggregate> overviewAggregates = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long captureTime = row.getLong(i++);
            double totalNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            double totalCpuNanos = getNotAvailableAwareDouble(row, i++);
            double totalBlockedNanos = getNotAvailableAwareDouble(row, i++);
            double totalWaitedNanos = getNotAvailableAwareDouble(row, i++);
            double totalAllocatedBytes = getNotAvailableAwareDouble(row, i++);
            List<Aggregate.Timer> rootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            overviewAggregates.add(ImmutableOverviewAggregate.builder()
                    .captureTime(captureTime)
                    .totalNanos(totalNanos)
                    .transactionCount(transactionCount)
                    .totalCpuNanos(totalCpuNanos)
                    .totalBlockedNanos(totalBlockedNanos)
                    .totalWaitedNanos(totalWaitedNanos)
                    .totalAllocatedBytes(totalAllocatedBytes)
                    .addAllRootTimers(rootTimers)
                    .build());
        }
        return overviewAggregates;
    }

    private static List<PercentileAggregate> buildPercentileAggregates(ResultSet results)
            throws InvalidProtocolBufferException {
        List<PercentileAggregate> percentileAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = row.getLong(0);
            double totalNanos = row.getDouble(1);
            long transactionCount = row.getLong(2);
            Aggregate.Histogram histogram =
                    Aggregate.Histogram.parseFrom(ByteString.copyFrom(row.getBytes(3)));
            percentileAggregates.add(ImmutablePercentileAggregate.builder()
                    .captureTime(captureTime)
                    .totalNanos(totalNanos)
                    .transactionCount(transactionCount)
                    .histogram(histogram)
                    .build());
        }
        return percentileAggregates;
    }

    private static List<ThroughputAggregate> buildThroughputAggregates(ResultSet results)
            throws IOException {
        List<ThroughputAggregate> throughputAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = row.getLong(0);
            long transactionCount = row.getLong(1);
            throughputAggregates.add(ImmutableThroughputAggregate.builder()
                    .captureTime(captureTime)
                    .transactionCount(transactionCount)
                    .build());
        }
        return throughputAggregates;
    }

    private static double getNotAvailableAwareDouble(Row row, int columnIndex) {
        double value = row.getDouble(columnIndex);
        if (row.isNull(columnIndex)) {
            return NotAvailableAware.NA;
        } else {
            return value;
        }
    }
}

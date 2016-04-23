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
package org.glowroot.server.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.Styles;
import org.glowroot.server.util.Messages;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableErrorMessageCount;
import org.glowroot.storage.repo.ImmutableErrorMessagePoint;
import org.glowroot.storage.repo.ImmutableErrorMessageResult;
import org.glowroot.storage.repo.ImmutableHeaderPlus;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.util.AgentRollups;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.Proto.StackTraceElement;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class TraceDao implements TraceRepository {

    private static final String WITH_DTCS =
            "with compaction = { 'class' : 'DateTieredCompactionStrategy' }";

    private final Session session;
    private final ConfigRepository configRepository;
    private final TraceAttributeNameDao traceAttributeNameDao;

    private final PreparedStatement insertOverallSlowPoint;
    private final PreparedStatement insertTransactionSlowPoint;

    private final PreparedStatement insertOverallSlowCount;
    private final PreparedStatement insertTransactionSlowCount;

    private final PreparedStatement insertOverallErrorPoint;
    private final PreparedStatement insertTransactionErrorPoint;

    private final PreparedStatement insertOverallErrorCount;
    private final PreparedStatement insertTransactionErrorCount;

    private final PreparedStatement insertOverallErrorMessage;
    private final PreparedStatement insertTransactionErrorMessage;

    private final PreparedStatement insertHeader;
    private final PreparedStatement insertEntry;
    private final PreparedStatement insertMainThreadProfile;
    private final PreparedStatement insertAuxThreadProfile;

    private final PreparedStatement readOverallSlowPoint;
    private final PreparedStatement readTransactionSlowPoint;
    private final PreparedStatement readOverallErrorPoint;
    private final PreparedStatement readTransactionErrorPoint;

    private final PreparedStatement readOverallErrorMessage;
    private final PreparedStatement readTransactionErrorMessage;

    private final PreparedStatement readHeader;
    private final PreparedStatement readEntries;
    private final PreparedStatement readMainThreadProfile;
    private final PreparedStatement readAuxThreadProfile;

    private final PreparedStatement deletePartialOverallSlowPoint;
    private final PreparedStatement deletePartialTransactionSlowPoint;

    private final PreparedStatement deletePartialOverallSlowCount;
    private final PreparedStatement deletePartialTransactionSlowCount;

    public TraceDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;
        traceAttributeNameDao = new TraceAttributeNameDao(session, configRepository);

        session.execute("create table if not exists trace_tt_slow_point (agent_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, duration_nanos bigint, error boolean, headline varchar,"
                + " user varchar, attributes blob, primary key ((agent_rollup, transaction_type),"
                + " capture_time, agent_id, trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_tn_slow_point (agent_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, duration_nanos bigint, error boolean,"
                + " headline varchar, user varchar, attributes blob, primary key ((agent_rollup,"
                + " transaction_type, transaction_name), capture_time, agent_id, trace_id)) "
                + WITH_DTCS);

        session.execute("create table if not exists trace_tt_error_point (agent_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, duration_nanos bigint, error_message varchar,"
                + " headline varchar, user varchar, attributes blob, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_tn_error_point (agent_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, duration_nanos bigint,"
                + " error_message varchar, headline varchar, user varchar, attributes blob,"
                + " primary key ((agent_rollup, transaction_type, transaction_name), capture_time,"
                + " agent_id, trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_tt_error_message (agent_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, error_message varchar, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_tn_error_message (agent_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, error_message varchar, primary key"
                + " ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id,"
                + " trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_header (agent_id varchar,"
                + " trace_id varchar, header blob, primary key (agent_id, trace_id)) " + WITH_DTCS);

        // "index" is cassandra reserved word
        session.execute("create table if not exists trace_entry (agent_id varchar,"
                + " trace_id varchar, index_ int, depth int, start_offset_nanos bigint,"
                + " duration_nanos bigint, active boolean, message varchar, detail blob,"
                + " location_stack_trace blob, error blob, primary key (agent_id, trace_id,"
                + " index_)) " + WITH_DTCS);

        session.execute("create table if not exists trace_main_thread_profile (agent_id varchar,"
                + " trace_id varchar, profile blob, primary key (agent_id, trace_id)) "
                + WITH_DTCS);

        session.execute("create table if not exists trace_aux_thread_profile (agent_id varchar,"
                + " trace_id varchar, profile blob, primary key (agent_id, trace_id)) "
                + WITH_DTCS);

        // agent_rollup/capture_time is not necessarily unique
        // using a counter would be nice since only need sum over capture_time range
        // but counter has no TTL, see https://issues.apache.org/jira/browse/CASSANDRA-2103
        // so adding trace_id to provide uniqueness
        session.execute("create table if not exists trace_tt_slow_count (agent_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, primary key ((agent_rollup, transaction_type), capture_time,"
                + " agent_id, trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_tn_slow_count (agent_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                + " transaction_type, transaction_name), capture_time, agent_id, trace_id)) "
                + WITH_DTCS);

        session.execute("create table if not exists trace_tt_error_count (agent_rollup varchar,"
                + " transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, primary key ((agent_rollup, transaction_type), capture_time,"
                + " agent_id, trace_id)) " + WITH_DTCS);

        session.execute("create table if not exists trace_tn_error_count (agent_rollup varchar,"
                + " transaction_type varchar, transaction_name varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                + " transaction_type, transaction_name), capture_time, agent_id, trace_id)) "
                + WITH_DTCS);

        insertOverallSlowPoint = session.prepare("insert into trace_tt_slow_point (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id, duration_nanos, error,"
                + " user, attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionSlowPoint = session.prepare("insert into trace_tn_slow_point"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, duration_nanos, error, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallSlowCount = session.prepare("insert into trace_tt_slow_count (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id) values (?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertTransactionSlowCount = session.prepare("insert into trace_tn_slow_count"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallErrorPoint = session.prepare("insert into trace_tt_error_point (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id, duration_nanos,"
                + " error_message, user, attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertTransactionErrorPoint = session.prepare("insert into trace_tn_error_point"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, duration_nanos, error_message, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallErrorCount = session.prepare("insert into trace_tt_error_count (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id) values (?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertTransactionErrorCount = session.prepare("insert into trace_tn_error_count"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallErrorMessage = session.prepare("insert into trace_tt_error_message"
                + " (agent_rollup, transaction_type, capture_time, agent_id, trace_id,"
                + " error_message) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionErrorMessage = session.prepare("insert into trace_tn_error_message"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, error_message) values (?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertHeader = session.prepare("insert into trace_header (agent_id, trace_id, header)"
                + " values (?, ?, ?) using ttl ?");

        insertEntry = session.prepare("insert into trace_entry (agent_id, trace_id, index_, depth,"
                + " start_offset_nanos, duration_nanos, active, message, detail,"
                + " location_stack_trace, error) values (?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?) using ttl ?");

        insertMainThreadProfile = session.prepare("insert into trace_main_thread_profile"
                + " (agent_id, trace_id, profile) values (?, ?, ?) using ttl ?");

        insertAuxThreadProfile = session.prepare("insert into trace_aux_thread_profile"
                + " (agent_id, trace_id, profile) values (?, ?, ?) using ttl ?");

        readOverallSlowPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, error, headline, user, attributes from trace_tt_slow_point"
                + " where agent_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?");

        readTransactionSlowPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, error, headline, user, attributes from trace_tn_slow_point"
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?");

        readOverallErrorPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, headline, error_message, user, attributes"
                + " from trace_tt_error_point where agent_rollup = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?");

        readTransactionErrorPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, headline, error_message, user, attributes"
                + " from trace_tn_error_point where agent_rollup = ? and transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readOverallErrorMessage = session.prepare("select capture_time, error_message"
                + " from trace_tt_error_message where agent_rollup = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?");

        readTransactionErrorMessage = session.prepare("select capture_time, error_message"
                + " from trace_tn_error_message where agent_rollup = ? and transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readHeader = session
                .prepare("select header from trace_header where agent_id = ? and trace_id = ?");

        readEntries = session.prepare("select depth, start_offset_nanos, duration_nanos,"
                + " active, message, detail, location_stack_trace, error from"
                + " trace_entry where agent_id = ? and trace_id = ?");

        readMainThreadProfile = session.prepare("select profile from trace_main_thread_profile"
                + " where agent_id = ? and trace_id = ?");

        readAuxThreadProfile = session.prepare("select profile from trace_aux_thread_profile"
                + " where agent_id = ? and trace_id = ?");

        deletePartialOverallSlowPoint = session.prepare("delete from trace_tt_slow_point"
                + " where agent_rollup = ? and transaction_type = ? and capture_time = ?"
                + " and agent_id = ? and trace_id = ?");

        deletePartialTransactionSlowPoint = session.prepare("delete from trace_tn_slow_point"
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time = ? and agent_id = ? and trace_id = ?");

        deletePartialOverallSlowCount = session.prepare("delete from trace_tt_slow_count"
                + " where agent_rollup = ? and transaction_type = ? and capture_time = ?"
                + " and agent_id = ? and trace_id = ?");

        deletePartialTransactionSlowCount = session.prepare("delete from trace_tn_slow_count"
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time = ? and agent_id = ? and trace_id = ?");
    }

    @Override
    public void collect(String agentId, Trace trace) throws Exception {
        String traceId = trace.getId();
        // TEMPORARY UNTIL ROLL OUT AGENT 0.9.1
        traceId = traceId.replaceAll("-", "");
        traceId = traceId.substring(traceId.length() - 20);
        traceId = lowerSixBytesHex(trace.getHeader().getStartTime()) + traceId;
        // END TEMPORARY
        Trace.Header priorHeader = readHeader(agentId, traceId);
        Trace.Header header = trace.getHeader();

        // unlike aggregates and gauge values, traces can get written to server rollups immediately
        List<String> agentRollups = AgentRollups.getAgentRollups(agentId);

        List<ResultSetFuture> futures = Lists.newArrayList();
        int ttl = getTTL();
        for (String agentRollup : agentRollups) {
            List<Trace.Attribute> attributes = header.getAttributeList();
            if (header.getSlow()) {
                BoundStatement boundStatement = insertOverallSlowPoint.bind();
                int i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setBool(i++, header.hasError());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                if (attributes.isEmpty()) {
                    boundStatement.setToNull(i++);
                } else {
                    boundStatement.setBytes(i++, Messages.toByteBuffer(attributes));
                }
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionSlowPoint.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setBool(i++, header.hasError());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                if (attributes.isEmpty()) {
                    boundStatement.setToNull(i++);
                } else {
                    boundStatement.setBytes(i++, Messages.toByteBuffer(attributes));
                }
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertOverallSlowCount.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionSlowCount.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                if (priorHeader != null) {
                    boundStatement = deletePartialOverallSlowPoint.bind();
                    i = 0;
                    boundStatement.setString(i++, agentRollup);
                    boundStatement.setString(i++, priorHeader.getTransactionType());
                    boundStatement.setTimestamp(i++, new Date(priorHeader.getCaptureTime()));
                    boundStatement.setString(i++, agentId);
                    boundStatement.setString(i++, traceId);
                    futures.add(session.executeAsync(boundStatement));

                    boundStatement = deletePartialTransactionSlowPoint.bind();
                    i = 0;
                    boundStatement.setString(i++, agentRollup);
                    boundStatement.setString(i++, priorHeader.getTransactionType());
                    boundStatement.setString(i++, priorHeader.getTransactionName());
                    boundStatement.setTimestamp(i++, new Date(priorHeader.getCaptureTime()));
                    boundStatement.setString(i++, agentId);
                    boundStatement.setString(i++, traceId);
                    futures.add(session.executeAsync(boundStatement));

                    boundStatement = deletePartialOverallSlowCount.bind();
                    i = 0;
                    boundStatement.setString(i++, agentRollup);
                    boundStatement.setString(i++, priorHeader.getTransactionType());
                    boundStatement.setTimestamp(i++, new Date(priorHeader.getCaptureTime()));
                    boundStatement.setString(i++, agentId);
                    boundStatement.setString(i++, traceId);
                    futures.add(session.executeAsync(boundStatement));

                    boundStatement = deletePartialTransactionSlowCount.bind();
                    i = 0;
                    boundStatement.setString(i++, agentRollup);
                    boundStatement.setString(i++, priorHeader.getTransactionType());
                    boundStatement.setString(i++, priorHeader.getTransactionName());
                    boundStatement.setTimestamp(i++, new Date(priorHeader.getCaptureTime()));
                    boundStatement.setString(i++, agentId);
                    boundStatement.setString(i++, traceId);
                    futures.add(session.executeAsync(boundStatement));
                }
            }
            // seems unnecessary to insert error info for partial traces
            // and this avoids having to clean up partial trace data when trace is complete
            if (header.hasError() && !header.getPartial()) {
                BoundStatement boundStatement = insertOverallErrorMessage.bind();
                int i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setString(i++, header.getError().getMessage());
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionErrorMessage.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setString(i++, header.getError().getMessage());
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertOverallErrorPoint.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setString(i++, header.getError().getMessage());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                if (attributes.isEmpty()) {
                    boundStatement.setToNull(i++);
                } else {
                    boundStatement.setBytes(i++, Messages.toByteBuffer(attributes));
                }
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionErrorPoint.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setLong(i++, header.getDurationNanos());
                boundStatement.setString(i++, header.getError().getMessage());
                boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
                if (attributes.isEmpty()) {
                    boundStatement.setToNull(i++);
                } else {
                    boundStatement.setBytes(i++, Messages.toByteBuffer(attributes));
                }
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertOverallErrorCount.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionErrorCount.bind();
                i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, header.getTransactionType());
                boundStatement.setString(i++, header.getTransactionName());
                boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
                boundStatement.setString(i++, agentId);
                boundStatement.setString(i++, traceId);
                boundStatement.setInt(i++, ttl);
                futures.add(session.executeAsync(boundStatement));
            }
            for (Trace.Attribute attributeName : attributes) {
                traceAttributeNameDao.maybeUpdateLastCaptureTime(agentRollup,
                        header.getTransactionType(), attributeName.getName(), futures);
            }
        }

        BoundStatement boundStatement = insertHeader.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, traceId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(trace.getHeader().toByteArray()));
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        for (int entryIndex = 0; entryIndex < trace.getEntryCount(); entryIndex++) {
            Trace.Entry entry = trace.getEntry(entryIndex);
            boundStatement = insertEntry.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, traceId);
            boundStatement.setInt(i++, entryIndex);
            boundStatement.setInt(i++, entry.getDepth());
            boundStatement.setLong(i++, entry.getStartOffsetNanos());
            boundStatement.setLong(i++, entry.getDurationNanos());
            boundStatement.setBool(i++, entry.getActive());
            boundStatement.setString(i++, entry.getMessage());
            List<Trace.DetailEntry> detailEntries = entry.getDetailEntryList();
            if (detailEntries.isEmpty()) {
                boundStatement.setToNull(i++);
            } else {
                boundStatement.setBytes(i++, Messages.toByteBuffer(detailEntries));
            }
            List<StackTraceElement> location = entry.getLocationStackTraceElementList();
            if (location.isEmpty()) {
                boundStatement.setToNull(i++);
            } else {
                boundStatement.setBytes(i++, Messages.toByteBuffer(location));
            }
            if (entry.hasError()) {
                boundStatement.setBytes(i++, ByteBuffer.wrap(entry.getError().toByteArray()));
            } else {
                boundStatement.setToNull(i++);
            }
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }

        if (trace.hasMainThreadProfile()) {
            boundStatement = insertMainThreadProfile.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, traceId);
            boundStatement.setBytes(i++,
                    ByteBuffer.wrap(trace.getMainThreadProfile().toByteArray()));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }

        if (trace.hasAuxThreadProfile()) {
            boundStatement = insertAuxThreadProfile.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, traceId);
            boundStatement.setBytes(i++,
                    ByteBuffer.wrap(trace.getAuxThreadProfile().toByteArray()));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        Futures.allAsList(futures).get();
    }

    @Override
    public List<String> readTraceAttributeNames(String agentRollup, String transactionType) {
        return traceAttributeNameDao.getTraceAttributeNames(agentRollup, transactionType);
    }

    @Override
    public Result<TracePoint> readSlowPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws IOException {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            BoundStatement boundStatement = readOverallSlowPoint.bind();
            boundStatement.setString(0, query.agentRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setTimestamp(2, new Date(query.from()));
            boundStatement.setTimestamp(3, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, false);
        } else {
            BoundStatement boundStatement = readTransactionSlowPoint.bind();
            boundStatement.setString(0, query.agentRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(query.from()));
            boundStatement.setTimestamp(4, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, false);
        }
    }

    @Override
    public Result<TracePoint> readErrorPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws IOException {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            BoundStatement boundStatement = readOverallErrorPoint.bind();
            boundStatement.setString(0, query.agentRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setTimestamp(2, new Date(query.from()));
            boundStatement.setTimestamp(3, new Date(query.to()));
            ResultSet results = session.execute(boundStatement);
            return processPoints(results, filter, limit, true);
        } else {
            BoundStatement boundStatement = readTransactionErrorPoint.bind();
            boundStatement.setString(0, query.agentRollup());
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
                    "select count(*) from trace_tt_slow_count where agent_rollup = ?"
                            + " and transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ?",
                    query.agentRollup(), query.transactionType(), query.from(), query.to());
            return results.one().getLong(0);
        } else {
            ResultSet results = session.execute(
                    "select count(*) from trace_tn_slow_count where agent_rollup = ?"
                            + " and transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ?",
                    query.agentRollup(), query.transactionType(), transactionName, query.from(),
                    query.to());
            return results.one().getLong(0);
        }
    }

    @Override
    public long readErrorCount(TraceQuery query) {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            ResultSet results = session.execute(
                    "select count(*) from trace_tt_error_count where agent_rollup = ?"
                            + " and transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ?",
                    query.agentRollup(), query.transactionType(), query.from(), query.to());
            return results.one().getLong(0);
        } else {
            ResultSet results = session.execute(
                    "select count(*) from trace_tn_error_count where agent_rollup = ?"
                            + " and transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ?",
                    query.agentRollup(), query.transactionType(), transactionName, query.from(),
                    query.to());
            return results.one().getLong(0);
        }
    }

    @Override
    public ErrorMessageResult readErrorMessages(TraceQuery query, ErrorMessageFilter filter,
            long resolutionMillis, int limit) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorMessage.bind();
            boundStatement.setString(0, query.agentRollup());
            boundStatement.setString(1, query.transactionType());
            boundStatement.setTimestamp(2, new Date(query.from()));
            boundStatement.setTimestamp(3, new Date(query.to()));
        } else {
            boundStatement = readTransactionErrorMessage.bind();
            boundStatement.setString(0, query.agentRollup());
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
            if (!matches(filter, errorMessage)) {
                continue;
            }
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
    public @Nullable HeaderPlus readHeaderPlus(String agentId, String traceId)
            throws InvalidProtocolBufferException {
        Trace.Header header = readHeader(agentId, traceId);
        if (header == null) {
            return null;
        }
        Existence entriesExistence = header.getEntryCount() == 0 ? Existence.NO : Existence.YES;
        Existence profileExistence = header.getMainThreadProfileSampleCount() == 0
                && header.getAuxThreadProfileSampleCount() == 0 ? Existence.NO : Existence.YES;
        return ImmutableHeaderPlus.of(header, entriesExistence, profileExistence);
    }

    @Override
    public List<Trace.Entry> readEntries(String agentId, String traceId) throws IOException {
        BoundStatement boundStatement = readEntries.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        List<Trace.Entry> entries = Lists.newArrayList();
        while (!results.isExhausted()) {
            Row row = results.one();
            int i = 0;
            Trace.Entry.Builder entry = Trace.Entry.newBuilder()
                    .setDepth(row.getInt(i++))
                    .setStartOffsetNanos(row.getLong(i++))
                    .setDurationNanos(row.getLong(i++))
                    .setActive(row.getBool(i++))
                    .setMessage(Strings.nullToEmpty(row.getString(i++)));
            ByteBuffer detailBytes = row.getBytes(i++);
            if (detailBytes != null) {
                entry.addAllDetailEntry(
                        Messages.parseDelimitedFrom(detailBytes, Trace.DetailEntry.parser()));
            }
            ByteBuffer locationBytes = row.getBytes(i++);
            if (locationBytes != null) {
                entry.addAllLocationStackTraceElement(Messages.parseDelimitedFrom(locationBytes,
                        Proto.StackTraceElement.parser()));
            }
            ByteBuffer errorBytes = row.getBytes(i++);
            if (errorBytes != null) {
                entry.setError(Trace.Error.parseFrom(ByteString.copyFrom(errorBytes)));
            }
            entries.add(entry.build());
        }
        return entries;
    }

    @Override
    public @Nullable Profile readMainThreadProfile(String agentId, String traceId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readMainThreadProfile.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Profile.parseFrom(ByteString.copyFrom(bytes));
    }

    @Override
    public @Nullable Profile readAuxThreadProfile(String agentId, String traceId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readAuxThreadProfile.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Profile.parseFrom(ByteString.copyFrom(bytes));
    }

    @Override
    public void deleteAll(String agentRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    private @Nullable Trace.Header readHeader(String agentId, String traceId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readHeader.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        return Trace.Header.parseFrom(ByteString.copyFrom(bytes));
    }

    private int getTTL() {
        return Ints.saturatedCast(
                HOURS.toSeconds(configRepository.getStorageConfig().traceExpirationHours()));
    }

    private static Result<TracePoint> processPoints(ResultSet results, TracePointFilter filter,
            int limit, boolean errorPoints) throws IOException {
        List<TracePoint> tracePoints = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            String agentId = checkNotNull(row.getString(i++));
            String traceId = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long durationNanos = row.getLong(i++);
            boolean error = errorPoints ? true : row.getBool(i++);
            // headline is always non-null, except for old data prior to when headline column added
            String headline = Strings.nullToEmpty(row.getString(i++));
            // error points are defined by having an error message, so safe to checkNotNull
            String errorMessage = errorPoints ? checkNotNull(row.getString(i++)) : "";
            String user = Strings.nullToEmpty(row.getString(i++));
            ByteBuffer attributeBytes = row.getBytes(i++);
            List<Trace.Attribute> attrs =
                    Messages.parseDelimitedFrom(attributeBytes, Trace.Attribute.parser());
            Map<String, List<String>> attributes = attrs.stream().collect(
                    Collectors.toMap(Trace.Attribute::getName, Trace.Attribute::getValueList));
            if (filter.matchesDuration(durationNanos)
                    && filter.matchesHeadline(headline)
                    && filter.matchesError(errorMessage)
                    && filter.matchesUser(user)
                    && filter.matchesAttributes(attributes)) {
                tracePoints.add(ImmutableTracePoint.builder()
                        .agentId(agentId)
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
            TraceKey traceKey = ImmutableTraceKey.of(trace.agentId(), trace.traceId());
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

    private static boolean matches(ErrorMessageFilter filter, String errorMessage) {
        String upper = errorMessage.toUpperCase(Locale.ENGLISH);
        for (String include : filter.includes()) {
            if (!upper.contains(include.toUpperCase(Locale.ENGLISH))) {
                return false;
            }
        }
        for (String exclude : filter.excludes()) {
            if (upper.contains(exclude.toUpperCase(Locale.ENGLISH))) {
                return false;
            }
        }
        return true;
    }

    static String lowerSixBytesHex(long startTime) {
        long mask = 1L << 48;
        return Long.toHexString(mask | (startTime & (mask - 1))).substring(1);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceKey {
        String agentId();
        String traceId();
    }

    private static class MutableLong {
        private long value;
        private void increment() {
            value++;
        }
    }
}

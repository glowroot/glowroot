/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.central.repo;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.google.common.base.Strings;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.futures.CompletableFutures;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.util.CassandraWriteMetrics;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.Session;
import org.glowroot.common.Constants;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.ImmutableEntriesAndQueries;
import org.glowroot.common.live.ImmutableQueries;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.*;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common2.repo.*;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.Proto.OptionalInt64;
import org.glowroot.wire.api.model.Proto.StackTraceElement;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.immutables.value.Value;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;

public class TraceDaoImpl implements TraceDao {

    @SuppressWarnings("deprecation")
    private static final HashFunction SHA_1 = Hashing.sha1();

    private final Session session;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

    private final boolean cassandra2x;

    private final PreparedStatement insertOverallSlowCount;
    private final PreparedStatement insertOverallSlowCountPartial;
    private final PreparedStatement insertTransactionSlowCount;
    private final PreparedStatement insertTransactionSlowCountPartial;

    private final PreparedStatement insertOverallSlowPoint;
    private final PreparedStatement insertOverallSlowPointPartial;
    private final PreparedStatement insertTransactionSlowPoint;
    private final PreparedStatement insertTransactionSlowPointPartial;

    private final PreparedStatement insertOverallErrorCount;
    private final PreparedStatement insertTransactionErrorCount;

    private final PreparedStatement insertOverallErrorPoint;
    private final PreparedStatement insertTransactionErrorPoint;

    private final PreparedStatement insertOverallErrorMessage;
    private final PreparedStatement insertTransactionErrorMessage;

    private final PreparedStatement insertHeaderV2;
    private final PreparedStatement insertEntryV2;
    private final PreparedStatement insertQueryV2;
    private final PreparedStatement insertSharedQueryTextV2;
    private final PreparedStatement insertMainThreadProfileV2;
    private final PreparedStatement insertAuxThreadProfileV2;

    private final PreparedStatement readOverallSlowCount;
    private final PreparedStatement readOverallSlowCountPartial;
    private final PreparedStatement readTransactionSlowCount;
    private final PreparedStatement readTransactionSlowCountPartial;

    private final PreparedStatement readOverallSlowPoint;
    private final PreparedStatement readOverallSlowPointPartial;
    private final PreparedStatement readTransactionSlowPoint;
    private final PreparedStatement readTransactionSlowPointPartial;

    private final PreparedStatement readOverallErrorCount;
    private final PreparedStatement readTransactionErrorCount;

    private final PreparedStatement readOverallErrorPoint;
    private final PreparedStatement readTransactionErrorPoint;

    private final PreparedStatement readOverallErrorMessage;
    private final PreparedStatement readTransactionErrorMessage;

    private final PreparedStatement readHeaderV1;
    private final PreparedStatement readEntriesV1;
    private final PreparedStatement readSharedQueryTextsV1;
    private final PreparedStatement readMainThreadProfileV1;
    private final PreparedStatement readAuxThreadProfileV1;

    private final PreparedStatement readHeaderV2;
    private final PreparedStatement readEntriesV2;
    private final PreparedStatement readQueriesV2;
    private final PreparedStatement readSharedQueryTextsV2;
    private final PreparedStatement readMainThreadProfileV2;
    private final PreparedStatement readAuxThreadProfileV2;

    private final PreparedStatement deleteOverallSlowCountPartial;
    private final PreparedStatement deleteTransactionSlowCountPartial;

    private final PreparedStatement deleteOverallSlowPointPartial;
    private final PreparedStatement deleteTransactionSlowPointPartial;

    TraceDaoImpl(Session session, TransactionTypeDao transactionTypeDao,
                 FullQueryTextDao fullQueryTextDao, TraceAttributeNameDao traceAttributeNameDao,
                 ConfigRepositoryImpl configRepository, Clock clock) throws Exception {
        this.session = session;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.traceAttributeNameDao = traceAttributeNameDao;
        this.configRepository = configRepository;
        this.clock = clock;

        AsyncResultSet results =
                session.readAsync("select release_version from system.local where key = 'local'", CassandraProfile.slow).toCompletableFuture().get();
        Row row = checkNotNull(results.one());
        String cassandraVersion = checkNotNull(row.getString(0));
        cassandra2x = cassandraVersion.startsWith("2.");

        int expirationHours = configRepository.getCentralStorageConfig().toCompletableFuture().join().traceExpirationHours();

        // agent_rollup/capture_time is not necessarily unique
        // using a counter would be nice since only need sum over capture_time range
        // but counter has no TTL, see https://issues.apache.org/jira/browse/CASSANDRA-2103
        // so adding trace_id to provide uniqueness
        session.createTableWithTWCS("create table if not exists trace_tt_slow_count (agent_rollup"
                + " varchar, transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, primary key ((agent_rollup, transaction_type), capture_time,"
                + " agent_id, trace_id))", expirationHours);

        // "capture_time" column now should be "capture_time_partial_rollup" (since 0.13.1)
        // (and "real_capture_time" column now should be "capture_time")
        session.createTableWithTWCS("create table if not exists trace_tt_slow_count_partial"
                        + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                        + " agent_id varchar, trace_id varchar, real_capture_time timestamp, primary key"
                        + " ((agent_rollup, transaction_type), capture_time, agent_id, trace_id))",
                expirationHours, false, true);

        session.createTableWithTWCS("create table if not exists trace_tn_slow_count (agent_rollup"
                        + " varchar, transaction_type varchar, transaction_name varchar, capture_time"
                        + " timestamp, agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                        + " transaction_type, transaction_name), capture_time, agent_id, trace_id))",
                expirationHours);

        // "capture_time" column now should be "capture_time_partial_rollup" (since 0.13.1)
        // (and "real_capture_time" column now should be "capture_time")
        session.createTableWithTWCS("create table if not exists trace_tn_slow_count_partial"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar, real_capture_time"
                + " timestamp, primary key ((agent_rollup, transaction_type, transaction_name),"
                + " capture_time, agent_id, trace_id))", expirationHours, false, true);

        session.createTableWithTWCS("create table if not exists trace_tt_slow_point (agent_rollup"
                + " varchar, transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, duration_nanos bigint, error boolean, headline varchar, user"
                + " varchar, attributes blob, primary key ((agent_rollup, transaction_type),"
                + " capture_time, agent_id, trace_id))", expirationHours);

        // "capture_time" column now should be "capture_time_partial_rollup" (since 0.13.1)
        // (and "real_capture_time" column now should be "capture_time")
        session.createTableWithTWCS("create table if not exists trace_tt_slow_point_partial"
                        + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                        + " agent_id varchar, trace_id varchar, real_capture_time timestamp, duration_nanos"
                        + " bigint, error boolean, headline varchar, user varchar, attributes blob, primary"
                        + " key ((agent_rollup, transaction_type), capture_time, agent_id, trace_id))",
                expirationHours, false, true);

        session.createTableWithTWCS("create table if not exists trace_tn_slow_point (agent_rollup"
                + " varchar, transaction_type varchar, transaction_name varchar, capture_time"
                + " timestamp, agent_id varchar, trace_id varchar, duration_nanos bigint, error"
                + " boolean, headline varchar, user varchar, attributes blob, primary key"
                + " ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id,"
                + " trace_id))", expirationHours);

        // "capture_time" column now should be "capture_time_partial_rollup" (since 0.13.1)
        // (and "real_capture_time" column now should be "capture_time")
        session.createTableWithTWCS("create table if not exists trace_tn_slow_point_partial"
                        + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                        + " capture_time timestamp, agent_id varchar, trace_id varchar, real_capture_time"
                        + " timestamp, duration_nanos bigint, error boolean, headline varchar, user"
                        + " varchar, attributes blob, primary key ((agent_rollup, transaction_type,"
                        + " transaction_name), capture_time, agent_id, trace_id))", expirationHours, false,
                true);

        session.createTableWithTWCS("create table if not exists trace_tt_error_count (agent_rollup"
                + " varchar, transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, primary key ((agent_rollup, transaction_type), capture_time,"
                + " agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_error_count (agent_rollup"
                        + " varchar, transaction_type varchar, transaction_name varchar, capture_time"
                        + " timestamp, agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                        + " transaction_type, transaction_name), capture_time, agent_id, trace_id))",
                expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tt_error_point (agent_rollup"
                + " varchar, transaction_type varchar, capture_time timestamp, agent_id varchar,"
                + " trace_id varchar, duration_nanos bigint, error_message varchar, headline"
                + " varchar, user varchar, attributes blob, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_error_point (agent_rollup"
                + " varchar, transaction_type varchar, transaction_name varchar, capture_time"
                + " timestamp, agent_id varchar, trace_id varchar, duration_nanos bigint,"
                + " error_message varchar, headline varchar, user varchar, attributes blob, primary"
                + " key ((agent_rollup, transaction_type, transaction_name), capture_time,"
                + " agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tt_error_message"
                        + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                        + " agent_id varchar, trace_id varchar, error_message varchar, primary key"
                        + " ((agent_rollup, transaction_type), capture_time, agent_id, trace_id))",
                expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_error_message"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar, error_message"
                + " varchar, primary key ((agent_rollup, transaction_type, transaction_name),"
                + " capture_time, agent_id, trace_id))", expirationHours);

        // ===== trace components v1 =====

        session.createTableWithTWCS("create table if not exists trace_header (agent_id varchar,"
                        + " trace_id varchar, header blob, primary key (agent_id, trace_id))",
                expirationHours);

        // index_ is just to provide uniqueness
        session.createTableWithTWCS("create table if not exists trace_entry (agent_id varchar,"
                + " trace_id varchar, index_ int, depth int, start_offset_nanos bigint,"
                + " duration_nanos bigint, active boolean, message varchar, shared_query_text_index"
                + " int, query_message_prefix varchar, query_message_suffix varchar, detail blob,"
                + " location_stack_trace blob, error blob, primary key (agent_id, trace_id,"
                + " index_))", expirationHours);

        // index_ is just to provide uniqueness
        session.createTableWithTWCS("create table if not exists trace_shared_query_text (agent_id"
                + " varchar, trace_id varchar, index_ int, truncated_text varchar,"
                + " truncated_end_text varchar, full_text_sha1 varchar, primary key (agent_id,"
                + " trace_id, index_))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_main_thread_profile (agent_id"
                        + " varchar, trace_id varchar, profile blob, primary key (agent_id, trace_id))",
                expirationHours);

        session.createTableWithTWCS("create table if not exists trace_aux_thread_profile (agent_id"
                        + " varchar, trace_id varchar, profile blob, primary key (agent_id, trace_id))",
                expirationHours);

        // ===== trace components v2 =====

        session.createTableWithTWCS("create table if not exists trace_header_v2 (agent_id varchar,"
                        + " trace_id varchar, header blob, primary key ((agent_id, trace_id)))",
                expirationHours);

        // index_ is used to provide uniqueness and ordering
        session.createTableWithTWCS("create table if not exists trace_entry_v2 (agent_id varchar,"
                + " trace_id varchar, index_ int, depth int, start_offset_nanos bigint,"
                + " duration_nanos bigint, active boolean, message varchar, shared_query_text_index"
                + " int, query_message_prefix varchar, query_message_suffix varchar, detail blob,"
                + " location_stack_trace blob, error blob, primary key ((agent_id, trace_id),"
                + " index_))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_query_v2 (agent_id varchar,"
                + " trace_id varchar, type varchar, shared_query_text_index int,"
                + " total_duration_nanos double, execution_count bigint, total_rows bigint,"
                + " active boolean, primary key ((agent_id, trace_id), type,"
                + " shared_query_text_index))", expirationHours);

        // index_ is used to provide uniqueness and ordering
        session.createTableWithTWCS("create table if not exists trace_shared_query_text_v2"
                + " (agent_id varchar, trace_id varchar, index_ int, truncated_text varchar,"
                + " truncated_end_text varchar, full_text_sha1 varchar, primary key ((agent_id,"
                + " trace_id), index_))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_main_thread_profile_v2"
                + " (agent_id varchar, trace_id varchar, profile blob, primary key ((agent_id,"
                + " trace_id)))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_aux_thread_profile_v2"
                + " (agent_id varchar, trace_id varchar, profile blob, primary key ((agent_id,"
                + " trace_id)))", expirationHours);

        insertOverallSlowCount = session.prepare("insert into trace_tt_slow_count (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id) values (?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertOverallSlowCountPartial = session.prepare("insert into trace_tt_slow_count_partial"
                + " (agent_rollup, transaction_type, capture_time, agent_id, trace_id,"
                + " real_capture_time) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionSlowCount = session.prepare("insert into trace_tn_slow_count"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionSlowCountPartial = session.prepare("insert into"
                + " trace_tn_slow_count_partial (agent_rollup, transaction_type, transaction_name,"
                + " capture_time, agent_id, trace_id, real_capture_time) values (?, ?, ?, ?, ?, ?,"
                + " ?) using ttl ?");

        insertOverallSlowPoint = session.prepare("insert into trace_tt_slow_point (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id, duration_nanos, error,"
                + " headline, user, attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallSlowPointPartial = session.prepare("insert into trace_tt_slow_point_partial"
                + " (agent_rollup, transaction_type, capture_time, agent_id, trace_id,"
                + " real_capture_time, duration_nanos, error, headline, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionSlowPoint = session.prepare("insert into trace_tn_slow_point"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, duration_nanos, error, headline, user, attributes) values (?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionSlowPointPartial = session.prepare("insert into"
                + " trace_tn_slow_point_partial (agent_rollup, transaction_type, transaction_name,"
                + " capture_time, agent_id, trace_id, real_capture_time, duration_nanos, error,"
                + " headline, user, attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using"
                + " ttl ?");

        insertOverallErrorCount = session.prepare("insert into trace_tt_error_count (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id) values (?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertTransactionErrorCount = session.prepare("insert into trace_tn_error_count"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallErrorPoint = session.prepare("insert into trace_tt_error_point (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id, duration_nanos,"
                + " error_message, headline, user, attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?) using ttl ?");

        insertTransactionErrorPoint = session.prepare("insert into trace_tn_error_point"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, duration_nanos, error_message, headline, user, attributes) values (?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallErrorMessage = session.prepare("insert into trace_tt_error_message"
                + " (agent_rollup, transaction_type, capture_time, agent_id, trace_id,"
                + " error_message) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionErrorMessage = session.prepare("insert into trace_tn_error_message"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, error_message) values (?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertHeaderV2 = session.prepare("insert into trace_header_v2 (agent_id, trace_id, header)"
                + " values (?, ?, ?) using ttl ?");

        insertEntryV2 = session.prepare("insert into trace_entry_v2 (agent_id, trace_id, index_,"
                + " depth, start_offset_nanos, duration_nanos, active, message,"
                + " shared_query_text_index, query_message_prefix, query_message_suffix, detail,"
                + " location_stack_trace, error) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertQueryV2 = session.prepare("insert into trace_query_v2 (agent_id, trace_id, type,"
                + " shared_query_text_index, total_duration_nanos, execution_count, total_rows,"
                + " active) values (?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertSharedQueryTextV2 = session.prepare("insert into trace_shared_query_text_v2"
                + " (agent_id, trace_id, index_, truncated_text, truncated_end_text,"
                + " full_text_sha1) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertMainThreadProfileV2 = session.prepare("insert into trace_main_thread_profile_v2"
                + " (agent_id, trace_id, profile) values (?, ?, ?) using ttl ?");

        insertAuxThreadProfileV2 = session.prepare("insert into trace_aux_thread_profile_v2"
                + " (agent_id, trace_id, profile) values (?, ?, ?) using ttl ?");

        readOverallSlowCount = session.prepare("select count(*) from trace_tt_slow_count where"
                + " agent_rollup = ? and transaction_type = ? and capture_time > ? and capture_time"
                + " <= ?");

        if (cassandra2x) {
            // Cassandra 2.x doesn't support predicates on non-primary-key columns
            readOverallSlowCountPartial = session.prepare("select count(*) from"
                    + " trace_tt_slow_count_partial where agent_rollup = ? and transaction_type = ?"
                    + " and capture_time > ? and capture_time <= ?");
        } else {
            readOverallSlowCountPartial = session.prepare("select count(*) from"
                    + " trace_tt_slow_count_partial where agent_rollup = ? and transaction_type = ?"
                    + " and capture_time > ? and capture_time <= ? and real_capture_time > ? and"
                    + " real_capture_time <= ? allow filtering");
        }

        readTransactionSlowCount = session.prepare("select count(*) from trace_tn_slow_count where"
                + " agent_rollup = ? and transaction_type = ? and transaction_name = ? and"
                + " capture_time > ? and capture_time <= ?");

        if (cassandra2x) {
            // Cassandra 2.x doesn't support predicates on non-primary-key columns
            readTransactionSlowCountPartial = session.prepare("select count(*) from"
                    + " trace_tn_slow_count_partial where agent_rollup = ? and transaction_type = ?"
                    + " and transaction_name = ? and capture_time > ? and capture_time <= ?");
        } else {
            readTransactionSlowCountPartial = session.prepare("select count(*) from"
                    + " trace_tn_slow_count_partial where agent_rollup = ? and transaction_type = ?"
                    + " and transaction_name = ? and capture_time > ? and capture_time <= ? and"
                    + " real_capture_time > ? and real_capture_time <= ? allow filtering");
        }

        readOverallSlowPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, error, headline, user, attributes from trace_tt_slow_point"
                + " where agent_rollup = ? and transaction_type = ? and capture_time > ? and"
                + " capture_time <= ?");

        if (cassandra2x) {
            // Cassandra 2.x doesn't support predicates on non-primary-key columns
            readOverallSlowPointPartial = session.prepare("select agent_id, trace_id, capture_time,"
                    + " real_capture_time, duration_nanos, error, headline, user, attributes from"
                    + " trace_tt_slow_point_partial where agent_rollup = ? and transaction_type = ?"
                    + " and capture_time > ? and capture_time <= ?");
        } else {
            readOverallSlowPointPartial = session.prepare("select agent_id, trace_id, capture_time,"
                    + " real_capture_time, duration_nanos, error, headline, user, attributes from"
                    + " trace_tt_slow_point_partial where agent_rollup = ? and transaction_type = ?"
                    + " and capture_time > ? and capture_time <= ? and real_capture_time > ? and"
                    + " real_capture_time <= ? allow filtering");
        }

        readTransactionSlowPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, error, headline, user, attributes from trace_tn_slow_point"
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ? and"
                + " capture_time > ? and capture_time <= ?");

        if (cassandra2x) {
            // Cassandra 2.x doesn't support predicates on non-primary-key columns
            readTransactionSlowPointPartial = session.prepare("select agent_id, trace_id,"
                    + " capture_time, real_capture_time, duration_nanos, error, headline, user,"
                    + " attributes from trace_tn_slow_point_partial where agent_rollup = ? and"
                    + " transaction_type = ? and transaction_name = ? and capture_time > ? and"
                    + " capture_time <= ?");
        } else {
            readTransactionSlowPointPartial = session.prepare("select agent_id, trace_id,"
                    + " capture_time, real_capture_time, duration_nanos, error, headline, user,"
                    + " attributes from trace_tn_slow_point_partial where agent_rollup = ? and"
                    + " transaction_type = ? and transaction_name = ? and capture_time > ? and"
                    + " capture_time <= ? and real_capture_time > ? and real_capture_time <= ?"
                    + " allow filtering");
        }

        readOverallErrorCount = session.prepare("select count(*) from trace_tt_error_count where"
                + " agent_rollup = ? and transaction_type = ? and capture_time > ? and"
                + " capture_time <= ?");

        readTransactionErrorCount = session.prepare("select count(*) from trace_tn_error_count"
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?");

        readOverallErrorPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, error_message, headline, user, attributes from"
                + " trace_tt_error_point where agent_rollup = ? and transaction_type = ? and"
                + " capture_time > ? and capture_time <= ?");

        readTransactionErrorPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, error_message, headline, user, attributes from"
                + " trace_tn_error_point where agent_rollup = ? and transaction_type = ? and"
                + " transaction_name = ? and capture_time > ? and capture_time <= ?");

        readOverallErrorMessage = session.prepare("select capture_time, error_message from"
                + " trace_tt_error_message where agent_rollup = ? and transaction_type = ? and"
                + " capture_time > ? and capture_time <= ?");

        readTransactionErrorMessage = session.prepare("select capture_time, error_message from"
                + " trace_tn_error_message where agent_rollup = ? and transaction_type = ? and"
                + " transaction_name = ? and capture_time > ? and capture_time <= ?");

        readHeaderV1 = session
                .prepare("select header from trace_header where agent_id = ? and trace_id = ?");

        readEntriesV1 = session.prepare("select depth, start_offset_nanos, duration_nanos, active,"
                + " message, shared_query_text_index, query_message_prefix, query_message_suffix,"
                + " detail, location_stack_trace, error from trace_entry where agent_id = ? and"
                + " trace_id = ?");

        readSharedQueryTextsV1 = session.prepare("select truncated_text, truncated_end_text,"
                + " full_text_sha1 from trace_shared_query_text where agent_id = ? and trace_id"
                + " = ?");

        readMainThreadProfileV1 = session.prepare("select profile from trace_main_thread_profile"
                + " where agent_id = ? and trace_id = ?");

        readAuxThreadProfileV1 = session.prepare("select profile from trace_aux_thread_profile"
                + " where agent_id = ? and trace_id = ?");

        readHeaderV2 = session
                .prepare("select header from trace_header_v2 where agent_id = ? and trace_id = ?");

        readEntriesV2 = session.prepare("select depth, start_offset_nanos, duration_nanos, active,"
                + " message, shared_query_text_index, query_message_prefix, query_message_suffix,"
                + " detail, location_stack_trace, error from trace_entry_v2 where agent_id = ? and"
                + " trace_id = ?");

        readQueriesV2 = session.prepare("select type, shared_query_text_index,"
                + " total_duration_nanos, execution_count, total_rows, active from trace_query_v2"
                + " where agent_id = ? and trace_id = ?");

        readSharedQueryTextsV2 = session.prepare("select truncated_text, truncated_end_text,"
                + " full_text_sha1 from trace_shared_query_text_v2 where agent_id = ? and trace_id"
                + " = ?");

        readMainThreadProfileV2 = session.prepare("select profile from trace_main_thread_profile_v2"
                + " where agent_id = ? and trace_id = ?");

        readAuxThreadProfileV2 = session.prepare("select profile from trace_aux_thread_profile_v2"
                + " where agent_id = ? and trace_id = ?");

        deleteOverallSlowCountPartial = session.prepare("delete from trace_tt_slow_count_partial"
                + " where agent_rollup = ? and transaction_type = ? and capture_time = ? and"
                + " agent_id = ? and trace_id = ?");

        deleteTransactionSlowCountPartial = session.prepare("delete from"
                + " trace_tn_slow_count_partial where agent_rollup = ? and transaction_type = ? and"
                + " transaction_name = ? and capture_time = ? and agent_id = ? and trace_id = ?");

        deleteOverallSlowPointPartial = session.prepare("delete from trace_tt_slow_point_partial"
                + " where agent_rollup = ? and transaction_type = ? and capture_time = ? and"
                + " agent_id = ? and trace_id = ?");

        deleteTransactionSlowPointPartial = session.prepare("delete from"
                + " trace_tn_slow_point_partial where agent_rollup = ? and transaction_type = ? and"
                + " transaction_name = ? and capture_time = ? and agent_id = ? and trace_id = ?");
    }

    @CheckReturnValue
    @Override
    public CompletionStage<?> store(String agentId, Trace trace) {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
        return store(agentId, agentRollupIds, agentRollupIds, trace);
    }

    @CheckReturnValue
    public CompletionStage<?> store(String agentId, List<String> agentRollupIds,
                                      List<String> agentRollupIdsForMeta, Trace trace) {
        CassandraWriteMetrics cassandraWriteMetrics = session.getCassandraWriteMetrics();
        cassandraWriteMetrics.setCurrTransactionType(trace.getHeader().getTransactionType());
        cassandraWriteMetrics.setCurrTransactionName(trace.getHeader().getTransactionName());
        cassandraWriteMetrics.setPartialTrace(trace.getHeader().getPartial());
        return storeInternal(agentId, agentRollupIds, agentRollupIdsForMeta, trace)
                .whenComplete((results, throwable) -> {
                    cassandraWriteMetrics.setCurrTransactionType(null);
                    cassandraWriteMetrics.setCurrTransactionName(null);
                    cassandraWriteMetrics.setPartialTrace(false);
                });
    }

    @CheckReturnValue
    private CompletionStage<?> storeInternal(String agentId, List<String> agentRollupIds,
                                               List<String> agentRollupIdsForMeta, Trace trace) {
        String traceId = trace.getId();
        return readHeader(agentId, traceId).thenCompose(readPriorHeader -> {
            Trace.Header priorHeader = trace.getUpdate() ? readPriorHeader : null;
            Trace.Header headerTmp = trace.getHeader();
            if (headerTmp.getPartial()) {
                headerTmp = headerTmp.toBuilder()
                        .setCaptureTimePartialRollup(
                                getCaptureTimePartialRollup(headerTmp.getCaptureTime()))
                        .build();
            }
            final Trace.Header header = headerTmp;
            List<CompletionStage<?>> completableFutures = new ArrayList<>();

            List<Trace.SharedQueryText> sharedQueryTexts = new ArrayList<>();
            for (Trace.SharedQueryText sharedQueryText : trace.getSharedQueryTextList()) {
                String fullTextSha1 = sharedQueryText.getFullTextSha1();
                if (fullTextSha1.isEmpty()) {
                    String fullText = sharedQueryText.getFullText();
                    if (fullText.length() > 2 * Constants.TRACE_QUERY_TEXT_TRUNCATE) {
                        // relying on agent side to rate limit (re-)sending the same full text
                        fullTextSha1 = SHA_1.hashString(fullText, UTF_8).toString();
                        completableFutures.add(fullQueryTextDao.store(agentRollupIds, fullTextSha1, fullText));
                        sharedQueryText = Trace.SharedQueryText.newBuilder()
                                .setTruncatedText(
                                        fullText.substring(0, Constants.TRACE_QUERY_TEXT_TRUNCATE))
                                .setTruncatedEndText(fullText.substring(
                                        fullText.length() - Constants.TRACE_QUERY_TEXT_TRUNCATE,
                                        fullText.length()))
                                .setFullTextSha1(fullTextSha1)
                                .build();
                    }
                }
                sharedQueryTexts.add(sharedQueryText);
            }

            // wait for success before proceeding in order to ensure cannot end up with orphaned
            // fullTextSha1
            return CompletableFutures.allAsList(completableFutures)
                    .thenCompose((ignored) -> configRepository.getCentralStorageConfig())
                    .thenCompose(centralStorageConfig -> {
                        List<CompletionStage<?>> futures = new ArrayList<>();

                        int adjustedTTL =
                                Common.getAdjustedTTL(centralStorageConfig.getTraceTTL(),
                                        header.getCaptureTime(), clock);
                        for (String agentRollupId : agentRollupIds) {
                            if (header.getSlow()) {
                                BoundStatement boundStatement;
                                if (header.getPartial()) {
                                    boundStatement = insertOverallSlowPointPartial.bind();
                                } else {
                                    boundStatement = insertOverallSlowPoint.bind();
                                }
                                boundStatement = bindSlowPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        true, header.getPartial(), cassandra2x);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                if (header.getPartial()) {
                                    boundStatement = insertTransactionSlowPointPartial.bind();
                                } else {
                                    boundStatement = insertTransactionSlowPoint.bind();
                                }
                                boundStatement = bindSlowPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        false, header.getPartial(), cassandra2x);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                if (header.getPartial()) {
                                    boundStatement = insertOverallSlowCountPartial.bind();
                                } else {
                                    boundStatement = insertOverallSlowCount.bind();
                                }
                                boundStatement = bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        true, header.getPartial(), cassandra2x);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                if (header.getPartial()) {
                                    boundStatement = insertTransactionSlowCountPartial.bind();
                                } else {
                                    boundStatement = insertTransactionSlowCount.bind();
                                }
                                boundStatement = bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        false, header.getPartial(), cassandra2x);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                if (priorHeader != null && priorHeader.getCaptureTimePartialRollup() != header
                                        .getCaptureTimePartialRollup()) {

                                    boolean useCaptureTimePartialRollup =
                                            priorHeader.getCaptureTimePartialRollup() != 0;

                                    boundStatement = deleteOverallSlowPointPartial.bind();
                                    boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, true,
                                            useCaptureTimePartialRollup, new AtomicInteger(0));
                                    futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                    boundStatement = deleteTransactionSlowPointPartial.bind();
                                    boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, false,
                                            useCaptureTimePartialRollup, new AtomicInteger(0));
                                    futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                    boundStatement = deleteOverallSlowCountPartial.bind();
                                    boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, true,
                                            useCaptureTimePartialRollup, new AtomicInteger(0));
                                    futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                    boundStatement = deleteTransactionSlowCountPartial.bind();
                                    boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, false,
                                            useCaptureTimePartialRollup, new AtomicInteger(0));
                                    futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                                }
                            }
                            // seems unnecessary to insert error info for partial traces
                            // and this avoids having to clean up partial trace data when trace is complete
                            if (header.hasError() && !header.getPartial()) {
                                BoundStatement boundStatement = insertOverallErrorMessage.bind();
                                boundStatement = bindErrorMessage(boundStatement, agentRollupId, agentId, traceId, header,
                                        adjustedTTL, true);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                boundStatement = insertTransactionErrorMessage.bind();
                                boundStatement = bindErrorMessage(boundStatement, agentRollupId, agentId, traceId, header,
                                        adjustedTTL, false);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                boundStatement = insertOverallErrorPoint.bind();
                                boundStatement = bindErrorPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        true);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                boundStatement = insertTransactionErrorPoint.bind();
                                boundStatement = bindErrorPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        false);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                boundStatement = insertOverallErrorCount.bind();
                                boundStatement = bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        true, false, cassandra2x);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                                boundStatement = insertTransactionErrorCount.bind();
                                boundStatement = bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                                        false, false, cassandra2x);
                                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                            }
                        }
                        for (String agentRollupIdForMeta : agentRollupIdsForMeta) {
                            for (Trace.Attribute attributeName : header.getAttributeList()) {
                                futures.add(traceAttributeNameDao.store(agentRollupIdForMeta,
                                        header.getTransactionType(), attributeName.getName()));
                            }
                        }

                        int i = 0;
                        BoundStatement boundStatement = insertHeaderV2.bind()
                                .setString(i++, agentId)
                                .setString(i++, traceId)
                                .setByteBuffer(i++, ByteBuffer.wrap(header.toByteArray()))
                                .setInt(i++, adjustedTTL);
                        futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));

                        int index = 0;
                        for (Trace.Entry entry : trace.getEntryList()) {
                            i = 0;
                            boundStatement = insertEntryV2.bind()
                                    .setString(i++, agentId)
                                    .setString(i++, traceId)
                                    .setInt(i++, index++)
                                    .setInt(i++, entry.getDepth())
                                    .setLong(i++, entry.getStartOffsetNanos())
                                    .setLong(i++, entry.getDurationNanos())
                                    .setBoolean(i++, entry.getActive());
                            if (entry.hasQueryEntryMessage()) {
                                boundStatement = boundStatement.setToNull(i++)
                                        .setInt(i++, entry.getQueryEntryMessage().getSharedQueryTextIndex())
                                        .setString(i++,
                                                Strings.emptyToNull(entry.getQueryEntryMessage().getPrefix()))
                                        .setString(i++,
                                                Strings.emptyToNull(entry.getQueryEntryMessage().getSuffix()));
                            } else {
                                // message is empty for trace entries added using addErrorEntry()
                                boundStatement = boundStatement.setString(i++, Strings.emptyToNull(entry.getMessage()))
                                        .setToNull(i++)
                                        .setToNull(i++)
                                        .setToNull(i++);
                            }
                            List<Trace.DetailEntry> detailEntries = entry.getDetailEntryList();
                            if (detailEntries.isEmpty()) {
                                boundStatement = boundStatement.setToNull(i++);
                            } else {
                                boundStatement = boundStatement.setByteBuffer(i++, Messages.toByteBuffer(detailEntries));
                            }
                            List<StackTraceElement> location = entry.getLocationStackTraceElementList();
                            if (location.isEmpty()) {
                                boundStatement = boundStatement.setToNull(i++);
                            } else {
                                boundStatement = boundStatement.setByteBuffer(i++, Messages.toByteBuffer(location));
                            }
                            if (entry.hasError()) {
                                boundStatement = boundStatement.setByteBuffer(i++, ByteBuffer.wrap(entry.getError().toByteArray()));
                            } else {
                                boundStatement = boundStatement.setToNull(i++);
                            }
                            boundStatement = boundStatement.setInt(i++, adjustedTTL);
                            futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                        }

                        for (Aggregate.Query query : trace.getQueryList()) {
                            i = 0;
                            boundStatement = insertQueryV2.bind()
                                    .setString(i++, agentId)
                                    .setString(i++, traceId)
                                    .setString(i++, query.getType())
                                    .setInt(i++, query.getSharedQueryTextIndex())
                                    .setDouble(i++, query.getTotalDurationNanos())
                                    .setLong(i++, query.getExecutionCount());
                            if (query.hasTotalRows()) {
                                boundStatement = boundStatement.setLong(i++, query.getTotalRows().getValue());
                            } else {
                                boundStatement = boundStatement.setLong(i++, NotAvailableAware.NA);
                            }
                            boundStatement = boundStatement.setBoolean(i++, query.getActive())
                                    .setInt(i++, adjustedTTL);
                            futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                        }

                        index = 0;
                        for (Trace.SharedQueryText sharedQueryText : sharedQueryTexts) {
                            i = 0;
                            boundStatement = insertSharedQueryTextV2.bind()
                                    .setString(i++, agentId)
                                    .setString(i++, traceId)
                                    .setInt(i++, index++);
                            String fullText = sharedQueryText.getFullText();
                            if (fullText.isEmpty()) {
                                boundStatement = boundStatement.setString(i++, sharedQueryText.getTruncatedText())
                                        .setString(i++, sharedQueryText.getTruncatedEndText())
                                        .setString(i++, sharedQueryText.getFullTextSha1());
                            } else {
                                boundStatement = boundStatement.setString(i++, fullText)
                                        .setToNull(i++)
                                        .setToNull(i++);
                            }
                            boundStatement = boundStatement.setInt(i++, adjustedTTL);
                            futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                        }

                        if (trace.hasMainThreadProfile()) {
                            boundStatement = insertMainThreadProfileV2.bind();
                            boundStatement = bindThreadProfile(boundStatement, agentId, traceId, trace.getMainThreadProfile(),
                                    adjustedTTL);
                            futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                        }

                        if (trace.hasAuxThreadProfile()) {
                            boundStatement = insertAuxThreadProfileV2.bind();
                            boundStatement = bindThreadProfile(boundStatement, agentId, traceId, trace.getAuxThreadProfile(),
                                    adjustedTTL);
                            futures.add(session.writeAsync(boundStatement, CassandraProfile.collector));
                        }
                        futures.add(
                                transactionTypeDao.store(agentRollupIdsForMeta, header.getTransactionType()));
                        return CompletableFutures.allAsList(futures);
                    });

        });
    }

    @Override
    public CompletionStage<Long> readSlowCount(String agentRollupId, TraceQuery query) {
        BoundStatement boundStatement;
        BoundStatement boundStatementPartial;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallSlowCount.bind();
            boundStatementPartial = readOverallSlowCountPartial.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, true);
            boundStatementPartial = bindTraceQueryPartial(boundStatementPartial, agentRollupId, query, true, cassandra2x);
        } else {
            boundStatement = readTransactionSlowCount.bind();
            boundStatementPartial = readTransactionSlowCountPartial.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, false);
            boundStatementPartial = bindTraceQueryPartial(boundStatementPartial, agentRollupId, query, false, cassandra2x);
        }
        CompletionStage<AsyncResultSet> future = session.readAsync(boundStatement, CassandraProfile.web).toCompletableFuture();
        CompletionStage<AsyncResultSet> futurePartial = session.readAsync(boundStatementPartial, CassandraProfile.web).toCompletableFuture();

        return future.thenCombine(futurePartial, (futureResult, futurePartialResult) -> {
            return futureResult.one().getLong(0) + futurePartialResult.one().getLong(0);
        });
    }

    @Override
    public CompletionStage<Result<TracePoint>> readSlowPoints(String agentRollupId, TraceQuery query,
                                                              TracePointFilter filter, int limit) {
        BoundStatement boundStatement;
        BoundStatement boundStatementPartial;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallSlowPoint.bind();
            boundStatementPartial = readOverallSlowPointPartial.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, true);
            boundStatementPartial = bindTraceQueryPartial(boundStatementPartial, agentRollupId, query, true, cassandra2x);
        } else {
            boundStatement = readTransactionSlowPoint.bind();
            boundStatementPartial = readTransactionSlowPointPartial.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, false);
            boundStatementPartial = bindTraceQueryPartial(boundStatementPartial, agentRollupId, query, false, cassandra2x);
        }

        CompletionStage<List<TracePoint>> completedPointsCS = session.readAsync(boundStatement, CassandraProfile.web)
                .thenCompose(future -> processPoints(future, filter, false, false));
        CompletionStage<List<TracePoint>> partialPointsCS = session.readAsync(boundStatementPartial, CassandraProfile.web)
                .thenCompose(futurePartial -> processPoints(futurePartial, filter, true, false));

        return completedPointsCS.thenCombine(partialPointsCS,
                (completedPoints, partialPoints) -> combine(completedPoints, partialPoints, limit));
    }

    @Override
    public CompletionStage<Long> readErrorCount(String agentRollupId, TraceQuery query) {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorCount.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorCount.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        return session.readAsync(boundStatement, CassandraProfile.web)
                .thenApply(results -> results.one().getLong(0));
    }

    @Override
    public CompletionStage<Result<TracePoint>> readErrorPoints(String agentRollupId, TraceQuery query,
                                                               TracePointFilter filter, int limit) {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorPoint.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorPoint.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        return session.readAsync(boundStatement, CassandraProfile.web)
                .thenCompose(results -> processPoints(results, filter, false, true))
                .thenApply(errorPoints -> createResult(errorPoints, limit));
    }

    @Override
    public CompletionStage<ErrorMessageResult> readErrorMessages(String agentRollupId, TraceQuery query,
                                                ErrorMessageFilter filter, long resolutionMillis, int limit) {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorMessage.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorMessage.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        // rows are already in order by captureTime, so saving sort step by using linked hash map
        Map<Long, MutableLong> pointCounts = new LinkedHashMap<>();
        Map<String, MutableLong> messageCounts = new HashMap<>();
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new com.google.common.base.Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    long captureTime = checkNotNull(row.getInstant(0)).toEpochMilli();
                    String errorMessage = checkNotNull(row.getString(1));
                    if (!matches(filter, errorMessage)) {
                        continue;
                    }
                    long rollupCaptureTime = CaptureTimes.getRollup(captureTime, resolutionMillis);
                    pointCounts.computeIfAbsent(rollupCaptureTime, k -> new MutableLong()).increment();
                    messageCounts.computeIfAbsent(errorMessage, k -> new MutableLong()).increment();
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return session.readAsync(boundStatement, CassandraProfile.web).thenCompose(compute).thenApply((ignored) -> {
            // pointCounts is linked hash map and is already sorted by capture time
            List<ErrorMessagePoint> points = pointCounts.entrySet().stream()
                    .map(e -> ImmutableErrorMessagePoint.of(e.getKey(), e.getValue().value))
                    // explicit type on this line is needed for Checker Framework
                    // see https://github.com/typetools/checker-framework/issues/531
                    .collect(Collectors.<ErrorMessagePoint>toList());
            List<ErrorMessageCount> counts = messageCounts.entrySet().stream()
                    .map(e1 -> ImmutableErrorMessageCount.of(e1.getKey(), e1.getValue().value))
                    .sorted(Comparator.comparing(ErrorMessageCount::count).reversed())
                    // explicit type on this line is needed for Checker Framework
                    // see https://github.com/typetools/checker-framework/issues/531
                    .collect(Collectors.<ErrorMessageCount>toList());

            if (counts.size() > limit) {
                return ImmutableErrorMessageResult.builder()
                        .addAllPoints(points)
                        .counts(new Result<>(counts.subList(0, limit), true))
                        .build();
            } else {
                return ImmutableErrorMessageResult.builder()
                        .addAllPoints(points)
                        .counts(new Result<>(counts, false))
                        .build();
            }
        });
    }

    @Override
    public CompletionStage<Long> readErrorMessageCount(String agentRollupId, TraceQuery query,
                                                       String errorMessageFilter, CassandraProfile profile) {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorMessage.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorMessage.bind();
            boundStatement = bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        Pattern errorMessagePattern;
        if (errorMessageFilter.startsWith("/") && errorMessageFilter.endsWith("/")) {
            // case insensitive search must be explicit via (?i) at beginning of pattern
            errorMessagePattern = Pattern.compile(
                    errorMessageFilter.substring(1, errorMessageFilter.length() - 1),
                    Pattern.DOTALL);
        } else {
            errorMessagePattern = null;
        }
        AtomicLong count = new AtomicLong(0);
        Function<AsyncResultSet, CompletableFuture<Long>> compute = new com.google.common.base.Function<AsyncResultSet, CompletableFuture<Long>>() {
            @Override
            public CompletableFuture<Long> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    String errorMessage = checkNotNull(row.getString(1));
                    if (errorMessagePattern == null && errorMessage.contains(errorMessageFilter)
                            || errorMessagePattern != null
                            && errorMessagePattern.matcher(errorMessage).find()) {
                        count.incrementAndGet();
                    }
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(count.get());
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    @Override
    public CompletionStage<HeaderPlus> readHeaderPlus(String agentId, String traceId) {
        return readHeader(agentId, traceId).thenApply(header -> {
            if (header == null) {
                return null;
            }
            Existence entriesExistence = header.getEntryCount() == 0 ? Existence.NO : Existence.YES;
            Existence queriesExistence = header.getQueryCount() == 0 ? Existence.NO : Existence.YES;
            Existence profileExistence = header.getMainThreadProfileSampleCount() == 0
                    && header.getAuxThreadProfileSampleCount() == 0 ? Existence.NO : Existence.YES;
            return ImmutableHeaderPlus.builder()
                    .header(header)
                    .entriesExistence(entriesExistence)
                    .queriesExistence(queriesExistence)
                    .profileExistence(profileExistence)
                    .build();
        });
    }

    @Override
    public CompletionStage<Entries> readEntries(String agentId, String traceId, CassandraProfile profile) {
        return readEntriesInternal(agentId, traceId, profile).thenCombine(readSharedQueryTexts(agentId, traceId, profile),
                (entries, sharedQueryTexts) -> {
                    return ImmutableEntries.builder()
                            .addAllEntries(entries)
                            .addAllSharedQueryTexts(sharedQueryTexts)
                            .build();
                });

    }

    @Override
    public CompletionStage<Queries> readQueries(String agentId, String traceId, CassandraProfile profile) {
        return readQueriesInternal(agentId, traceId, profile).thenCombine(readSharedQueryTexts(agentId, traceId, profile),
                (queries, sharedQueryTexts) -> {
                    return ImmutableQueries.builder()
                            .addAllQueries(queries)
                            .addAllSharedQueryTexts(sharedQueryTexts)
                            .build();
                });
    }

    // since this is only used by export, SharedQueryTexts are always returned with fullTrace
    // (never with truncatedText/truncatedEndText/fullTraceSha1)
    @Override
    public CompletionStage<EntriesAndQueries> readEntriesAndQueriesForExport(String agentId, String traceId, CassandraProfile profile) {

        return readEntriesInternal(agentId, traceId, profile).thenCombine(readQueriesInternal(agentId, traceId, profile),
                        (entries, queries) -> {
                            return ImmutableEntriesAndQueries.builder()
                                    .addAllEntries(entries)
                                    .addAllQueries(queries);
                        })
                .thenCompose(entries -> {
                    return readSharedQueryTexts(agentId, traceId, profile).thenCompose(sht -> {
                        List<Trace.SharedQueryText> sharedQueryTexts = new ArrayList<>();
                        List<Future<?>> futures = new ArrayList<>();
                        for (Trace.SharedQueryText sharedQueryText : sht) {
                            String fullTextSha1 = sharedQueryText.getFullTextSha1();
                            if (fullTextSha1.isEmpty()) {
                                sharedQueryTexts.add(sharedQueryText);
                            } else {
                                futures.add(fullQueryTextDao.getFullText(agentId, fullTextSha1, profile).thenAccept(fullText -> {
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
                                }).toCompletableFuture());
                            }
                        }
                        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(ignored -> {
                            return entries.addAllSharedQueryTexts(sharedQueryTexts)
                                    .build();
                        });
                    });
                });

    }

    @Override
    public CompletionStage<Profile> readMainThreadProfile(String agentId, String traceId) {
        return readMainThreadProfileUsingPS(agentId, traceId, readMainThreadProfileV2).thenCompose(profile -> {
            if (profile != null) {
                return CompletableFuture.completedFuture(profile);
            }
            return readMainThreadProfileUsingPS(agentId, traceId, readMainThreadProfileV1);
        });
    }

    public CompletionStage<Profile> readMainThreadProfileUsingPS(String agentId, String traceId,
                                                                 PreparedStatement readPS) {
        BoundStatement boundStatement = readPS.bind()
                .setString(0, agentId)
                .setString(1, traceId);
        return session.readAsync(boundStatement, CassandraProfile.web).thenApply(results -> {
            Row row = results.one();
            if (row == null) {
                return null;
            }
            try {
                return Profile.parseFrom(checkNotNull(row.getByteBuffer(0)));
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<Profile> readAuxThreadProfile(String agentId, String traceId) {
        return readAuxThreadProfileUsingPS(agentId, traceId, readAuxThreadProfileV2).thenCompose(profile -> {
            if (profile != null) {
                return CompletableFuture.completedFuture(profile);
            }
            return readAuxThreadProfileUsingPS(agentId, traceId, readAuxThreadProfileV1);
        });
    }

    public CompletionStage<Profile> readAuxThreadProfileUsingPS(String agentId, String traceId,
                                                                PreparedStatement readPS) {
        BoundStatement boundStatement = readPS.bind()
                .setString(0, agentId)
                .setString(1, traceId);
        return session.readAsync(boundStatement, CassandraProfile.web).thenApply(results -> {
            Row row = results.one();
            if (row == null) {
                return null;
            }
            try {
                return Profile.parseFrom(checkNotNull(row.getByteBuffer(0)));
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private CompletionStage<Trace.Header> readHeader(String agentId, String traceId) {
        return readHeaderUsingPS(agentId, traceId, readHeaderV2).thenCompose(header -> {
            if (header != null) {
                return CompletableFuture.completedFuture(header);
            }
            return readHeaderUsingPS(agentId, traceId, readHeaderV1);
        });
    }

    private CompletionStage<Trace.Header> readHeaderUsingPS(String agentId, String traceId,
                                                            PreparedStatement readPS) {
        BoundStatement boundStatement = readPS.bind()
                .setString(0, agentId)
                .setString(1, traceId);
        return session.readAsync(boundStatement, CassandraProfile.web).thenApply(results -> {
            Row row = results.one();
            if (row == null) {
                return null;
            }
            try {
                return Trace.Header.parseFrom(checkNotNull(row.getByteBuffer(0)));
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private CompletionStage<List<Trace.Entry>> readEntriesInternal(String agentId, String traceId, CassandraProfile profile) {
        return readEntriesUsingPS(agentId, traceId, readEntriesV2, profile).thenCompose(entries -> {
            if (!entries.isEmpty()) {
                return CompletableFuture.completedFuture(entries);
            }
            return readEntriesUsingPS(agentId, traceId, readEntriesV1, profile);
        });
    }

    private CompletionStage<List<Trace.Entry>> readEntriesUsingPS(String agentId, String traceId,
                                                                  PreparedStatement readPS, CassandraProfile profile) {
        BoundStatement boundStatement = readPS.bind()
                .setString(0, agentId)
                .setString(1, traceId);
        List<Trace.Entry> entries = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<Trace.Entry>>> compute = new com.google.common.base.Function<AsyncResultSet, CompletableFuture<List<Trace.Entry>>>() {
            @Override
            public CompletableFuture<List<Trace.Entry>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    Trace.Entry.Builder entry = Trace.Entry.newBuilder()
                            .setDepth(row.getInt(i++))
                            .setStartOffsetNanos(row.getLong(i++))
                            .setDurationNanos(row.getLong(i++))
                            .setActive(row.getBoolean(i++));
                    if (row.isNull(i + 1)) { // shared_query_text_index
                        // message is null for trace entries added using addErrorEntry()
                        entry.setMessage(Strings.nullToEmpty(row.getString(i++)));
                        i++; // shared_query_text_index
                        i++; // query_message_prefix
                        i++; // query_message_suffix
                    } else {
                        i++; // message
                        Trace.QueryEntryMessage queryEntryMessage = Trace.QueryEntryMessage.newBuilder()
                                .setSharedQueryTextIndex(row.getInt(i++))
                                .setPrefix(Strings.nullToEmpty(row.getString(i++)))
                                .setSuffix(Strings.nullToEmpty(row.getString(i++)))
                                .build();
                        entry.setQueryEntryMessage(queryEntryMessage);
                    }
                    ByteBuffer detailBytes = row.getByteBuffer(i++);
                    if (detailBytes != null) {
                        entry.addAllDetailEntry(
                                Messages.parseDelimitedFrom(detailBytes, Trace.DetailEntry.parser()));
                    }
                    ByteBuffer locationBytes = row.getByteBuffer(i++);
                    if (locationBytes != null) {
                        entry.addAllLocationStackTraceElement(Messages.parseDelimitedFrom(locationBytes,
                                Proto.StackTraceElement.parser()));
                    }
                    ByteBuffer errorBytes = row.getByteBuffer(i++);
                    if (errorBytes != null) {
                        try {
                            entry.setError(Trace.Error.parseFrom(errorBytes));
                        } catch (InvalidProtocolBufferException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    entries.add(entry.build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(entries);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    private CompletionStage<List<Aggregate.Query>> readQueriesInternal(String agentId, String traceId, CassandraProfile profile) {
        BoundStatement boundStatement = readQueriesV2.bind()
                .setString(0, agentId)
                .setString(1, traceId);
        List<Aggregate.Query> queries = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<Aggregate.Query>>> compute = new com.google.common.base.Function<AsyncResultSet, CompletableFuture<List<Aggregate.Query>>>() {
            @Override
            public CompletableFuture<List<Aggregate.Query>> apply(AsyncResultSet results) {

                for (Row row : results.currentPage()) {
                    int i = 0;
                    Aggregate.Query.Builder query = Aggregate.Query.newBuilder()
                            .setType(checkNotNull(row.getString(i++)))
                            .setSharedQueryTextIndex(row.getInt(i++))
                            .setTotalDurationNanos(row.getDouble(i++))
                            .setExecutionCount(row.getLong(i++));
                    long totalRows = row.getLong(i++);
                    if (!NotAvailableAware.isNA(totalRows)) {
                        query.setTotalRows(OptionalInt64.newBuilder().setValue(totalRows));
                    }
                    query.setActive(row.getBoolean(i++));
                    queries.add(query.build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(queries);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    private CompletionStage<List<Trace.SharedQueryText>> readSharedQueryTexts(String agentId, String traceId, CassandraProfile profile) {
        return readSharedQueryTextsUsingPS(agentId, traceId, readSharedQueryTextsV2, profile).thenCompose(sharedQueryTexts -> {
            if (!sharedQueryTexts.isEmpty()) {
                return CompletableFuture.completedFuture(sharedQueryTexts);
            }
            return readSharedQueryTextsUsingPS(agentId, traceId, readSharedQueryTextsV1, profile);
        });
    }

    private CompletionStage<List<Trace.SharedQueryText>> readSharedQueryTextsUsingPS(String agentId, String traceId,
                                                                                     PreparedStatement readPS, CassandraProfile profile) {
        BoundStatement boundStatement = readPS.bind()
                .setString(0, agentId)
                .setString(1, traceId);
        List<Trace.SharedQueryText> sharedQueryTexts = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<Trace.SharedQueryText>>> compute = new com.google.common.base.Function<AsyncResultSet, CompletableFuture<List<Trace.SharedQueryText>>>() {
            @Override
            public CompletableFuture<List<Trace.SharedQueryText>> apply(AsyncResultSet results) {

                for (Row row : results.currentPage()) {
                    int i = 0;
                    String truncatedText = checkNotNull(row.getString(i++));
                    String truncatedEndText = row.getString(i++);
                    String fullTextSha1 = row.getString(i++);
                    Trace.SharedQueryText.Builder sharedQueryText = Trace.SharedQueryText.newBuilder();
                    if (fullTextSha1 == null) {
                        sharedQueryText.setFullText(truncatedText);
                    } else {
                        sharedQueryText.setFullTextSha1(fullTextSha1)
                                .setTruncatedText(truncatedText)
                                .setTruncatedEndText(checkNotNull(truncatedEndText));
                    }
                    sharedQueryTexts.add(sharedQueryText.build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(sharedQueryTexts);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        session.updateSchemaWithRetry("truncate table trace_tt_slow_count");
        session.updateSchemaWithRetry("truncate table trace_tn_slow_count");
        session.updateSchemaWithRetry("truncate table trace_tt_slow_count_partial");
        session.updateSchemaWithRetry("truncate table trace_tn_slow_count_partial");
        session.updateSchemaWithRetry("truncate table trace_tt_slow_point");
        session.updateSchemaWithRetry("truncate table trace_tn_slow_point");
        session.updateSchemaWithRetry("truncate table trace_tt_slow_point_partial");
        session.updateSchemaWithRetry("truncate table trace_tn_slow_point_partial");
        session.updateSchemaWithRetry("truncate table trace_tt_error_count");
        session.updateSchemaWithRetry("truncate table trace_tn_error_count");
        session.updateSchemaWithRetry("truncate table trace_tt_error_point");
        session.updateSchemaWithRetry("truncate table trace_tn_error_point");
        session.updateSchemaWithRetry("truncate table trace_tt_error_message");
        session.updateSchemaWithRetry("truncate table trace_tn_error_message");
        session.updateSchemaWithRetry("truncate table trace_header");
        session.updateSchemaWithRetry("truncate table trace_entry");
        session.updateSchemaWithRetry("truncate table trace_shared_query_text");
        session.updateSchemaWithRetry("truncate table trace_main_thread_profile");
        session.updateSchemaWithRetry("truncate table trace_aux_thread_profile");
        session.updateSchemaWithRetry("truncate table trace_header_v2");
        session.updateSchemaWithRetry("truncate table trace_entry_v2");
        session.updateSchemaWithRetry("truncate table trace_query_v2");
        session.updateSchemaWithRetry("truncate table trace_shared_query_text_v2");
        session.updateSchemaWithRetry("truncate table trace_main_thread_profile_v2");
        session.updateSchemaWithRetry("truncate table trace_aux_thread_profile_v2");
    }

    @CheckReturnValue
    private static BoundStatement bindSlowPoint(BoundStatement boundStatement, String agentRollupId,
                                                String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall,
                                                boolean partial, boolean cassandra2x) {
        AtomicInteger ind = new AtomicInteger(0);
        boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, header, overall,
                partial && !cassandra2x, ind);
        int i = ind.get();
        if (partial) {
            if (cassandra2x) {
                // don't set real_capture_time, so this still looks like data prior to 0.13.1
                boundStatement = boundStatement.setToNull(i++);
            } else {
                boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(header.getCaptureTime()));
            }
        }
        boundStatement = boundStatement.setLong(i++, header.getDurationNanos())
                .setBoolean(i++, header.hasError())
                .setString(i++, header.getHeadline())
                .setString(i++, Strings.emptyToNull(header.getUser()));
        List<Trace.Attribute> attributes = header.getAttributeList();
        if (attributes.isEmpty()) {
            boundStatement = boundStatement.setToNull(i++);
        } else {
            boundStatement = boundStatement.setByteBuffer(i++, Messages.toByteBuffer(attributes));
        }
        return boundStatement.setInt(i++, adjustedTTL);
    }

    @CheckReturnValue
    private static BoundStatement bindCount(BoundStatement boundStatement, String agentRollupId,
                                            String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall,
                                            boolean partial, boolean cassandra2x) {
        AtomicInteger ind = new AtomicInteger(0);
        boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, header, overall,
                partial && !cassandra2x, ind);
        int i = ind.get();
        if (partial) {
            if (cassandra2x) {
                // don't set real_capture_time, so this still looks like data prior to 0.13.1
                boundStatement = boundStatement.setToNull(i++);
            } else {
                boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(header.getCaptureTime()));
            }
        }
        return boundStatement.setInt(i++, adjustedTTL);
    }

    @CheckReturnValue
    private static BoundStatement bindErrorMessage(BoundStatement boundStatement, String agentRollupId,
                                                   String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall) {
        AtomicInteger ind = new AtomicInteger(0);
        boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, header, overall, false, ind);
        int i = ind.get();
        return boundStatement.setString(i++, header.getError().getMessage())
                .setInt(i++, adjustedTTL);
    }

    @CheckReturnValue
    private static BoundStatement bindErrorPoint(BoundStatement boundStatement, String agentRollupId,
                                                 String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall) {
        AtomicInteger ind = new AtomicInteger(0);
        boundStatement = bind(boundStatement, agentRollupId, agentId, traceId, header, overall, false, ind);
        int i = ind.get();
        boundStatement = boundStatement.setLong(i++, header.getDurationNanos())
                .setString(i++, header.getError().getMessage())
                .setString(i++, header.getHeadline())
                .setString(i++, Strings.emptyToNull(header.getUser()));
        List<Trace.Attribute> attributes = header.getAttributeList();
        if (attributes.isEmpty()) {
            boundStatement = boundStatement.setToNull(i++);
        } else {
            boundStatement = boundStatement.setByteBuffer(i++, Messages.toByteBuffer(attributes));
        }
        return boundStatement.setInt(i++, adjustedTTL);
    }

    @CheckReturnValue
    private static BoundStatement bind(BoundStatement boundStatement, String agentRollupId, String agentId,
                                       String traceId, Trace.Header header, boolean overall,
                                       boolean useCaptureTimePartialRollup, AtomicInteger i) {
        boundStatement = boundStatement.setString(i.getAndIncrement(), agentRollupId)
                .setString(i.getAndIncrement(), header.getTransactionType());
        if (!overall) {
            boundStatement = boundStatement.setString(i.getAndIncrement(), header.getTransactionName());
        }
        if (useCaptureTimePartialRollup) {
            boundStatement = boundStatement.setInstant(i.getAndIncrement(), Instant.ofEpochMilli(header.getCaptureTimePartialRollup()));
        } else {
            boundStatement = boundStatement.setInstant(i.getAndIncrement(), Instant.ofEpochMilli(header.getCaptureTime()));
        }
        return boundStatement.setString(i.getAndIncrement(), agentId)
                .setString(i.getAndIncrement(), traceId);
    }

    @CheckReturnValue
    private static BoundStatement bindThreadProfile(BoundStatement boundStatement, String agentId,
                                                    String traceId, Profile profile, int adjustedTTL) {
        int i = 0;
        return boundStatement.setString(i++, agentId)
                .setString(i++, traceId)
                .setByteBuffer(i++, ByteBuffer.wrap(profile.toByteArray()))
                .setInt(i++, adjustedTTL);
    }

    @CheckReturnValue
    private static BoundStatement bindTraceQuery(BoundStatement boundStatement, String agentRollupId,
                                                 TraceQuery query, boolean overall) {
        int i = 0;
        boundStatement = boundStatement.setString(i++, agentRollupId)
                .setString(i++, query.transactionType());
        if (!overall) {
            boundStatement = boundStatement.setString(i++, query.transactionName());
        }
        return boundStatement.setInstant(i++, Instant.ofEpochMilli(query.from()))
                .setInstant(i++, Instant.ofEpochMilli(query.to()));
    }

    @CheckReturnValue
    private static BoundStatement bindTraceQueryPartial(BoundStatement boundStatement, String agentRollupId,
                                                        TraceQuery query, boolean overall, boolean cassandra2x) {
        int i = 0;
        boundStatement = boundStatement.setString(i++, agentRollupId)
                .setString(i++, query.transactionType());
        if (!overall) {
            boundStatement = boundStatement.setString(i++, query.transactionName());
        }
        if (cassandra2x) {
            boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(query.from()))
                    .setInstant(i++, Instant.ofEpochMilli(query.to()));
        } else {
            // not using getCaptureTimePartialRollup() on "from", to support data prior to 0.13.1
            boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(query.from()))
                    .setInstant(i++, Instant.ofEpochMilli(getCaptureTimePartialRollup(query.to())))
                    .setInstant(i++, Instant.ofEpochMilli(query.from()))
                    .setInstant(i++, Instant.ofEpochMilli(query.to()));
        }
        return boundStatement;
    }

    private static long getCaptureTimePartialRollup(long captureTime) {
        // it's not really relevant that the 30-min interval matches any of the aggregate rollups,
        // this is just to help reduce proliferation of Cassandra tombstones
        return CaptureTimes.getRollup(captureTime, MINUTES.toMillis(30));
    }

    private static CompletionStage<List<TracePoint>> processPoints(AsyncResultSet results, TracePointFilter filter,
                                                                   boolean partial, boolean errorPoints) {
        return processPoints(results, filter, partial, errorPoints, new ArrayList<>());
    }

    private static CompletionStage<List<TracePoint>> processPoints(AsyncResultSet results, TracePointFilter filter,
                                                                   boolean partial, boolean errorPoints, List<TracePoint> tracePoints) {
        for (Row row : results.currentPage()) {
            int i = 0;
            String agentId = checkNotNull(row.getString(i++));
            String traceId = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
            if (partial) {
                // real_capture_time is only present for data written starting with 0.13.1
                Instant realCaptureTime = row.getInstant(i++);
                if (realCaptureTime != null) {
                    captureTime = realCaptureTime.toEpochMilli();
                }
            }
            long durationNanos = row.getLong(i++);
            boolean error = errorPoints || row.getBoolean(i++);
            // error points are defined by having an error message, so safe to checkNotNull
            String errorMessage = errorPoints ? checkNotNull(row.getString(i++)) : "";
            // headline is null for data inserted prior to 0.9.7
            String headline = Strings.nullToEmpty(row.getString(i++));
            String user = Strings.nullToEmpty(row.getString(i++));
            ByteBuffer attributeBytes = row.getByteBuffer(i++);
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
                        .partial(partial)
                        .error(error)
                        .checkLiveTraces(false)
                        .build());
            }
        }
        if (results.hasMorePages()) {
            return results.fetchNextPage().thenCompose(res -> processPoints(res, filter, partial, errorPoints, tracePoints));
        }
        return CompletableFuture.completedFuture(tracePoints);
    }

    private static Result<TracePoint> combine(List<TracePoint> completedPoints,
                                              List<TracePoint> partialPoints, int limit) {
        if (partialPoints.isEmpty()) {
            // optimization of common path
            return createResult(completedPoints, limit);
        }
        removeDuplicatePartialPoints(completedPoints, partialPoints);
        List<TracePoint> allPoints = new ArrayList<>(completedPoints.size() + partialPoints.size());
        allPoints.addAll(completedPoints);
        allPoints.addAll(partialPoints);
        if (allPoints.size() > limit) {
            allPoints = applyLimitByDurationNanosAndThenSortByCaptureTime(allPoints, limit);
            return new Result<>(allPoints, true);
        } else {
            // sort by capture time needed since combined partial points are out of order
            allPoints = Ordering.from(Comparator.comparingLong(TracePoint::captureTime))
                    .sortedCopy(allPoints);
            return new Result<>(allPoints, false);
        }
    }

    private static Result<TracePoint> createResult(List<TracePoint> tracePoints, int limit) {
        if (tracePoints.size() > limit) {
            return new Result<>(
                    applyLimitByDurationNanosAndThenSortByCaptureTime(tracePoints, limit), true);
        } else {
            return new Result<>(tracePoints, false);
        }
    }

    private static void removeDuplicatePartialPoints(List<TracePoint> completedPoints,
                                                     List<TracePoint> partialPoints) {
        // remove duplicates (partially stored traces) since there is (small) window between updated
        // insert (with new capture time) and the delete of prior insert (with prior capture time)
        Set<TraceKey> traceKeys = new HashSet<>();
        for (TracePoint completedPoint : completedPoints) {
            traceKeys.add(TraceKey.from(completedPoint));
        }
        ListIterator<TracePoint> i = partialPoints.listIterator(partialPoints.size());
        while (i.hasPrevious()) {
            if (!traceKeys.add(TraceKey.from(i.previous()))) {
                i.remove();
            }
        }
    }

    public static List<TracePoint> applyLimitByDurationNanosAndThenSortByCaptureTime(
            List<TracePoint> tracePoints, int limit) {
        return tracePoints.stream()
                .sorted(Comparator.comparingLong(TracePoint::durationNanos).reversed())
                .limit(limit)
                .sorted(Comparator.comparingLong(TracePoint::captureTime))
                // explicit type on this line is needed for Checker Framework
                // see https://github.com/typetools/checker-framework/issues/531
                .collect(Collectors.<TracePoint>toList());
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

    @Value.Immutable
    abstract static class TraceKey {

        abstract String agentId();

        abstract String traceId();

        private static TraceKey from(TracePoint tracePoint) {
            return ImmutableTraceKey.builder()
                    .agentId(tracePoint.agentId())
                    .traceId(tracePoint.traceId())
                    .build();
        }
    }

    private static class MutableLong {
        private long value;

        private void increment() {
            value++;
        }
    }
}

/*
 * Copyright 2015-2017 the original author or authors.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.immutables.value.Value;

import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.model.Result;
import org.glowroot.common.repo.ImmutableErrorMessageCount;
import org.glowroot.common.repo.ImmutableErrorMessagePoint;
import org.glowroot.common.repo.ImmutableErrorMessageResult;
import org.glowroot.common.repo.ImmutableHeaderPlus;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.Proto.StackTraceElement;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class TraceDaoImpl implements TraceDao {

    @SuppressWarnings("deprecation")
    private static final HashFunction SHA_1 = Hashing.sha1();

    private final Session session;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

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
    private final PreparedStatement insertSharedQueryText;
    private final PreparedStatement insertMainThreadProfile;
    private final PreparedStatement insertAuxThreadProfile;

    private final PreparedStatement readOverallSlowPoint;
    private final PreparedStatement readTransactionSlowPoint;
    private final PreparedStatement readOverallErrorPoint;
    private final PreparedStatement readTransactionErrorPoint;

    private final PreparedStatement readOverallSlowCount;
    private final PreparedStatement readTransactionSlowCount;
    private final PreparedStatement readOverallErrorCount;
    private final PreparedStatement readTransactionErrorCount;

    private final PreparedStatement readOverallErrorMessage;
    private final PreparedStatement readTransactionErrorMessage;

    private final PreparedStatement readHeader;
    private final PreparedStatement readEntries;
    private final PreparedStatement readSharedQueryTexts;
    private final PreparedStatement readMainThreadProfile;
    private final PreparedStatement readAuxThreadProfile;

    private final PreparedStatement deletePartialOverallSlowPoint;
    private final PreparedStatement deletePartialTransactionSlowPoint;

    private final PreparedStatement deletePartialOverallSlowCount;
    private final PreparedStatement deletePartialTransactionSlowCount;

    TraceDaoImpl(Session session, TransactionTypeDao transactionTypeDao,
            FullQueryTextDao fullQueryTextDao, TraceAttributeNameDao traceAttributeNameDao,
            ConfigRepositoryImpl configRepository, Clock clock) throws Exception {
        this.session = session;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.traceAttributeNameDao = traceAttributeNameDao;
        this.configRepository = configRepository;
        this.clock = clock;

        int expirationHours = configRepository.getCentralStorageConfig().traceExpirationHours();

        session.createTableWithTWCS("create table if not exists trace_tt_slow_point"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, duration_nanos bigint, partial boolean,"
                + " error boolean, headline varchar, user varchar, attributes blob, primary key"
                + " ((agent_rollup, transaction_type), capture_time, agent_id, trace_id))",
                expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_slow_point"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar,"
                + " duration_nanos bigint, partial boolean, error boolean, headline varchar,"
                + " user varchar, attributes blob, primary key ((agent_rollup, transaction_type,"
                + " transaction_name), capture_time, agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tt_error_point"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, duration_nanos bigint, partial boolean,"
                + " error_message varchar, headline varchar, user varchar, attributes blob,"
                + " primary key ((agent_rollup, transaction_type), capture_time, agent_id,"
                + " trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_error_point"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar,"
                + " duration_nanos bigint, partial boolean, error_message varchar,"
                + " headline varchar, user varchar, attributes blob, primary key ((agent_rollup,"
                + " transaction_type, transaction_name), capture_time, agent_id, trace_id))",
                expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tt_error_message"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, error_message varchar, primary key"
                + " ((agent_rollup, transaction_type), capture_time, agent_id, trace_id))",
                expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_error_message"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar,"
                + " error_message varchar, primary key ((agent_rollup, transaction_type,"
                + " transaction_name), capture_time, agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_header"
                + " (agent_id varchar, trace_id varchar, header blob, primary key (agent_id,"
                + " trace_id))", expirationHours);

        // index_ is just to provide uniqueness
        session.createTableWithTWCS("create table if not exists trace_entry"
                + " (agent_id varchar, trace_id varchar, index_ int, depth int,"
                + " start_offset_nanos bigint, duration_nanos bigint, active boolean,"
                + " message varchar, shared_query_text_index int, query_message_prefix varchar,"
                + " query_message_suffix varchar, detail blob, location_stack_trace blob,"
                + " error blob, primary key (agent_id, trace_id, index_))", expirationHours);

        // index_ is just to provide uniqueness
        session.createTableWithTWCS("create table if not exists trace_shared_query_text"
                + " (agent_id varchar, trace_id varchar, index_ int, truncated_text varchar,"
                + " truncated_end_text varchar, full_text_sha1 varchar, primary key (agent_id,"
                + " trace_id, index_))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_main_thread_profile"
                + " (agent_id varchar, trace_id varchar, profile blob, primary key (agent_id,"
                + " trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_aux_thread_profile"
                + " (agent_id varchar, trace_id varchar, profile blob, primary key (agent_id,"
                + " trace_id))", expirationHours);

        // agent_rollup/capture_time is not necessarily unique
        // using a counter would be nice since only need sum over capture_time range
        // but counter has no TTL, see https://issues.apache.org/jira/browse/CASSANDRA-2103
        // so adding trace_id to provide uniqueness
        session.createTableWithTWCS("create table if not exists trace_tt_slow_count"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_slow_count"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar, primary key"
                + " ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id,"
                + " trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tt_error_count"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id))", expirationHours);

        session.createTableWithTWCS("create table if not exists trace_tn_error_count"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar, primary key"
                + " ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id,"
                + " trace_id))", expirationHours);

        insertOverallSlowPoint = session.prepare("insert into trace_tt_slow_point (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id, duration_nanos, partial,"
                + " error, headline, user, attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertTransactionSlowPoint = session.prepare("insert into trace_tn_slow_point"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, duration_nanos, partial, error, headline, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallSlowCount = session.prepare("insert into trace_tt_slow_count (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id) values (?, ?, ?, ?, ?)"
                + " using ttl ?");

        insertTransactionSlowCount = session.prepare("insert into trace_tn_slow_count"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id) values (?, ?, ?, ?, ?, ?) using ttl ?");

        insertOverallErrorPoint = session.prepare("insert into trace_tt_error_point (agent_rollup,"
                + " transaction_type, capture_time, agent_id, trace_id, duration_nanos, partial,"
                + " error_message, headline, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertTransactionErrorPoint = session.prepare("insert into trace_tn_error_point"
                + " (agent_rollup, transaction_type, transaction_name, capture_time, agent_id,"
                + " trace_id, duration_nanos, partial, error_message, headline, user, attributes)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

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
                + " start_offset_nanos, duration_nanos, active, message, shared_query_text_index,"
                + " query_message_prefix, query_message_suffix, detail, location_stack_trace,"
                + " error) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");

        insertSharedQueryText = session.prepare("insert into trace_shared_query_text (agent_id,"
                + " trace_id, index_, truncated_text, truncated_end_text, full_text_sha1) values"
                + " (?, ?, ?, ?, ?, ?) using ttl ?");

        insertMainThreadProfile = session.prepare("insert into trace_main_thread_profile"
                + " (agent_id, trace_id, profile) values (?, ?, ?) using ttl ?");

        insertAuxThreadProfile = session.prepare("insert into trace_aux_thread_profile"
                + " (agent_id, trace_id, profile) values (?, ?, ?) using ttl ?");

        readOverallSlowPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, partial, error, headline, user, attributes"
                + " from trace_tt_slow_point where agent_rollup = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?");

        readTransactionSlowPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, partial, error, headline, user, attributes"
                + " from trace_tn_slow_point where agent_rollup = ? and transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readOverallErrorPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, partial, error_message, headline, user, attributes"
                + " from trace_tt_error_point where agent_rollup = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?");

        readTransactionErrorPoint = session.prepare("select agent_id, trace_id, capture_time,"
                + " duration_nanos, partial, error_message, headline, user, attributes"
                + " from trace_tn_error_point where agent_rollup = ? and transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readOverallSlowCount = session.prepare("select count(*) from trace_tt_slow_count where"
                + " agent_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?");

        readTransactionSlowCount = session.prepare("select count(*) from trace_tn_slow_count where"
                + " agent_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?");

        readOverallErrorCount = session.prepare("select count(*) from trace_tt_error_count where"
                + " agent_rollup = ? and transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?");

        readTransactionErrorCount = session.prepare("select count(*) from trace_tn_error_count"
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ?");

        readOverallErrorMessage = session.prepare("select capture_time, error_message"
                + " from trace_tt_error_message where agent_rollup = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?");

        readTransactionErrorMessage = session.prepare("select capture_time, error_message"
                + " from trace_tn_error_message where agent_rollup = ? and transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ?");

        readHeader = session
                .prepare("select header from trace_header where agent_id = ? and trace_id = ?");

        readEntries = session.prepare("select depth, start_offset_nanos, duration_nanos,"
                + " active, message, shared_query_text_index, query_message_prefix,"
                + " query_message_suffix, detail, location_stack_trace, error from trace_entry"
                + " where agent_id = ? and trace_id = ?");

        readSharedQueryTexts = session.prepare("select truncated_text, truncated_end_text,"
                + " full_text_sha1 from trace_shared_query_text where agent_id = ?"
                + " and trace_id = ?");

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
    public void store(String agentId, Trace trace) throws Exception {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
        store(agentId, agentRollupIds, agentRollupIds, trace);
    }

    public void store(String agentId, List<String> agentRollupIds,
            List<String> agentRollupIdsForMeta, Trace trace) throws Exception {
        String traceId = trace.getId();
        Trace.Header priorHeader = trace.getUpdate() ? readHeader(agentId, traceId) : null;
        Trace.Header header = trace.getHeader();

        List<Future<?>> futures = Lists.newArrayList();

        List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        for (Trace.SharedQueryText sharedQueryText : trace.getSharedQueryTextList()) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > 2 * StorageConfig.TRACE_QUERY_TEXT_TRUNCATE) {
                    fullTextSha1 = SHA_1.hashString(fullText, Charsets.UTF_8).toString();
                    futures.addAll(fullQueryTextDao.store(agentId, fullTextSha1, fullText));
                    for (int i = 1; i < agentRollupIds.size(); i++) {
                        futures.addAll(fullQueryTextDao.updateCheckTTL(agentRollupIds.get(i),
                                fullTextSha1));
                    }
                    sharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                            .setTruncatedText(
                                    fullText.substring(0, StorageConfig.TRACE_QUERY_TEXT_TRUNCATE))
                            .setTruncatedEndText(fullText.substring(
                                    fullText.length() - StorageConfig.TRACE_QUERY_TEXT_TRUNCATE,
                                    fullText.length()))
                            .setFullTextSha1(fullTextSha1)
                            .build());
                } else {
                    sharedQueryTexts.add(sharedQueryText);
                }
            } else {
                futures.addAll(fullQueryTextDao.updateTTL(agentId, fullTextSha1));
                for (int i = 1; i < agentRollupIds.size(); i++) {
                    futures.addAll(
                            fullQueryTextDao.updateCheckTTL(agentRollupIds.get(i), fullTextSha1));
                }
                sharedQueryTexts.add(sharedQueryText);
            }
        }

        // wait for success before proceeding in order to ensure cannot end up with orphaned
        // fullTextSha1
        MoreFutures.waitForAll(futures);
        futures.clear();

        int adjustedTTL =
                Common.getAdjustedTTL(configRepository.getCentralStorageConfig().getTraceTTL(),
                        header.getCaptureTime(), clock);
        for (String agentRollupId : agentRollupIds) {
            if (header.getSlow()) {
                BoundStatement boundStatement = insertOverallSlowPoint.bind();
                bindSlowPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        true);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionSlowPoint.bind();
                bindSlowPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        false);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertOverallSlowCount.bind();
                bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        true);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionSlowCount.bind();
                bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        false);
                futures.add(session.executeAsync(boundStatement));

                if (priorHeader != null) {
                    boundStatement = deletePartialOverallSlowPoint.bind();
                    bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, true);
                    futures.add(session.executeAsync(boundStatement));

                    boundStatement = deletePartialTransactionSlowPoint.bind();
                    bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, false);
                    futures.add(session.executeAsync(boundStatement));

                    boundStatement = deletePartialOverallSlowCount.bind();
                    bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, true);
                    futures.add(session.executeAsync(boundStatement));

                    boundStatement = deletePartialTransactionSlowCount.bind();
                    bind(boundStatement, agentRollupId, agentId, traceId, priorHeader, false);
                    futures.add(session.executeAsync(boundStatement));
                }
            }
            // seems unnecessary to insert error info for partial traces
            // and this avoids having to clean up partial trace data when trace is complete
            if (header.hasError() && !header.getPartial()) {
                BoundStatement boundStatement = insertOverallErrorMessage.bind();
                bindErrorMessage(boundStatement, agentRollupId, agentId, traceId, header,
                        adjustedTTL, true);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionErrorMessage.bind();
                bindErrorMessage(boundStatement, agentRollupId, agentId, traceId, header,
                        adjustedTTL, false);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertOverallErrorPoint.bind();
                bindErrorPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        true);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionErrorPoint.bind();
                bindErrorPoint(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        false);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertOverallErrorCount.bind();
                bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        true);
                futures.add(session.executeAsync(boundStatement));

                boundStatement = insertTransactionErrorCount.bind();
                bindCount(boundStatement, agentRollupId, agentId, traceId, header, adjustedTTL,
                        false);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        for (String agentRollupIdForMeta : agentRollupIdsForMeta) {
            for (Trace.Attribute attributeName : header.getAttributeList()) {
                traceAttributeNameDao.store(agentRollupIdForMeta,
                        header.getTransactionType(), attributeName.getName(), futures);
            }
        }

        BoundStatement boundStatement = insertHeader.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, traceId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(header.toByteArray()));
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        int index = 0;
        for (Trace.Entry entry : trace.getEntryList()) {
            boundStatement = insertEntry.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, traceId);
            boundStatement.setInt(i++, index++);
            boundStatement.setInt(i++, entry.getDepth());
            boundStatement.setLong(i++, entry.getStartOffsetNanos());
            boundStatement.setLong(i++, entry.getDurationNanos());
            boundStatement.setBool(i++, entry.getActive());
            if (entry.hasQueryEntryMessage()) {
                boundStatement.setToNull(i++);
                boundStatement.setInt(i++, entry.getQueryEntryMessage().getSharedQueryTextIndex());
                boundStatement.setString(i++,
                        Strings.emptyToNull(entry.getQueryEntryMessage().getPrefix()));
                boundStatement.setString(i++,
                        Strings.emptyToNull(entry.getQueryEntryMessage().getSuffix()));
            } else {
                // message is empty for trace entries added using addErrorEntry()
                boundStatement.setString(i++, Strings.emptyToNull(entry.getMessage()));
                boundStatement.setToNull(i++);
                boundStatement.setToNull(i++);
                boundStatement.setToNull(i++);
            }
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
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        index = 0;
        for (Trace.SharedQueryText sharedQueryText : sharedQueryTexts) {
            boundStatement = insertSharedQueryText.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, traceId);
            boundStatement.setInt(i++, index++);
            String fullText = sharedQueryText.getFullText();
            if (fullText.isEmpty()) {
                boundStatement.setString(i++, sharedQueryText.getTruncatedText());
                boundStatement.setString(i++, sharedQueryText.getTruncatedEndText());
                boundStatement.setString(i++, sharedQueryText.getFullTextSha1());
            } else {
                boundStatement.setString(i++, fullText);
                boundStatement.setToNull(i++);
                boundStatement.setToNull(i++);
            }
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        if (trace.hasMainThreadProfile()) {
            boundStatement = insertMainThreadProfile.bind();
            bindThreadProfile(boundStatement, agentId, traceId, trace.getMainThreadProfile(),
                    adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        if (trace.hasAuxThreadProfile()) {
            boundStatement = insertAuxThreadProfile.bind();
            bindThreadProfile(boundStatement, agentId, traceId, trace.getAuxThreadProfile(),
                    adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        futures.addAll(
                transactionTypeDao.store(agentRollupIdsForMeta, header.getTransactionType()));
        MoreFutures.waitForAll(futures);
    }

    @Override
    public Result<TracePoint> readSlowPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallSlowPoint.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionSlowPoint.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        ResultSet results = session.execute(boundStatement);
        return processPoints(results, filter, limit, false);
    }

    @Override
    public Result<TracePoint> readErrorPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorPoint.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorPoint.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        ResultSet results = session.execute(boundStatement);
        return processPoints(results, filter, limit, true);
    }

    @Override
    public long readSlowCount(String agentRollupId, TraceQuery query) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallSlowCount.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionSlowCount.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        ResultSet results = session.execute(boundStatement);
        return results.one().getLong(0);
    }

    @Override
    public long readErrorCount(String agentRollupId, TraceQuery query) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorCount.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorCount.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, false);
        }
        ResultSet results = session.execute(boundStatement);
        return results.one().getLong(0);
    }

    @Override
    public ErrorMessageResult readErrorMessages(String agentRollupId, TraceQuery query,
            ErrorMessageFilter filter, long resolutionMillis, int limit) throws Exception {
        BoundStatement boundStatement;
        String transactionName = query.transactionName();
        if (transactionName == null) {
            boundStatement = readOverallErrorMessage.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, true);
        } else {
            boundStatement = readTransactionErrorMessage.bind();
            bindTraceQuery(boundStatement, agentRollupId, query, false);
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
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, resolutionMillis);
            pointCounts.computeIfAbsent(rollupCaptureTime, k -> new MutableLong()).increment();
            messageCounts.computeIfAbsent(errorMessage, k -> new MutableLong()).increment();
        }
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
    }

    @Override
    public @Nullable HeaderPlus readHeaderPlus(String agentId, String traceId) throws Exception {
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
    public Entries readEntries(String agentId, String traceId) throws Exception {
        return ImmutableEntries.builder()
                .addAllEntries(readEntriesInternal(agentId, traceId))
                .addAllSharedQueryTexts(readSharedQueryTexts(agentId, traceId))
                .build();
    }

    // since this is only used by export, SharedQueryTexts are always returned with fullTrace
    // (never with truncatedText/truncatedEndText/fullTraceSha1)
    @Override
    public Entries readEntriesForExport(String agentId, String traceId) throws Exception {
        ImmutableEntries.Builder entries = ImmutableEntries.builder()
                .addAllEntries(readEntriesInternal(agentId, traceId));
        List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        for (Trace.SharedQueryText sharedQueryText : readSharedQueryTexts(agentId, traceId)) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                sharedQueryTexts.add(sharedQueryText);
            } else {
                String fullText = fullQueryTextDao.getFullText(agentId, fullTextSha1);
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
        return entries.addAllSharedQueryTexts(sharedQueryTexts)
                .build();
    }

    @Override
    public @Nullable Profile readMainThreadProfile(String agentId, String traceId)
            throws Exception {
        BoundStatement boundStatement = readMainThreadProfile.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return Profile.parseFrom(checkNotNull(row.getBytes(0)));
    }

    @Override
    public @Nullable Profile readAuxThreadProfile(String agentId, String traceId) throws Exception {
        BoundStatement boundStatement = readAuxThreadProfile.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return Profile.parseFrom(checkNotNull(row.getBytes(0)));
    }

    private @Nullable Trace.Header readHeader(String agentId, String traceId) throws Exception {
        BoundStatement boundStatement = readHeader.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return Trace.Header.parseFrom(checkNotNull(row.getBytes(0)));
    }

    private List<Trace.Entry> readEntriesInternal(String agentId, String traceId) throws Exception {
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
                    .setActive(row.getBool(i++));
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
                entry.setError(Trace.Error.parseFrom(errorBytes));
            }
            entries.add(entry.build());
        }
        return entries;
    }

    private List<Trace.SharedQueryText> readSharedQueryTexts(String agentId, String traceId)
            throws Exception {
        BoundStatement boundStatement = readSharedQueryTexts.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, traceId);
        ResultSet results = session.execute(boundStatement);
        List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        while (!results.isExhausted()) {
            Row row = results.one();
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
        return sharedQueryTexts;
    }

    private static void bindSlowPoint(BoundStatement boundStatement, String agentRollupId,
            String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall)
            throws IOException {
        int i = bind(boundStatement, agentRollupId, agentId, traceId, header, overall);
        boundStatement.setLong(i++, header.getDurationNanos());
        boundStatement.setBool(i++, header.getPartial());
        boundStatement.setBool(i++, header.hasError());
        boundStatement.setString(i++, header.getHeadline());
        boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
        List<Trace.Attribute> attributes = header.getAttributeList();
        if (attributes.isEmpty()) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setBytes(i++, Messages.toByteBuffer(attributes));
        }
        boundStatement.setInt(i++, adjustedTTL);
    }

    private static void bindCount(BoundStatement boundStatement, String agentRollupId,
            String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall) {
        int i = bind(boundStatement, agentRollupId, agentId, traceId, header, overall);
        boundStatement.setInt(i++, adjustedTTL);
    }

    private static void bindErrorMessage(BoundStatement boundStatement, String agentRollupId,
            String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall) {
        int i = bind(boundStatement, agentRollupId, agentId, traceId, header, overall);
        boundStatement.setString(i++, header.getError().getMessage());
        boundStatement.setInt(i++, adjustedTTL);
    }

    private static void bindErrorPoint(BoundStatement boundStatement, String agentRollupId,
            String agentId, String traceId, Trace.Header header, int adjustedTTL, boolean overall)
            throws IOException {
        int i = bind(boundStatement, agentRollupId, agentId, traceId, header, overall);
        boundStatement.setLong(i++, header.getDurationNanos());
        boundStatement.setBool(i++, header.getPartial());
        boundStatement.setString(i++, header.getError().getMessage());
        boundStatement.setString(i++, header.getHeadline());
        boundStatement.setString(i++, Strings.emptyToNull(header.getUser()));
        List<Trace.Attribute> attributes = header.getAttributeList();
        if (attributes.isEmpty()) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setBytes(i++, Messages.toByteBuffer(attributes));
        }
        boundStatement.setInt(i++, adjustedTTL);
    }

    private static int bind(BoundStatement boundStatement, String agentRollupId, String agentId,
            String traceId, Trace.Header header, boolean overall) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, header.getTransactionType());
        if (!overall) {
            boundStatement.setString(i++, header.getTransactionName());
        }
        boundStatement.setTimestamp(i++, new Date(header.getCaptureTime()));
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, traceId);
        return i;
    }

    private static void bindThreadProfile(BoundStatement boundStatement, String agentId,
            String traceId, Profile profile, int adjustedTTL) {
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, traceId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(profile.toByteArray()));
        boundStatement.setInt(i++, adjustedTTL);
    }

    private static void bindTraceQuery(BoundStatement boundStatement, String agentRollupId,
            TraceQuery query, boolean overall) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        if (!overall) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
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
            boolean partial = row.getBool(i++);
            boolean error = errorPoints ? true : row.getBool(i++);
            // error points are defined by having an error message, so safe to checkNotNull
            String errorMessage = errorPoints ? checkNotNull(row.getString(i++)) : "";
            // headline is null for data inserted prior to 0.9.7
            String headline = Strings.nullToEmpty(row.getString(i++));
            String user = Strings.nullToEmpty(row.getString(i++));
            ByteBuffer attributeBytes = row.getBytes(i++);
            List<Trace.Attribute> attrs =
                    Messages.parseDelimitedFrom(attributeBytes, Trace.Attribute.parser());
            Map<String, List<String>> attributes = attrs.stream().collect(
                    Collectors.toMap(Trace.Attribute::getName, Trace.Attribute::getValueList));
            if (filter.matchesHeadline(headline)
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
            tracePoints = applyLimitByDurationNanosAndThenSortByCaptureTime(tracePoints, limit);
            return new Result<>(tracePoints, true);
        } else {
            return new Result<>(tracePoints, false);
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

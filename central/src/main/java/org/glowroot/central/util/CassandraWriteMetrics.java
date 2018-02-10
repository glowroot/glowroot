/*
private * Copyright 2018 the original author or authors.
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
package org.glowroot.central.util;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Longs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ImmutableCassandraWriteTotals;
import org.glowroot.common.repo.RepoAdmin.CassandraWriteTotals;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class CassandraWriteMetrics {

    private static final Logger logger = LoggerFactory.getLogger(CassandraWriteMetrics.class);

    private static final int TRANSACTION_NAME_LIMIT = 100;
    private static final String TRANSACTION_NAME_OTHER = "Other";

    private final Session session;
    private final String keyspace;

    private final Map<String, WriteMetrics> writeMetrics = new ConcurrentHashMap<>();

    private final ThreadLocal</*@Nullable*/ String> currTransactionType = new ThreadLocal<>();
    private final ThreadLocal</*@Nullable*/ String> currTransactionName = new ThreadLocal<>();

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> partialTrace = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    CassandraWriteMetrics(Session session, String keyspace) {
        this.session = session;
        this.keyspace = keyspace;
        long millisSinceLastMidnightUTC = System.currentTimeMillis() % DAYS.toMillis(1);
        long millisUntilNextMidnightUTC = DAYS.toMillis(1) - millisSinceLastMidnightUTC;
        // clear metrics once a day (midnight UTC) to make sure the map of agent rollup ids doesn't
        // grow unbounded, and also so map of transaction names doesn't become stagnant once it
        // reaches limit
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(writeMetrics::clear,
                millisUntilNextMidnightUTC, DAYS.toMillis(1), MILLISECONDS);
    }

    public void setCurrTransactionType(@Nullable String transactionType) {
        currTransactionType.set(transactionType);
    }

    public void setCurrTransactionName(@Nullable String transactionName) {
        currTransactionName.set(transactionName);
    }

    public void setPartialTrace(boolean partial) {
        partialTrace.set(partial);
    }

    public List<CassandraWriteTotals> getCassandraDataWrittenPerTable(int limit) {
        return getCassandraDataWritten(writeMetrics, limit);
    }

    public List<CassandraWriteTotals> getCassandraDataWrittenPerAgentRollup(String tableName,
            int limit) {
        WriteMetrics perTableMetrics = writeMetrics.get(tableName);
        if (perTableMetrics == null) {
            return ImmutableList.of();
        }
        return getCassandraDataWritten(perTableMetrics.nestedWriteMetricsMap, limit);
    }

    public List<CassandraWriteTotals> getCassandraDataWrittenPerTransactionType(String tableName,
            String agentRollupId, int limit) {
        WriteMetrics perTableMetrics = writeMetrics.get(tableName);
        if (perTableMetrics == null) {
            return ImmutableList.of();
        }
        perTableMetrics = perTableMetrics.nestedWriteMetricsMap.get(agentRollupId);
        if (perTableMetrics == null) {
            return ImmutableList.of();
        }
        return getCassandraDataWritten(perTableMetrics.nestedWriteMetricsMap, limit);
    }

    public List<CassandraWriteTotals> getCassandraDataWrittenPerTransactionName(String tableName,
            String agentRollupId, String transactionType, int limit) {
        WriteMetrics perTableMetrics = writeMetrics.get(tableName);
        if (perTableMetrics == null) {
            return ImmutableList.of();
        }
        perTableMetrics = perTableMetrics.nestedWriteMetricsMap.get(agentRollupId);
        if (perTableMetrics == null) {
            return ImmutableList.of();
        }
        perTableMetrics = perTableMetrics.nestedWriteMetricsMap.get(transactionType);
        if (perTableMetrics == null) {
            return ImmutableList.of();
        }
        return getCassandraDataWritten(perTableMetrics.nestedWriteMetricsMap, limit);
    }

    void recordMetrics(Statement statement) {
        try {
            recordMetricsInternal(statement);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void recordMetricsInternal(Statement statement) {
        if (!(statement instanceof BoundStatement)) {
            return;
        }
        BoundStatement boundStatement = (BoundStatement) statement;
        PreparedStatement preparedStatement = boundStatement.preparedStatement();
        if (!preparedStatement.getQueryString().startsWith("insert ")) {
            return;
        }
        List<ColumnDefinitions.Definition> columnDefinitions =
                preparedStatement.getVariables().asList();
        String tableName = columnDefinitions.get(0).getTable();
        String display;
        if (partialTrace.get() && !tableName.endsWith("_partial")) {
            display = tableName + " (partial trace)";
        } else {
            display = tableName;
        }
        String agentRollupId = getAgentRollupId(columnDefinitions, boundStatement);
        if (agentRollupId == null) {
            return;
        }
        // per table metrics
        WriteMetrics perTableMetrics = writeMetrics
                .computeIfAbsent(display, k -> new WriteMetrics(display));
        perTableMetrics.rowsWritten.incrementAndGet();
        // per agent rollup metrics
        // TODO report checker framework issue that occurs without checkNotNull
        WriteMetrics perAgentRollupMetrics = perTableMetrics.nestedWriteMetricsMap
                .computeIfAbsent(agentRollupId, k -> new WriteMetrics(checkNotNull(agentRollupId)));
        perAgentRollupMetrics.rowsWritten.incrementAndGet();
        // per transaction type metrics
        String transactionType = getTransactionType(columnDefinitions, boundStatement);
        WriteMetrics perTransactionTypeMetrics = null;
        WriteMetrics perTransactionNameMetrics = null;
        if (transactionType != null) {
            // TODO report checker framework issue that occurs without checkNotNull
            perTransactionTypeMetrics = perAgentRollupMetrics.nestedWriteMetricsMap.computeIfAbsent(
                    transactionType, k -> new WriteMetrics(checkNotNull(transactionType)));
            perTransactionTypeMetrics.rowsWritten.incrementAndGet();
            // per transaction name metrics
            String transactionName = transactionType == null ? null
                    : getTransactionName(columnDefinitions, boundStatement);
            if (transactionName != null) {
                Map<String, WriteMetrics> nestedWriteMetricsMap =
                        perTransactionTypeMetrics.nestedWriteMetricsMap;
                synchronized (nestedWriteMetricsMap) {
                    perTransactionNameMetrics =
                            getOrCreateOrOther(nestedWriteMetricsMap, transactionName);
                }
                // TODO report checker framework issue that occurs without checkNotNull
                perTransactionNameMetrics = nestedWriteMetricsMap
                        .computeIfAbsent(transactionName,
                                k -> new WriteMetrics(checkNotNull(transactionName)));
                perTransactionNameMetrics.rowsWritten.incrementAndGet();
            }
        }
        KeyspaceMetadata keyspaceMetadata =
                session.getCluster().getMetadata().getKeyspace(keyspace);
        if (keyspaceMetadata == null) {
            // this should not happen
            return;
        }
        Set<String> partitionKeyColumnNames = keyspaceMetadata.getTable(tableName)
                .getPartitionKey()
                .stream()
                .map(ColumnMetadata::getName)
                .collect(Collectors.toSet());
        for (int i = 1; i < columnDefinitions.size(); i++) {
            ColumnDefinitions.Definition columnDefinition = columnDefinitions.get(i);
            if (partitionKeyColumnNames.contains(columnDefinition.getName())) {
                continue;
            }
            int numBytes = getNumBytes(boundStatement, i, columnDefinition.getType());
            if (numBytes > 0) {
                String columnName = columnDefinition.getName();
                perTableMetrics.bytesWritten.addAndGet(numBytes);
                perTableMetrics.bytesWrittenPerColumn
                        .computeIfAbsent(columnName, k -> new AtomicLong())
                        .addAndGet(numBytes);
                perAgentRollupMetrics.bytesWritten.addAndGet(numBytes);
                perAgentRollupMetrics.bytesWrittenPerColumn
                        .computeIfAbsent(columnName, k -> new AtomicLong())
                        .addAndGet(numBytes);
                if (perTransactionTypeMetrics != null) {
                    perTransactionTypeMetrics.bytesWritten.addAndGet(numBytes);
                    perTransactionTypeMetrics.bytesWrittenPerColumn
                            .computeIfAbsent(columnName, k -> new AtomicLong())
                            .addAndGet(numBytes);
                }
                if (perTransactionNameMetrics != null) {
                    perTransactionNameMetrics.bytesWritten.addAndGet(numBytes);
                    perTransactionNameMetrics.bytesWrittenPerColumn
                            .computeIfAbsent(columnName, k -> new AtomicLong())
                            .addAndGet(numBytes);
                }
            }
        }
    }

    private WriteMetrics getOrCreateOrOther(Map<String, WriteMetrics> nestedWriteMetricsMap,
            String transactionName) {
        WriteMetrics perTransactionNameMetrics = nestedWriteMetricsMap.get(transactionName);
        if (perTransactionNameMetrics != null) {
            return perTransactionNameMetrics;
        }
        if (nestedWriteMetricsMap.size() < TRANSACTION_NAME_LIMIT - 1) {
            perTransactionNameMetrics = new WriteMetrics(transactionName);
            nestedWriteMetricsMap.put(transactionName, perTransactionNameMetrics);
            return perTransactionNameMetrics;
        }
        perTransactionNameMetrics = nestedWriteMetricsMap.get(TRANSACTION_NAME_OTHER);
        if (perTransactionNameMetrics != null) {
            return perTransactionNameMetrics;
        }
        perTransactionNameMetrics = new WriteMetrics(TRANSACTION_NAME_OTHER);
        nestedWriteMetricsMap.put(transactionName, perTransactionNameMetrics);
        return perTransactionNameMetrics;
    }

    private @Nullable String getAgentRollupId(List<ColumnDefinitions.Definition> columnDefinitions,
            BoundStatement boundStatement) {
        ColumnDefinitions.Definition columnDefinition = columnDefinitions.get(0);
        String columnDefinitionName = columnDefinition.getName();
        if (columnDefinitionName.equals("agent_rollup_id")
                || columnDefinitionName.equals("agent_id")
                || columnDefinitionName.equals("agent_rollup")) {
            return boundStatement.getString(0);
        } else {
            return null;
        }
    }

    private @Nullable String getTransactionType(
            List<ColumnDefinitions.Definition> columnDefinitions, BoundStatement boundStatement) {
        if (columnDefinitions.size() < 2) {
            return currTransactionType.get();
        }
        ColumnDefinitions.Definition columnDefinition = columnDefinitions.get(1);
        String columnDefinitionName = columnDefinition.getName();
        if (columnDefinitionName.equals("transaction_type")) {
            return boundStatement.getString(1);
        } else {
            return currTransactionType.get();
        }
    }

    private @Nullable String getTransactionName(
            List<ColumnDefinitions.Definition> columnDefinitions, BoundStatement boundStatement) {
        if (columnDefinitions.size() < 3) {
            return currTransactionName.get();
        }
        ColumnDefinitions.Definition columnDefinition = columnDefinitions.get(2);
        String columnDefinitionName = columnDefinition.getName();
        if (columnDefinitionName.equals("transaction_name")) {
            return boundStatement.getString(2);
        } else {
            return currTransactionName.get();
        }
    }

    private int getNumBytes(BoundStatement boundStatement, int i, DataType dataType) {
        switch (dataType.getName()) {
            case VARCHAR:
                String s = boundStatement.getString(i);
                return s == null ? 0 : s.length();
            case BLOB:
                ByteBuffer bb = boundStatement.getBytes(i);
                return bb == null ? 0 : bb.limit();
            default:
                return 0;
        }
    }

    private List<CassandraWriteTotals> getCassandraDataWritten(
            Map<String, WriteMetrics> writeMetricsMap, int limit) {
        return writeMetricsMap.values().stream()
                .sorted(Comparator.reverseOrder())
                .limit(limit)
                .map(WriteMetrics::toDataWritten)
                .collect(Collectors.toList());
    }

    private static class WriteMetrics implements Comparable<WriteMetrics> {

        private final String display;
        private final AtomicLong rowsWritten = new AtomicLong();
        private final AtomicLong bytesWritten = new AtomicLong();
        private final Map<String, AtomicLong> bytesWrittenPerColumn = new ConcurrentHashMap<>();
        private final Map<String, WriteMetrics> nestedWriteMetricsMap = new ConcurrentHashMap<>();

        private WriteMetrics(String display) {
            this.display = display;
        }

        CassandraWriteTotals toDataWritten() {
            return ImmutableCassandraWriteTotals.builder()
                    .display(display)
                    .rowsWritten(rowsWritten.get())
                    .bytesWritten(bytesWritten.get())
                    .bytesWrittenPerColumn(bytesWrittenPerColumn.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())))
                    .drilldown(!nestedWriteMetricsMap.isEmpty())
                    .build();
        }

        @Override
        public int compareTo(WriteMetrics o) {
            return Longs.compare(this.bytesWritten.get(), o.bytesWritten.get());
        }
    }
}

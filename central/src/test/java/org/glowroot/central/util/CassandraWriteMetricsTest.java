/*
 * Copyright 2026 the original author or authors.
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

import java.util.List;
import java.util.Optional;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataTypes;
import org.junit.jupiter.api.Test;

import org.glowroot.common2.repo.RepoAdmin.CassandraWriteTotals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CassandraWriteMetricsTest {

    @Test
    public void recordMetricsCountsAllBoundStatementsInBatchStatement() throws Exception {
        CqlSession cqlSession = mock(CqlSession.class);
        Metadata metadata = mock(Metadata.class);
        KeyspaceMetadata keyspaceMetadata = mock(KeyspaceMetadata.class);
        TableMetadata tableMetadata = mock(TableMetadata.class);
        ColumnMetadata pkCol1 = mock(ColumnMetadata.class);
        ColumnMetadata pkCol2 = mock(ColumnMetadata.class);
        when(pkCol1.getName()).thenReturn(CqlIdentifier.fromInternal("agent_rollup"));
        when(pkCol2.getName()).thenReturn(CqlIdentifier.fromInternal("transaction_type"));
        when(tableMetadata.getPartitionKey()).thenReturn(List.of(pkCol1, pkCol2));
        when(keyspaceMetadata.getTable("aggregate_tt_query_rollup_0"))
                .thenReturn(Optional.of(tableMetadata));
        when(metadata.getKeyspace("glowroot")).thenReturn(Optional.of(keyspaceMetadata));
        when(cqlSession.getMetadata()).thenReturn(metadata);

        ColumnDefinition col0 = mockColumn("aggregate_tt_query_rollup_0", "agent_rollup");
        ColumnDefinition col1 = mockColumn("aggregate_tt_query_rollup_0", "transaction_type");
        ColumnDefinition col2 = mockColumn("aggregate_tt_query_rollup_0", "truncated_query_text");
        when(col2.getType()).thenReturn(DataTypes.TEXT);

        ColumnDefinitions defs = mock(ColumnDefinitions.class);
        when(defs.size()).thenReturn(3);
        when(defs.get(0)).thenReturn(col0);
        when(defs.get(1)).thenReturn(col1);
        when(defs.get(2)).thenReturn(col2);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.getVariableDefinitions()).thenReturn(defs);

        BoundStatement bs1 = mockBoundStatement(ps, "agent1", "Web", "select 12345");
        BoundStatement bs2 = mockBoundStatement(ps, "agent1", "Web", "select 67890");
        BatchStatement batch = BatchStatement.newInstance(DefaultBatchType.UNLOGGED, bs1, bs2);

        CassandraWriteMetrics metrics = new CassandraWriteMetrics(cqlSession, "glowroot");
        try {
            metrics.recordMetrics(batch);

            List<CassandraWriteTotals> perTable = metrics.getCassandraDataWrittenPerTable(10);
            assertThat(perTable).hasSize(1);
            assertThat(perTable.get(0).display()).isEqualTo("aggregate_tt_query_rollup_0");
            assertThat(perTable.get(0).rowsWritten()).isEqualTo(2);
            assertThat(perTable.get(0).bytesWritten()).isEqualTo(24);
        } finally {
            metrics.close();
        }
    }

    private static ColumnDefinition mockColumn(String table, String column) {
        ColumnDefinition def = mock(ColumnDefinition.class);
        when(def.getTable()).thenReturn(CqlIdentifier.fromInternal(table));
        when(def.getName()).thenReturn(CqlIdentifier.fromInternal(column));
        return def;
    }

    private static BoundStatement mockBoundStatement(PreparedStatement ps, String agentRollup,
            String transactionType, String queryText) {
        BoundStatement bs = mock(BoundStatement.class);
        when(bs.getPreparedStatement()).thenReturn(ps);
        when(bs.getString(0)).thenReturn(agentRollup);
        when(bs.getString(1)).thenReturn(transactionType);
        when(bs.getString(2)).thenReturn(queryText);
        return bs;
    }
}

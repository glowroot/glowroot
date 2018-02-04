/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.central;

import java.util.List;

import org.glowroot.central.util.CassandraWriteMetrics;
import org.glowroot.common.repo.RepoAdmin;

class RepoAdminImpl implements RepoAdmin {

    private final CassandraWriteMetrics cassandraWriteMetrics;

    RepoAdminImpl(CassandraWriteMetrics cassandraWriteMetrics) {
        this.cassandraWriteMetrics = cassandraWriteMetrics;
    }

    @Override
    public void defragH2Data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void compactH2Data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getH2DataFileSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<H2Table> analyzeH2DiskSpace() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TraceCounts analyzeTraceCounts() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllData() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resizeIfNeeded() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTable(int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTable(limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerAgentRollup(String tableName,
            int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerAgentRollup(tableName, limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionType(
            String tableName, String agentRollupId, int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTransactionType(tableName,
                agentRollupId, limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionName(
            String tableName, String agentRollupId, String transactionType, int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTransactionName(tableName,
                agentRollupId, transactionType, limit);
    }
}

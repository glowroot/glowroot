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
package org.glowroot.central.repo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.RepoAdmin;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres stand-in for {@link RepoAdminImpl}. Health check hits JDBC; Cassandra/H2 admin ops are
 * unsupported.
 */
public class PostgresRepoAdmin implements RepoAdmin {

    private final DataSource dataSource;
    private final ActiveAgentStore activeAgentDao;
    private final Clock clock;

    public PostgresRepoAdmin(DataSource dataSource, ActiveAgentStore activeAgentDao, Clock clock) {
        this.dataSource = dataSource;
        this.activeAgentDao = activeAgentDao;
        this.clock = clock;
    }

    @Override
    public void runHealthCheck() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select 1")) {
            if (!rs.next()) {
                throw new Exception("Postgres health check returned no row");
            }
        }
        long now = clock.currentTimeMillis();
        activeAgentDao.readActiveTopLevelAgentRollups(now - HOURS.toMillis(4), now,
                CassandraProfile.web).toCompletableFuture().get();
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
        return -1;
    }

    @Override
    public List<H2Table> analyzeH2DiskSpace() {
        return Collections.emptyList();
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
    public int updateCassandraTwcsWindowSizes() {
        return 0;
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTable(int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerAgentRollup(String tableName,
            int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionType(String tableName,
            String agentRollupId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionName(String tableName,
            String agentRollupId, String transactionType, int limit) {
        return Collections.emptyList();
    }
}

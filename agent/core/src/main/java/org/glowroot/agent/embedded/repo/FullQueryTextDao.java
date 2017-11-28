/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class FullQueryTextDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("full_text_sha1", ColumnType.VARCHAR),
            ImmutableColumn.of("full_text", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.of("full_query_text_idx", ImmutableList.of("full_text_sha1")));

    private final DataSource dataSource;

    private final Cache<String, Boolean> lastCaptureTimeUpdatedInThePastDay =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(1, DAYS)
                    .maximumSize(10000)
                    .build();

    private final Object lock = new Object();

    FullQueryTextDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("full_query_text", columns);
        dataSource.syncIndexes("full_query_text", indexes);
    }

    String updateLastCaptureTime(String fullText, long captureTime) throws SQLException {
        String fullTextSha1 = Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
        if (lastCaptureTimeUpdatedInThePastDay.getIfPresent(fullTextSha1) != null) {
            return fullTextSha1;
        }
        synchronized (lock) {
            int updateCount = dataSource.update(
                    "update full_query_text set last_capture_time = ? where full_text_sha1 = ?",
                    captureTime, fullTextSha1);
            if (updateCount == 0) {
                dataSource.update("insert into full_query_text (full_text_sha1, full_text,"
                        + " last_capture_time) values (?, ?, ?)", fullTextSha1, fullText,
                        captureTime);
            }
        }
        lastCaptureTimeUpdatedInThePastDay.put(fullTextSha1, true);
        return fullTextSha1;
    }

    @Nullable
    String getFullText(final String fullTextSha1) throws SQLException {
        return dataSource.queryAtMostOne(new JdbcRowQuery<String>() {
            @Override
            public @Untainted String getSql() {
                return "select full_text from full_query_text where full_text_sha1 = ?";
            }
            @Override
            public void bind(PreparedStatement preparedStatement) throws SQLException {
                preparedStatement.setString(1, fullTextSha1);
            }
            @Override
            public String mapRow(ResultSet resultSet) throws Exception {
                return checkNotNull(resultSet.getString(1));
            }
        });
    }

    void deleteBefore(long captureTime) throws Exception {
        // subtracting 1 day to account for rate limiting of updates
        dataSource.deleteBeforeUsingLock("full_query_text", "last_capture_time",
                captureTime - DAYS.toMillis(1), lock);
    }

    void invalidateCache() {
        lastCaptureTimeUpdatedInThePastDay.invalidateAll();
    }
}

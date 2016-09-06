/*
 * Copyright 2016 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.Charsets;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.RateLimiter;

import org.glowroot.common.repo.ConfigRepository;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

class FullQueryTextDao {

    private final Session session;
    private final ConfigRepository configRepository;

    private final PreparedStatement insertCheckPS;
    private final PreparedStatement readCheckPS;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    // 2-day expiration is just to periodically clean up cache
    private final LoadingCache<String, RateLimiter> rateLimiters = CacheBuilder.newBuilder()
            .expireAfterAccess(2, DAYS)
            .maximumSize(10000)
            .build(new CacheLoader<String, RateLimiter>() {
                @Override
                public RateLimiter load(String fullTextSha1) throws Exception {
                    // 1 permit per 24 hours
                    return RateLimiter.create(1 / (24 * 3600.0));
                }
            });

    FullQueryTextDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        // intentionally using default size-tiered compaction strategy
        session.execute("create table if not exists full_query_text_check (agent_rollup varchar,"
                + " full_query_text_sha1 varchar, primary key (agent_rollup,"
                + " full_query_text_sha1))");
        session.execute("create table if not exists full_query_text (full_query_text_sha1 varchar,"
                + " full_query_text varchar, primary key (full_query_text_sha1))");

        insertCheckPS = session.prepare("insert into full_query_text_check (agent_rollup,"
                + " full_query_text_sha1) values (?, ?) using ttl ?");
        readCheckPS = session.prepare("select agent_rollup from full_query_text_check"
                + " where agent_rollup = ? and full_query_text_sha1 = ?");

        insertPS = session.prepare("insert into full_query_text (full_query_text_sha1,"
                + " full_query_text) values (?, ?) using ttl ?");
        readPS = session.prepare(
                "select full_query_text from full_query_text where full_query_text_sha1 = ?");
    }

    @Nullable
    String getFullText(String agentRollup, String fullTextSha1) {
        BoundStatement boundStatement = readCheckPS.bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, fullTextSha1);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return null;
        }
        boundStatement = readPS.bind();
        boundStatement.setString(0, fullTextSha1);
        results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return row.getString(0);
    }

    String updateLastCaptureTime(String agentRollup, String fullText,
            List<ResultSetFuture> futures) {
        String fullTextSha1 = Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
        // not currently rate-limiting check inserts
        BoundStatement boundStatement = insertCheckPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, fullTextSha1);
        boundStatement.setInt(i++, getTTL());
        futures.add(session.executeAsync(boundStatement));
        // rate limit the large query text inserts
        RateLimiter rateLimiter = rateLimiters.getUnchecked(fullTextSha1);
        if (!rateLimiter.tryAcquire()) {
            return fullTextSha1;
        }
        boundStatement = insertPS.bind();
        i = 0;
        boundStatement.setString(i++, fullTextSha1);
        boundStatement.setString(i++, fullText);
        boundStatement.setInt(i++, getTTL());
        futures.add(TransactionTypeDao.executeAsyncUnderRateLimiter(session, boundStatement,
                rateLimiters, fullTextSha1));
        return fullTextSha1;
    }

    private int getTTL() {
        return Ints.saturatedCast(HOURS
                .toSeconds(configRepository.getStorageConfig().fullQueryTextExpirationHours()));
    }
}

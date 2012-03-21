/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.jdbc;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.shaded.google.common.cache.CacheBuilder;
import org.informantproject.shaded.google.common.cache.CacheLoader;
import org.informantproject.shaded.google.common.cache.LoadingCache;
import org.informantproject.shaded.google.common.collect.MapMaker;

/**
 * Used by JdbcAspect to associate a {@link StatementMirror} with every {@link Statement}.
 * 
 * {@link StatementMirror} is used to capture and mirror the state of statements since the
 * underlying {@link Statement} values cannot be inspected after they have been set.
 * 
 * Weak references are used to retain this association for only as long as the underlying
 * {@link Statement} is retained.
 * 
 * Note: {@link PreparedStatement}s are often retained by the application server to be reused later
 * so this association can (and needs to) last for a long time in this case.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class StatementMirrorCache {

    private static final Logger logger = LoggerFactory.getLogger(StatementMirrorCache.class);

    private final LoadingCache<Statement, StatementMirror> statementMirrorCache = CacheBuilder
            .newBuilder().weakKeys().build(new StatementMirrorLazyMapValueFunction());

    private final ConcurrentMap<PreparedStatement, PreparedStatementMirror> preparedStatementMirrorMap =
            new MapMaker().weakKeys().makeMap();

    private final AtomicBoolean missingSqlTextErrorLogged = new AtomicBoolean();

    StatementMirror getStatementMirror(Statement statement) {
        if (statement instanceof PreparedStatement) {
            return preparedStatementMirrorMap.get(statement);
        } else {
            return statementMirrorCache.getUnchecked(statement);
        }
    }

    PreparedStatementMirror getOrCreatePreparedStatementMirror(
            PreparedStatement preparedStatement, String sql) {

        PreparedStatementMirror info = preparedStatementMirrorMap.get(preparedStatement);
        if (info == null) {
            // shouldn't need to worry about multiple threads putting the same prepared statement
            // into the map at the same time since prepared statements are typically checked out
            // from a pool and owned by a thread until the prepared statement is closed.
            // however, this case is handled anyways just to be safe since maybe(?) it's possible
            // that a pool could hand out read-only versions of a prepared statement that has no
            // parameters
            preparedStatementMirrorMap.putIfAbsent(preparedStatement, new PreparedStatementMirror(
                    sql));
            info = preparedStatementMirrorMap.get(preparedStatement);
        } else if (!info.getSql().equals(sql)) {
            // sql has changed for this PreparedStatement
            // this needs to be handled since a PreparedStatement pool
            // could in theory reuse closed PreparedStatement instances
            preparedStatementMirrorMap.replace(preparedStatement, info,
                    new PreparedStatementMirror(sql));
            info = preparedStatementMirrorMap.get(preparedStatement);
        }
        return info;
    }

    PreparedStatementMirror getPreparedStatementMirror(PreparedStatement preparedStatement) {
        PreparedStatementMirror info = preparedStatementMirrorMap.get(preparedStatement);
        if (info == null) {
            if (!missingSqlTextErrorLogged.getAndSet(true)) {
                // this error is only logged the first time it occurs
                logger.error("SQL TEXT WAS NOT CAPTURED BY INFORMANT.  PLEASE REPORT THIS.",
                        new Throwable());
            }
            return new PreparedStatementMirror("SQL TEXT WAS NOT CAPTURED BY INFORMANT.  PLEASE"
                    + " REPORT THIS.");
        }
        return info;
    }

    private static class StatementMirrorLazyMapValueFunction extends
            CacheLoader<Statement, StatementMirror> {

        @Override
        public StatementMirror load(Statement from) {
            return new StatementMirror();
        }
    }
}

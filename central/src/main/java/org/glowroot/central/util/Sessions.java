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
package org.glowroot.central.util;

import java.util.concurrent.ExecutionException;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

public class Sessions {

    private static final Logger logger = LoggerFactory.getLogger(Sessions.class);

    private Sessions() {}

    // do not use session.execute() because that calls getUninterruptibly() which can cause central
    // shutdown to timeout while waiting for executor service to shutdown
    public static ResultSet execute(Session session, Statement statement) throws Exception {
        try {
            return session.executeAsync(statement).get();
        } catch (ExecutionException e) {
            propagateCauseIfPossible(e);
            throw e; // unusual case (cause is null or cause is not Exception or Error)
        }
    }

    // do not use session.execute() because that calls getUninterruptibly() which can cause central
    // shutdown to timeout while waiting for executor service to shutdown
    public static ResultSet execute(Session session, String query) throws Exception {
        try {
            return session.executeAsync(query).get();
        } catch (ExecutionException e) {
            propagateCauseIfPossible(e);
            throw e; // unusual case (cause is null or cause is not Exception or Error)
        }
    }

    public static void createKeyspaceIfNotExists(Session session, String keyspace)
            throws Exception {
        execute(session, "create keyspace if not exists " + keyspace + " with replication"
                + " = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
    }

    public static void createTableWithTWCS(Session session, String createTableQuery,
            int expirationHours) throws Exception {
        // "Ideally, operators should select a compaction_window_unit and compaction_window_size
        // pair that produces approximately 20-30 windows"
        // (http://cassandra.apache.org/doc/latest/operating/compaction.html)
        int windowSizeHours = expirationHours / 24;

        // as long as gc_grace_seconds is less than TTL, then tombstones can be collected
        // immediately (https://issues.apache.org/jira/browse/CASSANDRA-4917)
        //
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long gcGraceSeconds = DAYS.toSeconds(1);
        try {
            execute(session, createTableQuery + " with compaction = { 'class' :"
                    + " 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                    + " 'compaction_window_size' : '" + windowSizeHours + "' }"
                    + " and gc_grace_seconds = " + gcGraceSeconds);
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            execute(session, createTableQuery
                    + " with compaction = { 'class' : 'DateTieredCompactionStrategy' }"
                    + " and gc_grace_seconds = " + gcGraceSeconds);
        }
    }

    public static ResultSetFuture executeAsyncWithOnFailure(Session session,
            BoundStatement boundStatement, Runnable onFailure) {
        ResultSetFuture future = session.executeAsync(boundStatement);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(future).getUninterruptibly();
                } catch (Exception e) {
                    logger.debug(e.getMessage(), e);
                    onFailure.run();
                }
            }
        }, MoreExecutors.directExecutor());
        return future;
    }

    private static void propagateCauseIfPossible(ExecutionException e) throws Exception {
        Throwable cause = e.getCause();
        if (cause instanceof DriverException) {
            // see com.datastax.driver.core.DriverThrowables.propagateCause()
            throw ((DriverException) cause).copy();
        } else if (cause instanceof Exception) {
            throw (Exception) cause;
        } else if (cause instanceof Error) {
            throw (Error) cause;
        }
    }
}

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
package org.glowroot.agent.plugin.cassandra;

import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;
import org.glowroot.agent.plugin.cassandra.ResultSetAspect.ResultSet;
import org.glowroot.agent.plugin.cassandra.ResultSetFutureAspect.ResultSetFutureMixin;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class SessionAspect {

    private static final String QUERY_TYPE = "CQL";

    private static final ConfigService configService = Agent.getConfigService("cassandra");

    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private static int stackTraceThresholdMillis;

    static {
        configService.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                Double value = configService.getDoubleProperty("stackTraceThresholdMillis").value();
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
    }

    @Shim("com.datastax.driver.core.Statement")
    public interface Statement {}

    @Shim("com.datastax.driver.core.RegularStatement")
    public interface RegularStatement extends Statement {

        @Nullable
        String getQueryString();
    }

    @Shim("com.datastax.driver.core.BoundStatement")
    public interface BoundStatement extends Statement {

        @Shim("com.datastax.driver.core.PreparedStatement preparedStatement()")
        @Nullable
        PreparedStatement glowroot$preparedStatement();
    }

    @Shim("com.datastax.driver.core.BatchStatement")
    public interface BatchStatement extends Statement {

        @Nullable
        Collection<Statement> getStatements();
    }

    @Shim("com.datastax.driver.core.PreparedStatement")
    public interface PreparedStatement {

        @Nullable
        String getQueryString();
    }

    @Pointcut(className = "com.datastax.driver.core.Session", methodName = "execute",
            methodParameterTypes = {"com.datastax.driver.core.Statement"},
            nestingGroup = "cassandra", timerName = "cql execute",
            suppressionKey = "wait-on-future")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable QueryEntry onBefore(ThreadContext context,
                @BindParameter @Nullable Object arg) {
            QueryEntryInfo queryEntryInfo = getQueryEntryInfo(arg);
            if (queryEntryInfo == null) {
                return null;
            }
            return context.startQueryEntry(QUERY_TYPE, queryEntryInfo.queryText,
                    queryEntryInfo.queryMessageSupplier, timerName);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSet resultSet,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                if (resultSet != null) {
                    resultSet.glowroot$setLastQueryEntry(queryEntry);
                }
                queryEntry.endWithLocationStackTrace(stackTraceThresholdMillis, MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "com.datastax.driver.core.Session", methodName = "prepare",
            methodParameterTypes = {"*"}, timerName = "cql prepare",
            suppressionKey = "wait-on-future")
    public static class PrepareAdvice {
        private static final TimerName timerName = Agent.getTimerName(PrepareAdvice.class);
        @OnBefore
        public static Timer onBefore(ThreadContext context) {
            return context.startTimer(timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    @Pointcut(className = "com.datastax.driver.core.Session", methodName = "executeAsync",
            methodParameterTypes = {"com.datastax.driver.core.Statement"},
            nestingGroup = "cassandra", timerName = "cql execute")
    public static class ExecuteAsyncAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAsyncAdvice.class);
        @OnBefore
        public static @Nullable AsyncQueryEntry onBefore(ThreadContext context,
                @BindParameter @Nullable Object arg) {
            QueryEntryInfo queryEntryInfo = getQueryEntryInfo(arg);
            if (queryEntryInfo == null) {
                return null;
            }
            return context.startAsyncQueryEntry(QUERY_TYPE, queryEntryInfo.queryText,
                    queryEntryInfo.queryMessageSupplier, timerName);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSetFutureMixin future,
                @BindTraveler @Nullable AsyncQueryEntry asyncQueryEntry) {
            if (asyncQueryEntry == null) {
                return;
            }
            asyncQueryEntry.stopSyncTimer();
            if (future == null) {
                asyncQueryEntry.end();
                return;
            }
            // to prevent race condition, setting async query entry before getting completed status,
            // and the converse is done when getting async query entry
            // ok if end() happens to get called twice
            future.glowroot$setAsyncQueryEntry(asyncQueryEntry);
            if (future.glowroot$isCompleted()) {
                // ResultSetFuture completed really fast, prior to @OnReturn
                Throwable exception = future.glowroot$getException();
                if (exception == null) {
                    asyncQueryEntry.end();
                } else {
                    asyncQueryEntry.endWithError(exception);
                }
                return;
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncQueryEntry asyncQueryEntry) {
            if (asyncQueryEntry != null) {
                asyncQueryEntry.stopSyncTimer();
                asyncQueryEntry.endWithError(t);
            }
        }
    }

    private static @Nullable QueryEntryInfo getQueryEntryInfo(@Nullable Object arg) {
        if (arg == null) {
            // seems nothing sensible to do here other than ignore
            return null;
        }
        String queryText;
        if (arg instanceof String) {
            queryText = (String) arg;
        } else if (arg instanceof RegularStatement) {
            queryText = nullToEmpty(((RegularStatement) arg).getQueryString());
        } else if (arg instanceof BoundStatement) {
            PreparedStatement preparedStatement =
                    ((BoundStatement) arg).glowroot$preparedStatement();
            queryText = preparedStatement == null ? ""
                    : nullToEmpty(preparedStatement.getQueryString());
        } else if (arg instanceof BatchStatement) {
            Collection<Statement> statements = ((BatchStatement) arg).getStatements();
            if (statements == null) {
                statements = new ArrayList<Statement>();
            }
            queryText = concatenate(statements);
        } else {
            return null;
        }
        return new QueryEntryInfo(queryText, QueryMessageSupplier.create("cql execution: "));
    }

    private static String concatenate(Collection<Statement> statements) {
        if (statements.isEmpty()) {
            return "[empty batch]";
        }
        StringBuilder sb = new StringBuilder("[batch] ");
        String currQuery = null;
        int currCount = 0;
        boolean first = true;
        for (Statement statement : statements) {
            String query = getQuery(statement);
            if (currQuery == null) {
                currQuery = query;
                currCount = 1;
            } else if (!query.equals(currQuery)) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                if (currCount == 1) {
                    sb.append(currQuery);
                } else {
                    sb.append(currCount + " x " + currQuery);
                }
                currQuery = query;
                currCount = 1;
            } else {
                currCount++;
            }
        }
        if (currQuery != null) {
            if (!first) {
                sb.append(", ");
            }
            if (currCount == 1) {
                sb.append(currQuery);
            } else {
                sb.append(currCount + " x " + currQuery);
            }
        }
        return sb.toString();
    }

    private static String getQuery(Statement statement) {
        if (statement instanceof RegularStatement) {
            String qs = ((RegularStatement) statement).getQueryString();
            return nullToEmpty(qs);
        } else if (statement instanceof BoundStatement) {
            PreparedStatement preparedStatement =
                    ((BoundStatement) statement).glowroot$preparedStatement();
            String qs = preparedStatement == null ? "" : preparedStatement.getQueryString();
            return nullToEmpty(qs);
        } else if (statement instanceof BatchStatement) {
            return "[nested batch statement]";
        } else {
            return "[unexpected statement type: " + statement.getClass().getName() + "]";
        }
    }

    private static String nullToEmpty(@Nullable String string) {
        return string == null ? "" : string;
    }

    private static class QueryEntryInfo {

        private final String queryText;
        private final QueryMessageSupplier queryMessageSupplier;

        private QueryEntryInfo(String queryText, QueryMessageSupplier messageSupplier) {
            this.queryText = queryText;
            this.queryMessageSupplier = messageSupplier;
        }
    }
}

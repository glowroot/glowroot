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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.LoggerFactory;

import org.glowroot.common2.repo.CassandraProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SessionTest {

    @Test
    public void readAsyncWarnIfNoRowsDoesNotWarnWhenSubsequentPageHasRows() throws Exception {
        CqlSession wrappedSession = mockCqlSession();
        Statement<?> statement = mock(Statement.class, Answers.RETURNS_SELF);

        Row row = mock(Row.class);
        AsyncResultSet secondPage = mock(AsyncResultSet.class);
        when(secondPage.currentPage()).thenReturn(List.of(row));

        AsyncResultSet firstPage = mock(AsyncResultSet.class);
        when(firstPage.currentPage()).thenReturn(Collections.emptyList());
        when(firstPage.hasMorePages()).thenReturn(true);
        when(firstPage.fetchNextPage())
                .thenReturn(CompletableFuture.completedFuture(secondPage));

        when(wrappedSession.executeAsync(any(Statement.class)))
                .thenReturn(CompletableFuture.completedFuture(firstPage));

        Logger sessionLogger = (Logger) LoggerFactory.getLogger(Session.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sessionLogger.addAppender(appender);
        try (Session session = new Session(wrappedSession, "glowroot", null, 0)) {
            AsyncResultSet result = session.readAsyncWarnIfNoRows(statement,
                            CassandraProfile.rollup, "no rows for {}", "agent1")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(result).isSameAs(secondPage);
            assertThat(appender.list).isEmpty();
        } finally {
            sessionLogger.detachAppender(appender);
        }
    }

    @Test
    public void readAsyncWarnIfNoRowsWarnsWhenAllPagesAreEmpty() throws Exception {
        CqlSession wrappedSession = mockCqlSession();
        Statement<?> statement = mock(Statement.class, Answers.RETURNS_SELF);

        AsyncResultSet secondPage = mock(AsyncResultSet.class);
        when(secondPage.currentPage()).thenReturn(Collections.emptyList());
        when(secondPage.hasMorePages()).thenReturn(false);

        AsyncResultSet firstPage = mock(AsyncResultSet.class);
        when(firstPage.currentPage()).thenReturn(Collections.emptyList());
        when(firstPage.hasMorePages()).thenReturn(true);
        when(firstPage.fetchNextPage())
                .thenReturn(CompletableFuture.completedFuture(secondPage));

        when(wrappedSession.executeAsync(any(Statement.class)))
                .thenReturn(CompletableFuture.completedFuture(firstPage));

        Logger sessionLogger = (Logger) LoggerFactory.getLogger(Session.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sessionLogger.addAppender(appender);
        try (Session session = new Session(wrappedSession, "glowroot", null, 0)) {
            AsyncResultSet result = session.readAsyncWarnIfNoRows(statement,
                            CassandraProfile.rollup, "no rows for {}", "agent1")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(result).isSameAs(secondPage);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .isEqualTo("no rows for agent1");
        } finally {
            sessionLogger.detachAppender(appender);
        }
    }

    private static CqlSession mockCqlSession() {
        CqlSession wrappedSession = mock(CqlSession.class);
        Metadata metadata = mock(Metadata.class);
        KeyspaceMetadata keyspaceMetadata = mock(KeyspaceMetadata.class);
        when(wrappedSession.getMetadata()).thenReturn(metadata);
        when(metadata.getKeyspace("glowroot")).thenReturn(Optional.of(keyspaceMetadata));
        return wrappedSession;
    }
}

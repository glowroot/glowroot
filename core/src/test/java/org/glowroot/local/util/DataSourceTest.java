/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.local.util;

import java.sql.SQLException;
import java.util.List;

import org.junit.Test;
import org.slf4j.Logger;

import org.glowroot.local.util.Schemas.Column;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class DataSourceTest {

    @Test
    public void testDebugNoArgs() throws SQLException {
        // given
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        // when
        DataSource.debug(logger, "select x from y");
        // then
        verify(logger).isDebugEnabled();
        verify(logger).debug("select x from y");
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void testDebugOneStringArg() throws SQLException {
        // given
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        // when
        DataSource.debug(logger, "select x from y where a = ?", "aaa");
        // then
        verify(logger).isDebugEnabled();
        verify(logger).debug("{} [{}]", "select x from y where a = ?", "'aaa'");
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void testDebugOneNullArg() throws SQLException {
        // given
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        // when
        DataSource.debug(logger, "select x from y where a = ?", new Object[] {null});
        // then
        verify(logger).isDebugEnabled();
        verify(logger).debug("{} [{}]", "select x from y where a = ?", "NULL");
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void testDebugOneNumberArg() throws SQLException {
        // given
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        // when
        DataSource.debug(logger, "select x from y where a = ?", 99);
        // then
        verify(logger).isDebugEnabled();
        verify(logger).debug("{} [{}]", "select x from y where a = ?", "99");
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void testDebugMultipleArgs() throws SQLException {
        // given
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        // when
        DataSource.debug(logger, "select x from y where a = ? and b = ? and c = ?", "aaa", null,
                99);
        // then
        verify(logger).isDebugEnabled();
        verify(logger).debug("{} [{}]", "select x from y where a = ? and b = ? and c = ?",
                "'aaa', NULL, 99");
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void shouldGetColumns() throws Exception {
        // given
        DataSource dataSource = new DataSource();
        dataSource.execute("create table tab (a varchar, b bigint)");
        dataSource.execute("create index tab_idx on tab (a)");
        // when
        List<Column> columns = dataSource.getColumns("tab");
        // then
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).name()).isEqualTo("a");
        assertThat(columns.get(1).name()).isEqualTo("b");
    }
}

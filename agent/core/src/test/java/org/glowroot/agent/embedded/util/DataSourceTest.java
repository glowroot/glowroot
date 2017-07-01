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
package org.glowroot.agent.embedded.util;

import java.sql.SQLException;

import org.junit.Test;
import org.slf4j.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        verify(logger, times(2)).isDebugEnabled();
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
        verify(logger, times(2)).isDebugEnabled();
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
        verify(logger, times(2)).isDebugEnabled();
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
        verify(logger, times(2)).isDebugEnabled();
        verify(logger).debug("{} [{}]", "select x from y where a = ? and b = ? and c = ?",
                "'aaa', NULL, 99");
        verifyNoMoreInteractions(logger);
    }
}

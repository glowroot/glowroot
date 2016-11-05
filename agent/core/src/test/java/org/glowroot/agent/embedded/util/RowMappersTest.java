/*
 * Copyright 2015-2016 the original author or authors.
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

import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.Test;

import org.glowroot.common.live.LiveTraceRepository.Existence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RowMappersTest {

    @Test
    public void testExists() throws SQLException {
        // given
        ResultSet resultSet = mock(ResultSet.class);
        CappedDatabase cappedDatabase = mock(CappedDatabase.class);
        RowMappers.getExistence(resultSet, 1, cappedDatabase);
        when(resultSet.getLong(1)).thenReturn(500L);
        when(cappedDatabase.isExpired(500)).thenReturn(false);
        // when
        Existence existence = RowMappers.getExistence(resultSet, 1, cappedDatabase);
        // then
        assertThat(existence).isEqualTo(Existence.YES);
    }

    @Test
    public void testNotExists() throws SQLException {
        // given
        ResultSet resultSet = mock(ResultSet.class);
        CappedDatabase cappedDatabase = mock(CappedDatabase.class);
        RowMappers.getExistence(resultSet, 1, cappedDatabase);
        when(resultSet.getLong(1)).thenReturn(0L);
        when(resultSet.wasNull()).thenReturn(true);
        // when
        Existence existence = RowMappers.getExistence(resultSet, 1, cappedDatabase);
        // then
        assertThat(existence).isEqualTo(Existence.NO);
    }

    @Test
    public void testExpired() throws SQLException {
        // given
        ResultSet resultSet = mock(ResultSet.class);
        CappedDatabase cappedDatabase = mock(CappedDatabase.class);
        RowMappers.getExistence(resultSet, 1, cappedDatabase);
        when(resultSet.getLong(1)).thenReturn(500L);
        when(cappedDatabase.isExpired(500)).thenReturn(true);
        // when
        Existence existence = RowMappers.getExistence(resultSet, 1, cappedDatabase);
        // then
        assertThat(existence).isEqualTo(Existence.EXPIRED);
    }
}

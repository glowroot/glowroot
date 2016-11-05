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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public class ResultSetCloserTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testExecuteException() throws SQLException {
        // given
        thrown.expectMessage("AAAA");
        ResultSet resultSet = mock(ResultSet.class);
        doThrow(new SQLException("AAAA")).when(resultSet).next();

        // when
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    @Test
    public void testCloseException() throws SQLException {
        // given
        thrown.expectMessage("BBBB");
        ResultSet resultSet = mock(ResultSet.class);
        doThrow(new SQLException("BBBB")).when(resultSet).close();

        // when
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    @Test
    public void testExecuteAndCloseExceptions() throws SQLException {
        // given
        thrown.expectMessage("AAAA");
        ResultSet resultSet = mock(ResultSet.class);
        doThrow(new SQLException("AAAA")).when(resultSet).next();
        doThrow(new SQLException("BBBB")).when(resultSet).close();

        // when
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }
}

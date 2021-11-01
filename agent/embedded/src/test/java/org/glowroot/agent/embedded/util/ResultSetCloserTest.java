/*
 * Copyright 2015-2018 the original author or authors.
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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public class ResultSetCloserTest {

    @Test
    public void testExecuteException() {
        Exception thrown = assertThrows(SQLException.class, () -> {
            // given
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
        });
        assertThat(thrown.getMessage()).isEqualTo("AAAA");
    }

    @Test
    public void testCloseException() {
        Exception thrown = assertThrows(SQLException.class, () -> {
            // given
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
        });
        assertThat(thrown.getMessage()).isEqualTo("BBBB");
    }

    @Test
    public void testExecuteAndCloseExceptions() {
        Exception thrown = assertThrows(SQLException.class, () -> {
            // given
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
        });
        assertThat(thrown.getMessage()).isEqualTo("AAAA");
    }
}

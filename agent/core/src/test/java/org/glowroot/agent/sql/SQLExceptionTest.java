/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.sql;

import java.util.List;

import com.google.common.collect.Lists;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SQLExceptionTest {

    @Test
    public void testNextException() {
        Throwable t1 = new Throwable();
        Throwable t2 = new Throwable();
        SQLException e1 = new SQLException(t1);
        SQLException e2 = new SQLException(t2);
        e1.setNextException(e2);

        assertThat((Exception) e1.getNextException()).isEqualTo(e2);
        assertThat((Exception) e2.getNextException()).isNull();
    }

    @Test
    public void testIterator() {
        Throwable t1 = new Throwable();
        Throwable t2 = new Throwable();
        SQLException e1 = new SQLException(t1);
        SQLException e2 = new SQLException(t2);
        e1.setNextException(e2);

        List<Throwable> list = Lists.newArrayList(e1);
        assertThat(list.get(0)).isEqualTo(e1);
        assertThat(list.get(1)).isEqualTo(t1);
        assertThat(list.get(2)).isEqualTo(e2);
        assertThat(list.get(3)).isEqualTo(t2);
    }

    @Test
    public void testIteratorWithCauseOnlyOnFirst() {
        Throwable t1 = new Throwable();
        SQLException e1 = new SQLException(t1);
        SQLException e2 = new SQLException();
        e1.setNextException(e2);

        List<Throwable> list = Lists.newArrayList(e1);
        assertThat(list.get(0)).isEqualTo(e1);
        assertThat(list.get(1)).isEqualTo(t1);
        assertThat(list.get(2)).isEqualTo(e2);
    }

    @Test
    public void testIteratorWithCauseOnlyOnSecond() {
        Throwable t2 = new Throwable();
        SQLException e1 = new SQLException();
        SQLException e2 = new SQLException(t2);
        e1.setNextException(e2);

        List<Throwable> list = Lists.newArrayList(e1);
        assertThat(list.get(0)).isEqualTo(e1);
        assertThat(list.get(1)).isEqualTo(e2);
        assertThat(list.get(2)).isEqualTo(t2);
    }

    @Test
    public void testIteratorWithNoCause() {
        SQLException e1 = new SQLException();
        SQLException e2 = new SQLException();
        e1.setNextException(e2);

        List<Throwable> list = Lists.newArrayList(e1);
        assertThat(list.get(0)).isEqualTo(e1);
        assertThat(list.get(1)).isEqualTo(e2);
    }

    @Test
    public void testCallSetNextExceptionMultipleTimesOnSameSQLException() {
        Throwable t1 = new Throwable();
        Throwable t2 = new Throwable();
        Throwable t3 = new Throwable();
        SQLException e1 = new SQLException(t1);
        SQLException e2 = new SQLException(t2);
        SQLException e3 = new SQLException(t3);
        e1.setNextException(e2);
        e1.setNextException(e3);

        List<Throwable> list = Lists.newArrayList(e1);
        assertThat(list.get(0)).isEqualTo(e1);
        assertThat(list.get(1)).isEqualTo(t1);
        assertThat(list.get(2)).isEqualTo(e2);
        assertThat(list.get(3)).isEqualTo(t2);
        assertThat(list.get(4)).isEqualTo(e3);
        assertThat(list.get(5)).isEqualTo(t3);
    }
}

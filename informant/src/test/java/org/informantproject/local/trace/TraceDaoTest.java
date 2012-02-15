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
package org.informantproject.local.trace;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import org.informantproject.trace.TraceTestData;
import org.informantproject.util.ConnectionTestProvider;
import org.informantproject.util.JdbcUtil;
import org.informantproject.util.ThreadChecker;
import org.jukito.JukitoModule;
import org.jukito.JukitoRunner;
import org.jukito.TestSingleton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(JukitoRunner.class)
public class TraceDaoTest {

    private Set<Thread> preExistingThreads;

    public static class Module extends JukitoModule {
        @Override
        protected void configureTest() {
            bind(Connection.class).toProvider(ConnectionTestProvider.class).in(TestSingleton.class);
        }
    }

    @Before
    public void before(Connection connection) throws SQLException {
        preExistingThreads = ThreadChecker.currentThreadList();
        if (JdbcUtil.tableExists("trace", connection)) {
            Statement statement = connection.createStatement();
            statement.execute("drop table trace");
            statement.close();
        }
    }

    @After
    public void after(Connection connection) throws SQLException, InterruptedException {
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        connection.close();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
    }

    @Test
    public void shouldReadTrace(TraceDao traceDao, TraceTestData traceTestData) {
        // given
        StoredTrace storedTrace = traceTestData.createTrace();
        traceDao.storeTrace(storedTrace);
        // when
        List<StoredTrace> storedTraces = traceDao.readStoredTraces(0, 0);
        // then
        assertThat(storedTraces.size(), is(1));
        StoredTrace storedTrace2 = storedTraces.get(0);
        assertThat(storedTrace2.getStartAt(), is(storedTrace.getStartAt()));
        assertThat(storedTrace2.isStuck(), is(storedTrace.isStuck()));
        assertThat(storedTrace2.getId(), is(storedTrace.getId()));
        assertThat(storedTrace2.getDuration(), is(storedTrace.getDuration()));
        assertThat(storedTrace2.isCompleted(), is(storedTrace.isCompleted()));
        assertThat(storedTrace2.getThreadNames(), is("[\"" + Thread.currentThread().getName()
                + "\"]"));
        assertThat(storedTrace2.getUsername(), is(storedTrace.getUsername()));
        // TODO verify metricData, trace and mergedStackTree
    }

    @Test
    public void shouldReadTraceWithDurationQualifier(TraceDao traceDao,
            TraceTestData traceTestData) {

        // given
        StoredTrace storedTrace = traceTestData.createTrace();
        traceDao.storeTrace(storedTrace);
        // when
        List<StoredTrace> storedTraces = traceDao.readStoredTraces(0, 0, storedTrace.getDuration(),
                storedTrace.getDuration());
        // then
        assertThat(storedTraces.size(), is(1));
    }

    @Test
    public void shouldNotReadTraceWithHighDurationQualifier(TraceDao traceDao,
            TraceTestData traceTestData) {

        // given
        StoredTrace storedTrace = traceTestData.createTrace();
        traceDao.storeTrace(storedTrace);
        // when
        List<StoredTrace> storedTraces = traceDao.readStoredTraces(0, 0,
                storedTrace.getDuration() + 1, storedTrace.getDuration() + 2);
        // then
        assertThat(storedTraces.size(), is(0));
    }

    @Test
    public void shouldNotReadTraceWithLowDurationQualifier(TraceDao traceDao,
            TraceTestData traceTestData) {

        // given
        StoredTrace storedTrace = traceTestData.createTrace();
        traceDao.storeTrace(storedTrace);
        // when
        List<StoredTrace> storedTraces = traceDao.readStoredTraces(0, 0,
                storedTrace.getDuration() - 2, storedTrace.getDuration() - 1);
        // then
        assertThat(storedTraces.size(), is(0));
    }

    @Test
    public void shouldDeletedTrace(TraceDao traceDao, TraceTestData traceTestData) {
        // given
        traceDao.storeTrace(traceTestData.createTrace());
        // when
        traceDao.deleteStoredTraces(0, 0);
        // then
        assertThat(traceDao.count(), is(0L));
    }
}

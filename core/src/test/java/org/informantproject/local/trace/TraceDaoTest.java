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

import static org.fest.assertions.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.informantproject.core.trace.TraceTestData;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSourceTestProvider;
import org.informantproject.core.util.RollingFile;
import org.informantproject.core.util.RollingFileTestProvider;
import org.informantproject.core.util.Threads;
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
            bind(DataSource.class).toProvider(DataSourceTestProvider.class).in(TestSingleton.class);
            bind(RollingFile.class).toProvider(RollingFileTestProvider.class).in(
                    TestSingleton.class);
        }
    }

    @Before
    public void before(DataSource dataSource) throws SQLException {
        preExistingThreads = Threads.currentThreadList();
        if (dataSource.tableExists("trace")) {
            dataSource.execute("drop table trace");
        }
    }

    @After
    public void after(DataSource dataSource, RollingFile rollingFile) throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        dataSource.closeAndDeleteFile();
        rollingFile.closeAndDeleteFile();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadTrace(TraceDao traceDao, TraceTestData traceTestData) {
        // given
        StoredTrace storedTrace = traceTestData.createTrace();
        traceDao.storeTrace(storedTrace);
        // when
        List<StoredTrace> storedTraces = traceDao.readStoredTraces(0, 0);
        // then
        assertThat(storedTraces).hasSize(1);
        StoredTrace storedTrace2 = storedTraces.get(0);
        assertThat(storedTrace2.getStartAt()).isEqualTo(storedTrace.getStartAt());
        assertThat(storedTrace2.isStuck()).isEqualTo(storedTrace.isStuck());
        assertThat(storedTrace2.getId()).isEqualTo(storedTrace.getId());
        assertThat(storedTrace2.getDuration()).isEqualTo(storedTrace.getDuration());
        assertThat(storedTrace2.isCompleted()).isEqualTo(storedTrace.isCompleted());
        assertThat(storedTrace2.getDescription()).isEqualTo("test description");
        assertThat(storedTrace2.getUsername()).isEqualTo(storedTrace.getUsername());
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
        assertThat(storedTraces).hasSize(1);
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
        assertThat(storedTraces).isEmpty();
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
        assertThat(storedTraces).isEmpty();
    }

    @Test
    public void shouldDeletedTrace(TraceDao traceDao, TraceTestData traceTestData) {
        // given
        traceDao.storeTrace(traceTestData.createTrace());
        // when
        traceDao.deleteStoredTraces(0, 0);
        // then
        assertThat(traceDao.count()).isEqualTo(0);
    }
}

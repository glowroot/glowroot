/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.store;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.core.snapshot.TraceSnapshot;
import io.informant.util.DaemonExecutors;
import io.informant.util.MockClock;
import io.informant.util.Threads;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceSnapshotDaoTest {

    private Collection<Thread> preExistingThreads;
    private DataSource dataSource;
    private File rollingDbFile;
    private ScheduledExecutorService scheduledExecutor;
    private RollingFile rollingFile;
    private TraceSnapshotDao snapshotDao;

    @Before
    public void before() throws SQLException, IOException {
        preExistingThreads = Threads.currentThreads();
        dataSource = new DataSource();
        if (dataSource.tableExists("trace_snapshot")) {
            dataSource.execute("drop table trace_snapshot");
        }
        rollingDbFile = new File("informant.rolling.db");
        scheduledExecutor = DaemonExecutors.newSingleThreadScheduledExecutor("Informant-Fsync");
        rollingFile = new RollingFile(rollingDbFile, 1000000, scheduledExecutor,
                Ticker.systemTicker());
        snapshotDao = new TraceSnapshotDao(dataSource, rollingFile, new MockClock());
    }

    @After
    public void after() throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        dataSource.close();
        scheduledExecutor.shutdownNow();
        rollingFile.close();
        rollingDbFile.delete();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadSnapshot() {
        // given
        TraceSnapshot snapshot = new TraceSnapshotTestData().createSnapshot();
        snapshotDao.store(snapshot);
        // when
        List<TracePoint> points = snapshotDao.readPoints(0, 0, 0, Long.MAX_VALUE, false,
                false, false, null, null, null, null, 1);
        TraceSnapshot snapshot2 = snapshotDao.readSnapshot(points.get(0).getId());
        // then
        assertThat(snapshot2.getStart()).isEqualTo(snapshot.getStart());
        assertThat(snapshot2.isStuck()).isEqualTo(snapshot.isStuck());
        assertThat(snapshot2.getId()).isEqualTo(snapshot.getId());
        assertThat(snapshot2.getDuration()).isEqualTo(snapshot.getDuration());
        assertThat(snapshot2.isCompleted()).isEqualTo(snapshot.isCompleted());
        assertThat(snapshot2.getHeadline()).isEqualTo("test headline");
        assertThat(snapshot2.getUserId()).isEqualTo(snapshot.getUserId());
        // TODO verify metricData, trace and mergedStackTree
    }

    @Test
    public void shouldReadSnapshotWithDurationQualifier() {
        // given
        TraceSnapshot snapshot = new TraceSnapshotTestData().createSnapshot();
        snapshotDao.store(snapshot);
        // when
        List<TracePoint> points = snapshotDao.readPoints(0, 0, snapshot.getDuration(),
                snapshot.getDuration(), false, false, false, null, null, null, null, 1);
        // then
        assertThat(points).hasSize(1);
    }

    @Test
    public void shouldNotReadSnapshotWithHighDurationQualifier() {
        // given
        TraceSnapshot snapshot = new TraceSnapshotTestData().createSnapshot();
        snapshotDao.store(snapshot);
        // when
        List<TracePoint> points = snapshotDao.readPoints(0, 0, snapshot.getDuration() + 1,
                snapshot.getDuration() + 2, false, false, false, null, null, null, null, 1);
        // then
        assertThat(points).isEmpty();
    }

    @Test
    public void shouldNotReadSnapshotWithLowDurationQualifier() {
        // given
        TraceSnapshot snapshot = new TraceSnapshotTestData().createSnapshot();
        snapshotDao.store(snapshot);
        // when
        List<TracePoint> points = snapshotDao.readPoints(0, 0, snapshot.getDuration() - 2,
                snapshot.getDuration() - 1, false, false, false, null, null, null, null, 1);
        // then
        assertThat(points).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() {
        // given
        snapshotDao.store(new TraceSnapshotTestData().createSnapshot());
        // when
        snapshotDao.deleteSnapshotsBefore(0);
        // then
        assertThat(snapshotDao.count()).isEqualTo(0);
    }
}

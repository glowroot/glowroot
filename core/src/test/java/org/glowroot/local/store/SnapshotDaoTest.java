/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.store;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Ticker;
import com.google.common.io.CharSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.Snapshot;
import org.glowroot.local.store.TracePointQuery.StringComparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SnapshotDaoTest {

    private DataSource dataSource;
    private File cappedFile;
    private ScheduledExecutorService scheduledExecutor;
    private CappedDatabase cappedDatabase;
    private SnapshotDao snapshotDao;

    @Before
    public void beforeEachTest() throws SQLException, IOException {
        dataSource = new DataSource();
        if (dataSource.tableExists("snapshot")) {
            dataSource.execute("drop table snapshot");
        }
        cappedFile = new File("glowroot.capped.db");
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, scheduledExecutor,
                Ticker.systemTicker());
        snapshotDao = new SnapshotDao(dataSource, cappedDatabase);
    }

    @After
    public void afterEachTest() throws Exception {
        scheduledExecutor.shutdownNow();
        dataSource.close();
        cappedDatabase.close();
        cappedFile.delete();
    }

    @Test
    public void shouldReadSnapshot() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, false, false, false,
                null, null, null, null, null, null, null, null, null, null, null, 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        Snapshot snapshot2 = snapshotDao.readSnapshot(points.get(0).getId());
        // then
        assertThat(snapshot2.getId()).isEqualTo(snapshot.getId());
        assertThat(snapshot2.isStuck()).isEqualTo(snapshot.isStuck());
        assertThat(snapshot2.getStartTime()).isEqualTo(snapshot.getStartTime());
        assertThat(snapshot2.getCaptureTime()).isEqualTo(snapshot.getCaptureTime());
        assertThat(snapshot2.getDuration()).isEqualTo(snapshot.getDuration());
        assertThat(snapshot2.getHeadline()).isEqualTo("test headline");
        assertThat(snapshot2.getUser()).isEqualTo(snapshot.getUser());
    }

    @Test
    public void shouldReadSnapshotWithDurationQualifier() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, snapshot.getDuration(),
                snapshot.getDuration(), false, false, false, null, null, null, null, null, null,
                null, null, null, null, null, 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).hasSize(1);
    }

    @Test
    public void shouldNotReadSnapshotWithHighDurationQualifier() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 0, snapshot.getDuration() + 1,
                snapshot.getDuration() + 2, false, false, false, null, null, null, null, null,
                null, null, null, null, null, null, 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).isEmpty();
    }

    @Test
    public void shouldNotReadSnapshotWithLowDurationQualifier() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 0, snapshot.getDuration() - 2,
                snapshot.getDuration() - 1, false, false, false, null, null, null, null, null,
                null, null, null, null, null, null, 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).isEmpty();
    }

    @Test
    public void shouldReadSnapshotWithAttributeQualifier() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, false, false, false,
                null, null, null, null, null, null, null, null, "abc", StringComparator.EQUALS,
                "xyz", 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).hasSize(1);
    }

    @Test
    public void shouldReadSnapshotWithAttributeQualifier2() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, false, false, false,
                null, null, null, null, null, null, null, null, "abc", null, null, 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).hasSize(1);
    }

    @Test
    public void shouldReadSnapshotWithAttributeQualifier3() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, false, false, false,
                null, null, null, null, null, null, null, null, null, StringComparator.EQUALS,
                "xyz", 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).hasSize(1);
    }

    @Test
    public void shouldNotReadSnapshotWithNonMatchingAttributeQualifier() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, false, false, false,
                null, null, null, null, null, null, null, null, "abc", StringComparator.EQUALS,
                "abc", 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).isEmpty();
    }

    @Test
    public void shouldNotReadSnapshotWithNonMatchingAttributeQualifier2() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, false, false, false,
                null, null, null, null, null, null, null, null, null, StringComparator.EQUALS,
                "xyz1", 1);
        // when
        List<TracePoint> points = snapshotDao.readPoints(query);
        // then
        assertThat(points).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() {
        // given
        Snapshot snapshot = SnapshotTestData.createSnapshot();
        CharSource spans = SnapshotTestData.createSpans();
        snapshotDao.store(snapshot, spans, null, null);
        // when
        snapshotDao.deleteSnapshotsBefore(100);
        // then
        assertThat(snapshotDao.count()).isEqualTo(0);
    }
}

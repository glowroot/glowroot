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
package org.informantproject.local.metric;

import static org.fest.assertions.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.informantproject.core.metric.MetricValue;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSourceTestProvider;
import org.informantproject.core.util.MockClock;
import org.informantproject.core.util.UnitTests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricDaoTest {

    private Collection<Thread> preExistingThreads;
    private DataSource dataSource;
    private MockClock clock;
    private MetricDao metricDao;

    @Before
    public void before() throws SQLException {
        preExistingThreads = UnitTests.currentThreads();
        dataSource = new DataSourceTestProvider().get();
        if (dataSource.tableExists("metric_point")) {
            dataSource.execute("drop table metric_point");
        }
        clock = new MockClock();
        metricDao = new MetricDao(dataSource, clock);
    }

    @After
    public void after() throws Exception {
        UnitTests.preShutdownCheck(preExistingThreads);
        dataSource.closeAndDeleteFile();
        UnitTests.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadEmptyMetricPoints() {
        // given
        List<String> metricIds = Arrays.asList("cpu", "mem", "io");
        // when
        Map<String, List<Point>> metricPoints = metricDao.readMetricPoints(metricIds, 0,
                System.currentTimeMillis());
        // then
        assertThat(metricPoints).isEmpty();
    }

    @Test
    public void shouldStoreMetricPoints() {
        // given
        MetricValue mv1 = new MetricValue("cpu", 0.5);
        MetricValue mv2 = new MetricValue("mem", 10);
        MetricValue mv3 = new MetricValue("cpu", 0.75);
        // when
        long start = clock.updateTime();
        metricDao.storeMetricValues(Arrays.asList(mv1, mv2));
        clock.forwardTime(1000);
        metricDao.storeMetricValues(Arrays.asList(mv3));
        // then
        Map<String, List<Point>> storedMetricPoints = metricDao.readMetricPoints(
                Arrays.asList("cpu", "mem"), start, start + 1000);
        assertThat(storedMetricPoints.get("cpu")).containsExactly(Point.from(start, 0.5),
                Point.from(start + 1000, 0.75));
        assertThat(storedMetricPoints.get("mem")).containsExactly(Point.from(start, 10));
        assertThat(storedMetricPoints).hasSize(2);
    }

    @Test
    public void shouldOnlyReadMetricPointsInGivenTimeBand() {
        // given
        MetricValue mv1 = new MetricValue("cpu", 0.5);
        MetricValue mv2 = new MetricValue("cpu", 0.75);
        MetricValue mv3 = new MetricValue("mem", 10);
        MetricValue mv4 = new MetricValue("mem", 11);
        long start = clock.updateTime();
        metricDao.storeMetricValues(Arrays.asList(mv1));
        clock.forwardTime(1);
        metricDao.storeMetricValues(Arrays.asList(mv2));
        clock.forwardTime(1000);
        metricDao.storeMetricValues(Arrays.asList(mv3));
        clock.forwardTime(1);
        metricDao.storeMetricValues(Arrays.asList(mv4));
        // when
        Map<String, List<Point>> storedMetricPoints = metricDao.readMetricPoints(
                Arrays.asList("cpu", "mem"), start + 1, start + 1001);
        // then
        assertThat(storedMetricPoints).hasSize(2);
        assertThat(storedMetricPoints.get("cpu")).containsExactly(Point.from(start + 1, 0.75));
        assertThat(storedMetricPoints.get("mem")).containsExactly(Point.from(start + 1001, 10));
    }

    @Test
    public void shouldOnlyReadMetricPointsForGivenMetricIds() {
        // given
        MetricValue mv1 = new MetricValue("cpu", 0.5);
        MetricValue mv2 = new MetricValue("mem", 0.75);
        MetricValue mv3 = new MetricValue("io", 0.5);
        MetricValue mv4 = new MetricValue("net", 0.5);
        // when
        long now = clock.updateTime();
        metricDao.storeMetricValues(Arrays.asList(mv1, mv2, mv3, mv4));
        // then
        Map<String, List<Point>> storedMetricPoints = metricDao.readMetricPoints(
                Arrays.asList("io", "net"), now, now);
        assertThat(storedMetricPoints).hasSize(2);
        assertThat(storedMetricPoints.get("io")).containsExactly(Point.from(now, 0.5));
        assertThat(storedMetricPoints.get("net")).containsExactly(Point.from(now, 0.5));
    }
}

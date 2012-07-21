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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.informantproject.core.metric.MetricValue;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSourceTestProvider;
import org.informantproject.core.util.MockClock;
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
public class MetricDaoTest {

    private Set<Thread> preExistingThreads;

    public static class Module extends JukitoModule {
        @Override
        protected void configureTest() {
            bind(DataSource.class).toProvider(DataSourceTestProvider.class).in(TestSingleton.class);
            bind(MockClock.class).in(TestSingleton.class);
            bind(Clock.class).to(MockClock.class);
        }
    }

    @Before
    public void before(DataSource dataSource) throws SQLException {
        preExistingThreads = Threads.currentThreadList();
        if (dataSource.tableExists("metric_point")) {
            dataSource.execute("drop table metric_point");
        }
    }

    @After
    public void after(DataSource dataSource) throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        dataSource.closeAndDeleteFile();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadEmptyMetricPoints(MetricDao metricDao) {
        // given
        List<String> metricIds = Arrays.asList("cpu", "mem", "io");
        // when
        Map<String, List<Point>> metricPoints = metricDao.readMetricPoints(metricIds, 0,
                System.currentTimeMillis());
        // then
        assertThat(metricPoints).isEmpty();
    }

    @Test
    public void shouldStoreMetricPoints(MetricDao metricDao, MockClock clock) {
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
        assertThat(storedMetricPoints.get("cpu")).containsExactly(new Point(start, 0.5),
                new Point(start + 1000, 0.75));
        assertThat(storedMetricPoints.get("mem")).containsExactly(new Point(start, 10));
        assertThat(storedMetricPoints).hasSize(2);
    }

    @Test
    public void shouldOnlyReadMetricPointsInGivenTimeBand(MetricDao metricDao, MockClock clock) {
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
        assertThat(storedMetricPoints.get("cpu")).containsExactly(new Point(start + 1, 0.75));
        assertThat(storedMetricPoints.get("mem")).containsExactly(new Point(start + 1001, 10));
    }

    @Test
    public void shouldOnlyReadMetricPointsForGivenMetricIds(MetricDao metricDao, MockClock clock) {
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
        assertThat(storedMetricPoints.get("io")).containsExactly(new Point(now, 0.5));
        assertThat(storedMetricPoints.get("net")).containsExactly(new Point(now, 0.5));
    }
}

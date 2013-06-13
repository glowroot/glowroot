/**
 * Copyright 2013 the original author or authors.
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

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.google.monitoring.runtime.instrumentation.common.com.google.common.collect.Maps;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.informant.collector.Aggregate;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AggregateDaoTest {

    private DataSource dataSource;
    private AggregateDao aggregateDao;

    @Before
    public void before() throws SQLException, IOException {
        dataSource = new DataSource();
        if (dataSource.tableExists("snapshot")) {
            dataSource.execute("drop table snapshot");
        }
        aggregateDao = new AggregateDao(dataSource);
    }

    @After
    public void after() throws Exception {
        dataSource.close();
    }

    @Test
    public void shouldReadAggregates() {
        // given
        Aggregate aggregate = new Aggregate(1000, 10);
        Map<String, Aggregate> groupAggregates = Maps.newHashMap();
        groupAggregates.put("one", new Aggregate(100, 1));
        groupAggregates.put("two", new Aggregate(300, 2));
        groupAggregates.put("seven", new Aggregate(1400, 7));
        aggregateDao.store(10000, aggregate, groupAggregates);
        aggregateDao.store(20000, aggregate, groupAggregates);
        // when
        List<AggregatePoint> aggregateIntervals = aggregateDao.readAggregates(0, 100000);
        List<GroupingAggregate> groupingAggregates =
                aggregateDao.readGroupingAggregates(0, 100000, 10);
        // then
        assertThat(aggregateIntervals).hasSize(2);
        assertThat(groupingAggregates).hasSize(3);
        assertThat(groupingAggregates.get(0).getGrouping()).isEqualTo("seven");
        assertThat(groupingAggregates.get(0).getDurationTotal()).isEqualTo(2800);
        assertThat(groupingAggregates.get(0).getTraceCount()).isEqualTo(14);
        assertThat(groupingAggregates.get(1).getGrouping()).isEqualTo("two");
        assertThat(groupingAggregates.get(1).getDurationTotal()).isEqualTo(600);
        assertThat(groupingAggregates.get(1).getTraceCount()).isEqualTo(4);
        assertThat(groupingAggregates.get(2).getGrouping()).isEqualTo("one");
        assertThat(groupingAggregates.get(2).getDurationTotal()).isEqualTo(200);
        assertThat(groupingAggregates.get(2).getTraceCount()).isEqualTo(2);
    }
}

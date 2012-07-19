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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.informantproject.core.metric.MetricValue;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.BatchPreparedStatementSetter;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.ResultSetExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading metric data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class MetricDao {

    private static final Logger logger = LoggerFactory.getLogger(MetricDao.class);

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new Column("metric_id", Types.VARCHAR),
            new Column("captured_at", Types.BIGINT),
            new Column("value", Types.DOUBLE));

    private final DataSource dataSource;
    private final Clock clock;

    private final LoadingCache<Integer, String> selectSqls = CacheBuilder.newBuilder().build(
            new CacheLoader<Integer, String>() {
                @Override
                public String load(Integer nMetricIds) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("select metric_id, captured_at, value from metric_point");
                    sql.append(" where captured_at >= ? and captured_at <= ?");
                    sql.append(" and metric_id in (");
                    for (int i = 0; i < nMetricIds; i++) {
                        if (i > 0) {
                            sql.append(", ");
                        }
                        sql.append("?");
                    }
                    sql.append(")");
                    return sql.toString();
                }
            });

    private final boolean valid;

    @Inject
    public MetricDao(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;

        boolean localValid;
        try {
            if (!dataSource.tableExists("metric_point")) {
                dataSource.createTable("metric_point", columns);
            } else if (dataSource.tableNeedsUpgrade("metric_point", columns)) {
                logger.warn("upgrading metric_point table schema, which unfortunately at this point"
                        + " just means dropping and re-create the table (losing existing data)");
                dataSource.execute("drop table metric_point");
                dataSource.createTable("metric_point", columns);
                logger.warn("the schema for the metric_point table was outdated so it was dropped"
                        + " and re-created, existing metric_point data was lost");
            }
            localValid = true;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }
        valid = localValid;
    }

    public Map<String, List<Point>> readMetricPoints(List<String> metricIds, long from, long to) {
        if (!valid) {
            return ImmutableMap.of();
        }
        Object[] args = new Object[2 + metricIds.size()];
        args[0] = from;
        args[1] = to;
        for (int i = 0; i < metricIds.size(); i++) {
            args[2 + i] = metricIds.get(i);
        }
        try {
            return dataSource.query(selectSqls.getUnchecked(metricIds.size()), args,
                    new ResultSetExtractor<Map<String, List<Point>>>() {
                        public Map<String, List<Point>> extractData(ResultSet resultSet)
                                throws SQLException {
                            Map<String, List<Point>> map = Maps.newHashMap();
                            while (resultSet.next()) {
                                String metricId = resultSet.getString(1);
                                long capturedAt = resultSet.getLong(2);
                                double value = resultSet.getDouble(3);
                                List<Point> metricPoints = map.get(metricId);
                                if (metricPoints == null) {
                                    metricPoints = Lists.newArrayList();
                                    map.put(metricId, metricPoints);
                                }
                                metricPoints.add(new Point(capturedAt, value));
                            }
                            return map;
                        }
                    });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableMap.of();
        }
    }

    public long count() {
        if (!valid) {
            return 0;
        }
        try {
            return dataSource.queryForLong("select count(*) from metric_point");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return 0;
        }
    }

    // TODO ensure that any call to read end=currentTimeMillis
    // and any subsequent call with start=<previous currentTimeMillis>
    // will not miss any metrics
    void storeMetricValues(final List<MetricValue> metricValues) {
        if (!valid) {
            return;
        }
        try {
            dataSource.batchUpdate("insert into metric_point (captured_at, metric_id, value)"
                    + " values (?, ?, ?)", new BatchPreparedStatementSetter() {
                public void setValues(PreparedStatement preparedStatement, int i)
                        throws SQLException {
                    preparedStatement.setLong(1, clock.currentTimeMillis());
                    preparedStatement.setString(2, metricValues.get(i).getMetricId());
                    preparedStatement.setDouble(3, metricValues.get(i).getValue());
                }
                public int getBatchSize() {
                    return metricValues.size();
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }
}

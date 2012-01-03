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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.informantproject.metric.MetricValue;
import org.informantproject.util.Clock;
import org.informantproject.util.JdbcUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final Connection connection;
    private final Clock clock;

    private final PreparedStatement insertPreparedStatement;
    // select prepared statements are indexed by the number of metricIds to use in the qualifier
    private final Map<Integer, PreparedStatement> selectPreparedStatements =
            new ConcurrentHashMap<Integer, PreparedStatement>();
    private final PreparedStatement countPreparedStatement;

    private final boolean valid;

    @Inject
    public MetricDao(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;

        PreparedStatement insertPS = null;
        PreparedStatement countPS = null;
        boolean localValid;
        try {
            if (!JdbcUtil.tableExists("metric_point", connection)) {
                createTable(connection);
            }
            insertPS = connection.prepareStatement("insert into metric_point"
                    + " (capturedAt, metricId, value) values (?, ?, ?)");
            countPS = connection.prepareStatement("select count(*) from metric_point");
            localValid = true;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }
        insertPreparedStatement = insertPS;
        countPreparedStatement = countPS;
        valid = localValid;
    }

    public Map<String, List<Point>> readMetricPoints(List<String> metricIds, long from, long to) {
        if (!valid) {
            return Collections.emptyMap();
        }
        synchronized (connection) {
            try {
                PreparedStatement selectPreparedStatement = getSelectPreparedStatement(metricIds
                        .size());
                selectPreparedStatement.setLong(1, from);
                selectPreparedStatement.setLong(2, to);
                for (int i = 0; i < metricIds.size(); i++) {
                    selectPreparedStatement.setString(3 + i, metricIds.get(i));
                }
                ResultSet resultSet = selectPreparedStatement.executeQuery();
                try {
                    Map<String, List<Point>> map = new HashMap<String, List<Point>>();
                    while (resultSet.next()) {
                        String metricId = resultSet.getString(1);
                        long capturedAt = resultSet.getLong(2);
                        double value = resultSet.getDouble(3);
                        List<Point> metricPoints = map.get(metricId);
                        if (metricPoints == null) {
                            metricPoints = new ArrayList<Point>();
                            map.put(metricId, metricPoints);
                        }
                        metricPoints.add(new Point(capturedAt, value));
                    }
                    return map;
                } finally {
                    resultSet.close();
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return Collections.emptyMap();
            }
        }
    }

    public long count() {
        if (!valid) {
            return 0;
        }
        synchronized (connection) {
            try {
                ResultSet resultSet = countPreparedStatement.executeQuery();
                try {
                    resultSet.next();
                    return resultSet.getLong(1);
                } finally {
                    resultSet.close();
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return 0;
            }
        }
    }

    void storeMetricValue(MetricValue metricValue) {
        storeMetricValues(Collections.singleton(metricValue));
    }

    // TODO ensure that any call to read end=currentTimeMillis
    // and any subsequent call with start=<previous currentTimeMillis>
    // will not miss any metrics
    void storeMetricValues(Iterable<MetricValue> metricValues) {
        if (!valid) {
            return;
        }
        synchronized (connection) {
            try {
                // batch them up
                for (MetricValue metricValue : metricValues) {
                    insertPreparedStatement.setLong(1, clock.currentTimeMillis());
                    insertPreparedStatement.setString(2, metricValue.getMetricId());
                    insertPreparedStatement.setDouble(3, metricValue.getValue());
                    insertPreparedStatement.addBatch();
                }
                insertPreparedStatement.executeBatch();
                // don't close prepared statement
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private PreparedStatement getSelectPreparedStatement(int nMetricIds) throws SQLException {
        PreparedStatement selectPreparedStatement = selectPreparedStatements.get(nMetricIds);
        if (selectPreparedStatement == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("select metricId, capturedAt, value from metric_point");
            sb.append(" where capturedAt >= ? and capturedAt <= ?");
            sb.append(" and metricId in (");
            for (int i = 0; i < nMetricIds; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("?");
            }
            sb.append(")");
            selectPreparedStatement = connection.prepareStatement(sb.toString());
            selectPreparedStatements.put(nMetricIds, selectPreparedStatement);
        }
        return selectPreparedStatement;
    }

    private static void createTable(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("create table metric_point"
                    + " (metricId varchar, capturedAt bigint, value double)");
        } finally {
            statement.close();
        }
    }
}

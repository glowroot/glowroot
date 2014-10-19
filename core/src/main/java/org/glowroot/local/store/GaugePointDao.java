/*
 * Copyright 2014 the original author or authors.
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.GaugePoint;
import org.glowroot.collector.GaugePointRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class GaugePointDao implements GaugePointRepository {

    private static final Logger logger = LoggerFactory.getLogger(GaugePointDao.class);

    private static final ImmutableList<Column> gaugePointColumns = ImmutableList.of(
            new Column("gauge_name", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT),
            new Column("value", Types.DOUBLE));

    private static final ImmutableList<Index> gaugePointIndexes =
            ImmutableList.of(new Index("gauge_point_idx",
                    ImmutableList.of("gauge_name", "capture_time", "value")));

    private final DataSource dataSource;

    GaugePointDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("gauge_point", gaugePointColumns);
        dataSource.syncIndexes("gauge_point", gaugePointIndexes);
    }

    @Override
    public void store(final List<GaugePoint> gaugePoints) {
        try {
            dataSource.batchUpdate("insert into gauge_point (gauge_name, capture_time, value)"
                    + " values (?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement) throws SQLException {
                    for (GaugePoint gaugePoint : gaugePoints) {
                        preparedStatement.setString(1, gaugePoint.getGaugeName());
                        preparedStatement.setLong(2, gaugePoint.getCaptureTime());
                        preparedStatement.setDouble(3, gaugePoint.getValue());
                        preparedStatement.addBatch();
                    }
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public ImmutableList<GaugePoint> readGaugePoints(String gaugeName, long captureTimeFrom,
            long captureTimeTo) {
        try {
            return dataSource.query("select gauge_name, capture_time, value from gauge_point"
                    + " where gauge_name = ? and capture_time >= ? and capture_time <= ?"
                    + " order by capture_time", new GaugePointRowMapper(), gaugeName,
                    captureTimeFrom, captureTimeTo);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    public void deleteAll() {
        try {
            dataSource.execute("truncate table gauge_point");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    // TODO delete 100 at a time similar to SnapshotDao.deleteBefore()
    void deleteBefore(long captureTime) {
        try {
            dataSource.update("delete from gauge_point where capture_time < ?", captureTime);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @ThreadSafe
    private static class GaugePointRowMapper implements RowMapper<GaugePoint> {
        @Override
        public GaugePoint mapRow(ResultSet resultSet) throws SQLException {
            String gaugeName = resultSet.getString(1);
            if (gaugeName == null) {
                // gauge_name should never be null
                // TODO provide better fallback here
                throw new SQLException("Found null gauge_name in gauge_point");
            }
            long captureTime = resultSet.getLong(2);
            double value = resultSet.getDouble(3);
            return new GaugePoint(gaugeName, captureTime, value);
        }
    }
}

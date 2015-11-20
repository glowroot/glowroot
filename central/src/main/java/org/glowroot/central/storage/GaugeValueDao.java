/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.central.storage;

import java.util.Date;
import java.util.List;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class GaugeValueDao implements GaugeValueRepository {

    private final Session session;
    private final ServerDao serverDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertValuePS;
    private final ImmutableList<PreparedStatement> insertNamePS;

    public GaugeValueDao(Session session, ServerDao serverDao) {
        this.session = session;
        this.serverDao = serverDao;

        // name already has "[counter]" suffix when it is a counter
        session.execute("create table if not exists gauge_value_rollup_0 (server_rollup varchar,"
                + " gauge_name varchar, capture_time timestamp, value double, weight bigint,"
                + " primary key ((server_rollup, gauge_name), capture_time))");

        // TTL on gauge_name table needs to be max(TTL) of gauge_value_rollup_*
        session.execute("create table if not exists gauge_name (server_rollup varchar,"
                + " gauge_name varchar, primary key (server_rollup, gauge_name))");

        List<PreparedStatement> insertValuePS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertValuePS.add(session.prepare("insert into gauge_value_rollup_" + castUntainted(i)
                    + "(server_rollup, gauge_name, capture_time, value, weight)"
                    + " values (?, ?, ?, ?, ?)"));
        }
        this.insertValuePS = ImmutableList.copyOf(insertValuePS);

        List<PreparedStatement> insertNamePS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertNamePS.add(session.prepare("insert into gauge_name(server_rollup, gauge_name)"
                    + " values (?, ?)"));
        }
        this.insertNamePS = ImmutableList.copyOf(insertNamePS);
    }

    @Override
    public void store(String serverId, List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        BatchStatement batchStatement = new BatchStatement();
        for (GaugeValue gaugeValue : gaugeValues) {
            BoundStatement boundStatement = insertValuePS.get(0).bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, gaugeValue.getGaugeName());
            boundStatement.setTimestamp(2, new Date(gaugeValue.getCaptureTime()));
            boundStatement.setDouble(3, gaugeValue.getValue());
            long weight = gaugeValue.getIntervalNanos();
            if (weight == 0) {
                // this is for non-counter gauges
                weight = 1;
            }
            boundStatement.setLong(4, weight);
            batchStatement.add(boundStatement);

            boundStatement = insertNamePS.get(0).bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, gaugeValue.getGaugeName());
            batchStatement.add(boundStatement);
        }
        serverDao.updateLastCaptureTime(serverId, true);
        session.execute(batchStatement);
    }

    @Override
    public List<Gauge> getGauges(String serverRollup) throws Exception {
        ResultSet results = session
                .execute("select gauge_name from gauge_name where server_rollup = ?", serverRollup);
        List<Gauge> gauges = Lists.newArrayList();
        for (Row row : results) {
            gauges.add(Gauges.getGauge(checkNotNull(row.getString(0))));
        }
        return gauges;
    }

    @Override
    public List<GaugeValue> readGaugeValues(String serverRollup, String gaugeName,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        // FIXME
        rollupLevel = 0;
        ResultSet results = session.execute("select capture_time, value from gauge_value_rollup_"
                + castUntainted(rollupLevel) + " where server_rollup = ? and gauge_name = ?",
                serverRollup, gaugeName);
        List<GaugeValue> gaugeValues = Lists.newArrayList();
        for (Row row : results) {
            gaugeValues.add(GaugeValue.newBuilder()
                    .setCaptureTime(checkNotNull(row.getTimestamp(0)).getTime())
                    .setValue(row.getDouble(1))
                    .build());
        }
        return gaugeValues;
    }

    @Override
    public List<GaugeValue> readManuallyRolledUpGaugeValues(String serverRollup, long from, long to,
            String gaugeName, int rollupLevel, long liveCaptureTime) throws Exception {
        return ImmutableList.of();
    }

    @Override
    public void deleteAll(String serverRollup) throws Exception {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }
}

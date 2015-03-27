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
package org.glowroot.local.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GaugeConfig;
import org.glowroot.config.GaugeConfig.MBeanAttribute;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;

import static java.util.concurrent.TimeUnit.HOURS;

public class GaugeDao {

    private static Logger logger = LoggerFactory.getLogger(GaugeDao.class);

    private static final ImmutableList<Column> gaugeColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("id", Types.BIGINT).withIdentity(true),
            ImmutableColumn.of("name", Types.VARCHAR),
            ImmutableColumn.of("ever_increasing", Types.BOOLEAN));

    private static final ImmutableList<Index> gaugeIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("gauge_idx", ImmutableList.of("name")));

    // expire after 1 hour to avoid retaining deleted gauge configs indefinitely
    private final Cache<String, Long> gaugeIds =
            CacheBuilder.newBuilder().expireAfterAccess(1, HOURS).build();

    private final DataSource dataSource;

    static GaugeDao create(final ConfigService configService, DataSource dataSource)
            throws SQLException {
        final GaugeDao gaugeDao = new GaugeDao(dataSource);
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                try {
                    gaugeDao.updateGauges(configService.getGaugeConfigs());
                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        gaugeDao.updateGauges(configService.getGaugeConfigs());
        return gaugeDao;
    }

    private GaugeDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("gauge", gaugeColumns);
        dataSource.syncIndexes("gauge", gaugeIndexes);
    }

    @Nullable
    Long getGaugeId(String gaugeName) throws SQLException {
        Long gaugeId = gaugeIds.getIfPresent(gaugeName);
        if (gaugeId != null) {
            return gaugeId;
        }
        gaugeId = dataSource.query("select id from gauge where name = ?", new LongExtractor(),
                gaugeName);
        if (gaugeId != null) {
            gaugeIds.put(gaugeName, gaugeId);
        }
        return gaugeId;
    }

    private synchronized void updateGauges(List<GaugeConfig> gaugeConfigs) throws SQLException {
        for (GaugeConfig gaugeConfig : gaugeConfigs) {
            for (MBeanAttribute mbeanAttribute : gaugeConfig.mbeanAttributes()) {
                String gaugeName = gaugeConfig.mbeanObjectName() + ',' + mbeanAttribute.name();
                Boolean everIncreasing = dataSource.query("select ever_increasing from gauge"
                        + " where name = ?", new BooleanExtractor(), gaugeName);
                if (everIncreasing == null) {
                    dataSource.update("insert into gauge (name, ever_increasing) values (?, ?)",
                            gaugeName, mbeanAttribute.everIncreasing());
                } else if (everIncreasing != mbeanAttribute.everIncreasing()) {
                    dataSource.update("update gauge set ever_increasing = ? where name = ?",
                            mbeanAttribute.everIncreasing(), gaugeName);
                }
            }
        }
    }

    private static class LongExtractor implements ResultSetExtractor</*@Nullable*/Long> {
        @Override
        public @Nullable Long extractData(ResultSet resultSet) throws Exception {
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
            return null;
        }
    }

    private static class BooleanExtractor implements ResultSetExtractor</*@Nullable*/Boolean> {
        @Override
        public @Nullable Boolean extractData(ResultSet resultSet) throws Exception {
            if (resultSet.next()) {
                return resultSet.getBoolean(1);
            }
            return null;
        }
    }
}

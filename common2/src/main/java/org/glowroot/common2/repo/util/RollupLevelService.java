/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.common2.repo.util;

import java.util.List;

import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.StorageConfig;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static java.util.concurrent.TimeUnit.HOURS;

public class RollupLevelService {

    private final ConfigRepository configRepository;
    private final Clock clock;

    public RollupLevelService(ConfigRepository configRepository, Clock clock) {
        this.configRepository = configRepository;
        this.clock = clock;
    }

    public int getRollupLevelForView(long from, long to, DataKind dataKind) throws Exception {
        long millis = to - from;
        long timeAgoMillis = clock.currentTimeMillis() - from;
        List<Integer> rollupExpirationHours = getRollupExpirationHours(dataKind);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            RollupConfig nextRollupConfig = rollupConfigs.get(i + 1);
            int expirationHours = rollupExpirationHours.get(i);
            if (millis < nextRollupConfig.viewThresholdMillis()
                    && (expirationHours == 0 || HOURS.toMillis(expirationHours) > timeAgoMillis)) {
                return i;
            }
        }
        return rollupConfigs.size() - 1;
    }

    public int getRollupLevelForReport(long from, DataKind dataKind) throws Exception {
        long timeAgoMillis = clock.currentTimeMillis() - from;
        List<Integer> rollupExpirationHours = getRollupExpirationHours(dataKind);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            int expirationHours = rollupExpirationHours.get(i);
            if (expirationHours == 0 || HOURS.toMillis(expirationHours) > timeAgoMillis) {
                return i;
            }
        }
        return rollupConfigs.size() - 1;
    }

    public int getGaugeRollupLevelForView(long from, long to, boolean agentRollup)
            throws Exception {
        long millis = to - from;
        long timeAgoMillis = clock.currentTimeMillis() - from;
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        // gauge point rollup level 0 shares rollup level 1's expiration
        long viewThresholdMillis = rollupConfigs.get(0).viewThresholdMillis();
        int expirationHours = rollupExpirationHours.get(0);
        if (millis < viewThresholdMillis * ConfigRepository.GAUGE_VIEW_THRESHOLD_MULTIPLIER
                && (expirationHours == 0 || HOURS.toMillis(expirationHours) > timeAgoMillis)) {
            // agent rollups from children do not have level-0 data
            return agentRollup ? 1 : 0;
        }
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            viewThresholdMillis = rollupConfigs.get(i + 1).viewThresholdMillis();
            expirationHours = rollupExpirationHours.get(i);
            if (millis < viewThresholdMillis * ConfigRepository.GAUGE_VIEW_THRESHOLD_MULTIPLIER
                    && (expirationHours == 0 || HOURS.toMillis(expirationHours) > timeAgoMillis)) {
                return i + 1;
            }
        }
        return rollupConfigs.size();
    }

    public int getGaugeRollupLevelForReport(long from) throws Exception {
        long timeAgoMillis = clock.currentTimeMillis() - from;
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        // gauge point rollup level 0 shares rollup level 1's expiration
        int expirationHours = rollupExpirationHours.get(0);
        if (expirationHours == 0 || HOURS.toMillis(expirationHours) > timeAgoMillis) {
            return 0;
        }
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            expirationHours = rollupExpirationHours.get(i);
            if (expirationHours == 0 || HOURS.toMillis(expirationHours) > timeAgoMillis) {
                return i + 1;
            }
        }
        return rollupConfigs.size();
    }

    private List<Integer> getRollupExpirationHours(DataKind dataKind) throws Exception {
        StorageConfig storageConfig = configRepository.getStorageConfig();
        switch (dataKind) {
            case GENERAL:
                return storageConfig.rollupExpirationHours();
            case QUERY:
                return storageConfig.queryAndServiceCallRollupExpirationHours();
            case SERVICE_CALL:
                return storageConfig.queryAndServiceCallRollupExpirationHours();
            case PROFILE:
                return storageConfig.profileRollupExpirationHours();
            default:
                throw new Exception("Unexpected data kind: " + dataKind);
        }
    }

    public static long getSafeRollupTime(long safeCurrentTime, long intervalMillis) {
        return getFloorRollupTime(safeCurrentTime, intervalMillis);
    }

    public static long getFloorRollupTime(long captureTime, long intervalMillis) {
        return (long) Math.floor(captureTime / (double) intervalMillis) * intervalMillis;
    }

    public static long getCeilRollupTime(long captureTime, long intervalMillis) {
        return (long) Math.ceil(captureTime / (double) intervalMillis) * intervalMillis;
    }

    public enum DataKind {
        GENERAL, QUERY, SERVICE_CALL, PROFILE
    }
}

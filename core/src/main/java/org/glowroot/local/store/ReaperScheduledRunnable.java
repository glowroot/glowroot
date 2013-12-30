/*
 * Copyright 2012-2013 the original author or authors.
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

import org.glowroot.common.Clock;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.markers.Singleton;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ReaperScheduledRunnable extends ScheduledRunnable {

    private static final long MILLISECONDS_PER_HOUR = 60L * 60L * 1000L;

    private final ConfigService configService;
    private final SnapshotDao snapshotDao;
    private final Clock clock;

    ReaperScheduledRunnable(ConfigService configService, SnapshotDao snapshotDao, Clock clock) {
        this.configService = configService;
        this.snapshotDao = snapshotDao;
        this.clock = clock;
    }

    @Override
    protected void runInternal() {
        int snapshotExpirationHours = configService.getStorageConfig()
                .getSnapshotExpirationHours();
        if (snapshotExpirationHours != GeneralConfig.SNAPSHOT_EXPIRATION_DISABLED) {
            snapshotDao.deleteSnapshotsBefore(clock.currentTimeMillis()
                    - snapshotExpirationHours * MILLISECONDS_PER_HOUR);
        }
    }
}

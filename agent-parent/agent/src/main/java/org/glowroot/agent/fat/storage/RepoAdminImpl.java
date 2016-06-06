/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.util.List;

import org.glowroot.agent.fat.storage.util.CappedDatabase;
import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;

class RepoAdminImpl implements RepoAdmin {

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final ConfigRepository configRepository;
    private final AgentDao agentDao;

    RepoAdminImpl(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            CappedDatabase traceCappedDatabase, ConfigRepository configRepository,
            AgentDao agentDao) {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.traceCappedDatabase = traceCappedDatabase;
        this.configRepository = configRepository;
        this.agentDao = agentDao;
    }

    @Override
    public void deleteAllData() throws Exception {
        SystemInfo systemInfo = agentDao.readSystemInfo("");
        dataSource.deleteAll();
        if (systemInfo != null) {
            agentDao.store(systemInfo);
        }
    }

    @Override
    public void defrag() throws Exception {
        dataSource.defrag();
    }

    @Override
    public void resizeIfNecessary() throws Exception {
        // resize() doesn't do anything if the new and old value are the same
        for (int i = 0; i < rollupCappedDatabases.size(); i++) {
            rollupCappedDatabases.get(i).resize(
                    configRepository.getFatStorageConfig().rollupCappedDatabaseSizesMb().get(i)
                            * 1024);
        }
        traceCappedDatabase
                .resize(configRepository.getFatStorageConfig().traceCappedDatabaseSizeMb() * 1024);
    }
}

/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.central;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.AgentConfigDao;
import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AgentDao.AgentConfigUpdate;
import org.glowroot.common.repo.AgentRollupRepository.AgentRollup;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

class UpdateAgentConfigIfNeededService implements Runnable {

    private static final Logger logger =
            LoggerFactory.getLogger(UpdateAgentConfigIfNeededService.class);

    private final AgentDao agentDao;
    private final AgentConfigDao agentConfigDao;
    private final DownstreamServiceImpl downstreamService;
    private final Clock clock;

    private final ExecutorService executor;

    private volatile boolean closed;

    UpdateAgentConfigIfNeededService(AgentDao agentDao, AgentConfigDao agentConfigDao,
            DownstreamServiceImpl downstreamService, Clock clock) {
        this.agentDao = agentDao;
        this.agentConfigDao = agentConfigDao;
        this.downstreamService = downstreamService;
        this.clock = clock;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                Thread.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                runInternal();
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method below)
                logger.debug(e.getMessage(), e);
                continue;
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }
    }

    void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to UpdateAgentConfigIfNeededService thread
        executor.shutdownNow();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for update agent config thread to terminate");
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer update agent config loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal() throws Exception {
        Glowroot.setTransactionOuter();
        for (AgentRollup agentRollup : agentDao.readRecentlyActiveAgentRollups(7)) {
            updateAgentConfigIfNeededAndConnected(agentRollup);
        }
    }

    private void updateAgentConfigIfNeededAndConnected(AgentRollup agentRollup)
            throws InterruptedException {
        if (agentRollup.children().isEmpty()) {
            updateAgentConfigIfNeededAndConnected(agentRollup.id());
        } else {
            for (AgentRollup childAgentRollup : agentRollup.children()) {
                updateAgentConfigIfNeededAndConnected(childAgentRollup);
            }
        }
    }

    void updateAgentConfigIfNeededAndConnected(String agentId) throws InterruptedException {
        AgentConfigUpdate agentConfigUpdate;
        try {
            agentConfigUpdate = agentConfigDao.readForUpdate(agentId);
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return;
        }
        if (agentConfigUpdate == null) {
            return;
        }
        try {
            boolean updated = downstreamService.updateAgentConfigIfConnected(agentId,
                    agentConfigUpdate.config());
            if (updated) {
                agentConfigDao.markUpdated(agentId, agentConfigUpdate.configUpdateToken());
            }
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", getDisplayForLogging(agentId), e.getMessage(), e);
        }
    }

    private String getDisplayForLogging(String agentRollupId) throws InterruptedException {
        try {
            return agentDao.readAgentRollupDisplay(agentRollupId);
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollupId, e.getMessage(), e);
            return "id:" + agentRollupId;
        }
    }

    @VisibleForTesting
    static long millisUntilNextRollup(long currentTimeMillis) {
        return 60000 - (currentTimeMillis - 10000) % 60000;
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }
}

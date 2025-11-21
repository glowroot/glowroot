/*
 * Copyright 2017-2023 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

import com.google.common.annotations.VisibleForTesting;
import com.spotify.futures.CompletableFutures;
import org.glowroot.common2.repo.CassandraProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AgentConfigDao;
import org.glowroot.central.repo.AgentConfigDao.AgentConfigAndUpdateToken;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class UpdateAgentConfigIfNeededService implements Runnable {

    private static final Logger logger =
            LoggerFactory.getLogger(UpdateAgentConfigIfNeededService.class);

    private final AgentConfigDao agentConfigDao;
    private final ActiveAgentDao activeAgentDao;
    private final DownstreamServiceImpl downstreamService;
    private final Clock clock;

    private final ExecutorService mainLoopExecutor;
    private final ExecutorService workerExecutor;

    private volatile boolean closed;

    UpdateAgentConfigIfNeededService(AgentConfigDao agentConfigDao, ActiveAgentDao activeAgentDao,
            DownstreamServiceImpl downstreamService, Clock clock) {
        this.agentConfigDao = agentConfigDao;
        this.activeAgentDao = activeAgentDao;
        this.downstreamService = downstreamService;
        this.clock = clock;
        workerExecutor = MoreExecutors2.newCachedThreadPool("Update-Agent-Config-Worker-%d");
        mainLoopExecutor = MoreExecutors2.newSingleThreadExecutor("Update-Agent-Config-Main-Loop");
        mainLoopExecutor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                MILLISECONDS.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
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
        // shutdownNow() is needed here to send interrupt to thread
        mainLoopExecutor.shutdownNow();
        if (!mainLoopExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for update agent config main loop thread to terminate");
        }
        // shutdownNow() is needed here to send interrupt to threads
        workerExecutor.shutdownNow();
        if (!workerExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for update agent config worker thread to terminate");
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer update agent config loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal() {
        activeAgentDao
                .readRecentlyActiveAgentRollups(DAYS.toMillis(7), CassandraProfile.rollup).thenCompose(list -> {
                    List<CompletionStage<?>> futures = new ArrayList<>();
                    for (AgentRollup agentRollup : list) {
                        futures.add(updateAgentConfigIfNeededAndConnected(agentRollup));
                    }
                    return CompletableFutures.allAsList(futures);
                }).toCompletableFuture().join();
    }

    private CompletionStage<?> updateAgentConfigIfNeededAndConnected(AgentRollup agentRollup) {
        if (agentRollup.children().isEmpty()) {
            return updateAgentConfigIfNeededAndConnectedAsync(agentRollup.id());
        } else {
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (AgentRollup childAgentRollup : agentRollup.children()) {
                futures.add(updateAgentConfigIfNeededAndConnected(childAgentRollup));
            }
            return CompletableFutures.allAsList(futures);
        }
    }

    CompletionStage<?> updateAgentConfigIfNeededAndConnectedAsync(String agentId) {

        return agentConfigDao.readForUpdate(agentId).thenAccept(opt -> {
            opt.ifPresent(agentConfigAndUpdateToken -> {
                UUID updateToken = agentConfigAndUpdateToken.updateToken();
                if (updateToken == null) {
                    return;
                }
                workerExecutor.execute(() -> {
                    try {
                        boolean updated = downstreamService.updateAgentConfigIfConnected(agentId,
                                agentConfigAndUpdateToken.config());
                        if (updated) {
                            agentConfigDao.markUpdated(agentId, updateToken);
                        }
                    } catch (InterruptedException e) {
                        // probably shutdown requested (see close method above)
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                });
            });
        });
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

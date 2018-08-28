/*
 * Copyright 2016-2018 the original author or authors.
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final ActiveAgentDao activeAgentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final CentralAlertingService centralAlertingService;
    private final Clock clock;

    private final ExecutorService executorMain;
    private final ExecutorService executorChild;

    private volatile boolean closed;

    public static int ROLLUP_THREAD_COUNT = 4;
    
    RollupService(ActiveAgentDao activeAgentDao, AggregateDao aggregateDao,
            GaugeValueDao gaugeValueDao, SyntheticResultDao syntheticResultDao,
            CentralAlertingService centralAlertingService, Clock clock) {
        this.activeAgentDao = activeAgentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.syntheticResultDao = syntheticResultDao;
        this.centralAlertingService = centralAlertingService;
        this.clock = clock;
//        String countStr = System.getProperty("glowroot.central.aggregateThreadCount");
//        if (countStr != null)
//        	ROLLUP_THREAD_COUNT = Integer.parseInt(countStr);
        executorMain = Executors.newSingleThreadExecutor();
        executorChild = Executors.newFixedThreadPool(ROLLUP_THREAD_COUNT);
        executorMain.execute(castInitialized(this));       
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
    
    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer rollup loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal() throws Exception {
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
    	List<AgentRollup> activeAgentRollups = shuffle(activeAgentDao.readRecentlyActiveAgentRollups(7));
    	
    	logger.debug("ActiveAgentRollups for aggegation = " + activeAgentRollups.size());
    	
    	double agentRollupSize = activeAgentRollups.size();
   	
    	if (agentRollupSize > 0) {
    	
	    	CountDownLatch latch = new CountDownLatch(ROLLUP_THREAD_COUNT);
	    	
	    	int agentGroupCount = (int) Math.round(agentRollupSize / ROLLUP_THREAD_COUNT);
	    	
	    	if (agentGroupCount == 0)
	    		agentGroupCount += 1;
	    	
	    	int startRollupPoint = 0;
	    	int endRollupPoint = 0;
	    	
	    	for (int i = 0; i < ROLLUP_THREAD_COUNT && endRollupPoint < agentRollupSize; i++) {
	    		if (i != 0)
	    			startRollupPoint = i * agentGroupCount;
	    		endRollupPoint = (i + 1) * agentGroupCount;
	    		if ((endRollupPoint > agentRollupSize) || (i == ROLLUP_THREAD_COUNT - 1 ))
	    			endRollupPoint =  (int) agentRollupSize;
	    		executorChild.execute(new RollupAgentsTask(activeAgentDao, aggregateDao, gaugeValueDao, syntheticResultDao,
	                 centralAlertingService, clock, activeAgentRollups.subList(startRollupPoint, endRollupPoint), latch));
	    	}
	    	
	    	latch.await();
    	}
        // FIXME keep this here as fallback, but also resolve alerts immediately when they are
        // deleted (or when their condition is updated)

    	centralAlertingService.checkForAllDeletedAlerts();
    }
    
    @VisibleForTesting
    static long millisUntilNextRollup(long currentTimeMillis) {
        return 60000 - (currentTimeMillis - 10000) % 60000;
    }

    void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to RollupService thread
        executorMain.shutdownNow();
        executorChild.shutdownNow();
        if (!executorMain.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for rollup thread to terminate");
        }
        
        if (!executorChild.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for rollup thread to terminate");
        }
    }


    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }

    @FunctionalInterface
    interface AgentRollupConsumer {
        void accept(AgentRollup agentRollup) throws Exception;
    }
    
    private static <T> List<T> shuffle(List<T> agentRollups) {
        List<T> mutable = new ArrayList<>(agentRollups);
        Collections.shuffle(mutable);
        return mutable;
    }
    
}

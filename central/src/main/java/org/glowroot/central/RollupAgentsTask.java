package org.glowroot.central;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RollupAgentsTask implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RollupAgentsTask.class);

    private final ActiveAgentDao activeAgentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final CentralAlertingService centralAlertingService;
    private final Clock clock;
    private List<AgentRollup> rollups;
    private CountDownLatch latch;
   
	public RollupAgentsTask(ActiveAgentDao activeAgentDao, AggregateDao aggregateDao,
            GaugeValueDao gaugeValueDao, SyntheticResultDao syntheticResultDao,
            CentralAlertingService centralAlertingService, Clock clock, List<AgentRollup> rollups, CountDownLatch latch) {
        this.activeAgentDao = activeAgentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.syntheticResultDao = syntheticResultDao;
        this.centralAlertingService = centralAlertingService;
        this.clock = clock;		
        this.rollups = rollups;
        this.latch = latch;
	}
    
    @Override
    public void run() {
        try {
            for (AgentRollup agentRollup : rollups) {
                rollupAggregates(agentRollup);
                rollupGauges(agentRollup);
                rollupSyntheticMonitors(agentRollup);
                // checking aggregate and gauge alerts after rollup since their calculation can depend
                // on rollups depending on time period length (and alerts on rollups are not checked
                // anywhere else)
                //
                // agent (not rollup) alerts are also checked right after receiving the respective data
                // (aggregate/gauge/heartbeat) from the agent, but need to also check these once a
                // minute in case no data has been received from the agent recently
                checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup);
            }
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method below)
            logger.debug(e.getMessage(), e);            
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        } finally {
        	latch.countDown();
        }
    }
    
 
    private void rollupAggregates(AgentRollup agentRollup) throws InterruptedException {
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup childAgentRollup : shuffle(agentRollup.children())) {
            rollupAggregates(childAgentRollup);
        }
        try {
            aggregateDao.rollup(agentRollup.id());
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    // returns true on success, false on failure
    private boolean rollupGauges(AgentRollup agentRollup) throws InterruptedException {
        // need to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        boolean success = true;
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            boolean childSuccess = rollupGauges(childAgentRollup);
            success = success && childSuccess;
        }
        if (!success) {
            // need to _not_ roll up parent if exception occurs while rolling up a child, since
            // gauge values initial roll up from children is done on the 1-min aggregates of the
            // children
            return false;
        }
        try {
            gaugeValueDao.rollup(agentRollup.id());
            return true;
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
            return false;
        }
    }

    private void rollupSyntheticMonitors(AgentRollup agentRollup) throws Exception {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            rollupSyntheticMonitors(childAgentRollup);
        }
        try {
            syntheticResultDao.rollup(agentRollup.id());
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }


    private void checkAggregateAndGaugeAndHeartbeatAlertsAsync(AgentRollup agentRollup)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            checkAggregateAndGaugeAndHeartbeatAlertsAsync(childAgentRollup);
        }
        centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup.id(),
                agentRollup.display(), clock.currentTimeMillis());
    }
    
    private static <T> List<T> shuffle(List<T> agentRollups) {
        List<T> mutable = new ArrayList<>(agentRollups);
        Collections.shuffle(mutable);
        return mutable;
    }
}

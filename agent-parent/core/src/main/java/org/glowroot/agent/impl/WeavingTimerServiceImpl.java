/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.impl;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.TimerImpl;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.weaving.WeavingTimerService;

public class WeavingTimerServiceImpl implements WeavingTimerService {

    private final TransactionRegistry transactionRegistry;
    private final TimerName timerName;

    private volatile boolean enabled;

    public WeavingTimerServiceImpl(TransactionRegistry transactionRegistry,
            final ConfigService configService, TimerNameCache timerNameCache) {
        this.transactionRegistry = transactionRegistry;
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                enabled = configService.getAdvancedConfig().weavingTimer();
            }
        });
        this.timerName = timerNameCache.getTimerName(OnlyForTheTimerName.class);
    }

    @Override
    public WeavingTimer start() {
        if (!enabled) {
            return NopWeavingTimer.INSTANCE;
        }
        ThreadContextImpl threadContext = transactionRegistry.getCurrentThreadContextHolder().get();
        if (threadContext == null) {
            return NopWeavingTimer.INSTANCE;
        }
        TimerImpl currentTimer = threadContext.getCurrentTimer();
        if (currentTimer == null) {
            return NopWeavingTimer.INSTANCE;
        }
        final TimerImpl timer = currentTimer.startNestedTimer(timerName);
        return new WeavingTimer() {
            @Override
            public void stop() {
                timer.stop();
            }
        };
    }

    private static class NopWeavingTimer implements WeavingTimer {
        private static final NopWeavingTimer INSTANCE = new NopWeavingTimer();
        @Override
        public void stop() {}
    }

    @Pointcut(className = "", methodName = "", methodParameterTypes = {},
            timerName = "glowroot weaving")
    private static class OnlyForTheTimerName {
        private OnlyForTheTimerName() {}
    }
}

/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.api.internal;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.agent.plugin.api.transaction.AdvancedService;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.QueryEntry;
import org.glowroot.agent.plugin.api.transaction.TimerName;

public class NopAdvancedService implements AdvancedService {

    public static final AdvancedService INSTANCE = new NopAdvancedService();

    private NopAdvancedService() {}

    @Override
    public void addErrorEntry(Throwable t) {}

    @Override
    public void addErrorEntry(@Nullable String message) {}

    @Override
    public void addErrorEntry(@Nullable String message, Throwable t) {}

    @Override
    public void setTransactionError(@Nullable Throwable t) {}

    @Override
    public void setTransactionError(@Nullable String message) {}

    @Override
    public void setTransactionError(@Nullable String message, @Nullable Throwable t) {}

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            MessageSupplier messageSupplier, TimerName timerName) {
        return NopQueryEntry.INSTANCE;
    }

    @Override
    public boolean isInTransaction() {
        return false;
    }
}

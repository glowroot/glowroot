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
package org.glowroot.agent.impl;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.internal.GlowrootService;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;

public class GlowrootServiceImpl implements GlowrootService {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootServiceImpl.class);

    private final TransactionRegistry transactionRegistry;

    public GlowrootServiceImpl(TransactionRegistry transactionRegistry) {
        this.transactionRegistry = transactionRegistry;
    }

    @Override
    public void setTransactionType(@Nullable String transactionType) {
        if (Strings.isNullOrEmpty(transactionType)) {
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionType(transactionType, Priority.USER_API);
        }
    }

    @Override
    public void setTransactionName(@Nullable String transactionName) {
        if (Strings.isNullOrEmpty(transactionName)) {
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionName(transactionName, Priority.USER_API);
        }
    }

    @Override
    public void setTransactionUser(@Nullable String user) {
        if (Strings.isNullOrEmpty(user)) {
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setUser(user, Priority.USER_API);
        }
    }

    @Override
    public void addTransactionAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("addTransactionAttribute(): argument 'name' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.addAttribute(name, value);
        }
    }

    @Override
    public void setTransactionSlowThreshold(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error(
                    "setTransactionSlowThreshold(): argument 'threshold' must be non-negative");
            return;
        }
        if (unit == null) {
            logger.error("setTransactionSlowThreshold(): argument 'unit' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
            transaction.setSlowThresholdMillis(thresholdMillis, Priority.USER_API);
        }
    }

    @Override
    public void setTransactionOuter() {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setOuter();
        }
    }
}

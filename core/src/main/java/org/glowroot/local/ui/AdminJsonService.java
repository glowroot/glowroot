/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

import org.glowroot.collector.AggregateCollector;
import org.glowroot.config.ConfigService;
import org.glowroot.config.InstrumentationConfig;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.store.GaugePointDao;
import org.glowroot.local.store.TraceDao;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.AdviceCache;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.weaving.AnalyzedWorld;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

@JsonService
class AdminJsonService {

    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    private final @Nullable AggregateCollector aggregateCollector;
    private final ConfigService configService;
    private final AdviceCache adviceCache;
    private final AnalyzedWorld analyzedWorld;
    private final @Nullable Instrumentation instrumentation;
    private final TransactionCollector transactionCollector;
    private final DataSource dataSource;
    private final TransactionRegistry transactionRegistry;

    AdminJsonService(AggregateDao aggregateDao, TraceDao traceDao, GaugePointDao gaugePointDao,
            @Nullable AggregateCollector aggregateCollector, ConfigService configService,
            AdviceCache adviceCache, AnalyzedWorld analyzedWorld,
            @Nullable Instrumentation instrumentation,
            TransactionCollector transactionCollector, DataSource dataSource,
            TransactionRegistry transactionRegistry) {
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugePointDao = gaugePointDao;
        this.aggregateCollector = aggregateCollector;
        this.configService = configService;
        this.adviceCache = adviceCache;
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
        this.transactionCollector = transactionCollector;
        this.dataSource = dataSource;
        this.transactionRegistry = transactionRegistry;
    }

    @POST("/backend/admin/delete-all-data")
    void deleteAllData() throws SQLException {
        // clear in-memory aggregates first
        if (aggregateCollector != null) {
            aggregateCollector.clearAll();
        }
        // TODO optimize by just deleting and re-creating h2 db
        traceDao.deleteAll();
        gaugePointDao.deleteAll();
        aggregateDao.deleteAll();
        dataSource.defrag();
    }

    @POST("/backend/admin/reweave")
    String reweave() throws Exception {
        // this action is not displayed in the UI when instrumentation is null
        // (which is only in dev mode anyways)
        checkNotNull(instrumentation);
        // this command is filtered out of the UI when retransform classes is not supported
        checkState(instrumentation.isRetransformClassesSupported(),
                "Retransform classes is not supported");
        int count = reweaveInternal();
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/defrag-data")
    void defragData() throws SQLException {
        dataSource.defrag();
    }

    @OnlyUsedByTests
    @POST("/backend/admin/reset-all-config")
    void resetAllConfig() throws IOException {
        configService.resetAllConfig();
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-active-transactions")
    String getNumActiveTransactions() {
        return Integer.toString(transactionRegistry.getTransactions().size());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-pending-complete-transactions")
    String getNumPendingCompleteTransactions() {
        return Integer.toString(transactionCollector.getPendingTransactions().size());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-traces")
    String getNumTraces() throws SQLException {
        return Long.toString(traceDao.count());
    }

    @RequiresNonNull("instrumentation")
    private int reweaveInternal() throws Exception {
        List<InstrumentationConfig> configs = configService.getInstrumentationConfigs();
        adviceCache.updateAdvisors(configs, false);
        Set<String> classNames = Sets.newHashSet();
        for (InstrumentationConfig config : configs) {
            classNames.add(config.className());
        }
        Set<Class<?>> classes = Sets.newHashSet();
        List<Class<?>> possibleNewReweavableClasses = getExistingSubClasses(classNames);
        // need to remove these classes from AnalyzedWorld, otherwise if a subclass and its parent
        // class are both in the list and the subclass is re-transformed first, it will use the
        // old cached AnalyzedClass for its parent which will have the old AnalyzedMethod advisors
        List<Class<?>> existingReweavableClasses =
                analyzedWorld.getClassesWithReweavableAdvice(true);
        analyzedWorld.removeClasses(possibleNewReweavableClasses);
        classes.addAll(existingReweavableClasses);
        classes.addAll(possibleNewReweavableClasses);
        if (classes.isEmpty()) {
            return 0;
        }
        instrumentation.retransformClasses(Iterables.toArray(classes, Class.class));
        List<Class<?>> updatedReweavableClasses =
                analyzedWorld.getClassesWithReweavableAdvice(false);
        // all existing reweavable classes were woven
        int count = existingReweavableClasses.size();
        // now add newly reweavable classes
        for (Class<?> possibleNewReweavableClass : possibleNewReweavableClasses) {
            if (updatedReweavableClasses.contains(possibleNewReweavableClass)
                    && !existingReweavableClasses.contains(possibleNewReweavableClass)) {
                count++;
            }
        }
        return count;
    }

    @RequiresNonNull("instrumentation")
    private List<Class<?>> getExistingSubClasses(Set<String> classNames) {
        List<Class<?>> classes = Lists.newArrayList();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (isSubClassOfOneOf(clazz, classNames)) {
                classes.add(clazz);
            }
        }
        return classes;
    }

    private static boolean isSubClassOfOneOf(Class<?> clazz, Set<String> classNames) {
        if (classNames.contains(clazz.getName())) {
            return true;
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && isSubClassOfOneOf(superclass, classNames)) {
            return true;
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (isSubClassOfOneOf(iface, classNames)) {
                return true;
            }
        }
        return false;
    }
}

/*
 * Copyright 2012-2014 the original author or authors.
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
import java.lang.instrument.UnmodifiableClassException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.config.CapturePoint;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.store.GaugePointDao;
import org.glowroot.local.store.TraceDao;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.transaction.AdviceCache;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.weaving.AnalyzedWorld;

/**
 * Json service for various admin tasks.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class AdminJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AdminJsonService.class);

    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    private final ConfigService configService;
    private final AdviceCache adviceCache;
    private final AnalyzedWorld analyzedWorld;
    @Nullable
    private final Instrumentation instrumentation;
    private final TransactionCollectorImpl transactionCollector;
    private final DataSource dataSource;
    private final TransactionRegistry transactionRegistry;

    AdminJsonService(AggregateDao aggregateDao, TraceDao traceDao, GaugePointDao gaugePointDao,
            ConfigService configService, AdviceCache adviceCache, AnalyzedWorld analyzedWorld,
            @Nullable Instrumentation instrumentation,
            TransactionCollectorImpl transactionCollector, DataSource dataSource,
            TransactionRegistry transactionRegistry) {
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugePointDao = gaugePointDao;
        this.configService = configService;
        this.adviceCache = adviceCache;
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
        this.transactionCollector = transactionCollector;
        this.dataSource = dataSource;
        this.transactionRegistry = transactionRegistry;
    }

    @POST("/backend/admin/delete-all-aggregates")
    void deleteAllAggregates() {
        logger.debug("deleteAllAggregates()");
        aggregateDao.deleteAll();
    }

    @POST("/backend/admin/delete-all-traces")
    void deleteAllTraces() {
        logger.debug("deleteAllTraces()");
        traceDao.deleteAll();
        gaugePointDao.deleteAll();
    }

    @POST("/backend/admin/reweave-capture-points")
    String reweaveCapturePoints() throws IOException, UnmodifiableClassException {
        if (instrumentation == null) {
            logger.warn("retransformClasses does not work under IsolatedWeavingClassLoader");
            return "{}";
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            logger.warn("retransformClasses is not supported");
            return "{}";
        }
        ImmutableList<CapturePoint> capturePoints = configService.getCapturePoints();
        adviceCache.updateAdvisors(capturePoints, false);
        Set<String> classNames = Sets.newHashSet();
        for (CapturePoint capturePoint : capturePoints) {
            classNames.add(capturePoint.getClassName());
        }
        Set<Class<?>> classes = Sets.newHashSet();
        // need to remove these classes from AnalyzedWorld, otherwise if a subclass and its parent
        // class are both in the list and the subclass is re-transformed first, it will use the
        // old cached AnalyzedClass for its parent which will have the old AnalyzedMethod advisors
        List<Class<?>> existingReweavableClasses =
                analyzedWorld.getClassesWithReweavableAdvice(true);
        // need to remove these classes from AnalyzedWorld, otherwise if a subclass and its parent
        // class are both in the list and the subclass is re-transformed first, it will use the
        // old cached AnalyzedClass for its parent which will have the old AnalyzedMethod advisors
        List<Class<?>> possibleNewReweavableClasses =
                analyzedWorld.getExistingSubClasses(classNames, true);
        classes.addAll(existingReweavableClasses);
        classes.addAll(possibleNewReweavableClasses);
        if (classes.isEmpty()) {
            return "{\"classes\":0}";
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
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/compact-data")
    void compactData() {
        logger.debug("compactData()");
        try {
            dataSource.compact();
        } catch (SQLException e) {
            // this might be serious, worth logging as error
            logger.error(e.getMessage(), e);
        }
    }

    @OnlyUsedByTests
    @POST("/backend/admin/reset-all-config")
    void resetAllConfig() throws IOException {
        logger.debug("resetAllConfig()");
        configService.resetAllConfig();
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-active-transactions")
    String getNumActiveTransactions() {
        logger.debug("getNumActiveTransactions()");
        return Integer.toString(transactionRegistry.getTransactions().size());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-pending-complete-transactions")
    String getNumPendingCompleteTransactions() {
        logger.debug("getNumPendingCompleteTransactions()");
        return Integer.toString(transactionCollector.getPendingCompleteTransactions().size());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-traces")
    String getNumTraces() {
        logger.debug("getNumTraces()");
        return Long.toString(traceDao.count());
    }
}

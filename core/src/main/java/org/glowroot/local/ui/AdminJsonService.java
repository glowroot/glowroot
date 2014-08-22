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

import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PointcutConfig;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.local.store.TransactionPointDao;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.AdviceCache;
import org.glowroot.trace.TraceRegistry;
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

    private final TransactionPointDao transactionPointDao;
    private final SnapshotDao snapshotDao;
    private final ConfigService configService;
    private final AdviceCache adviceCache;
    private final AnalyzedWorld analyzedWorld;
    @Nullable
    private final Instrumentation instrumentation;
    private final TraceCollectorImpl traceCollector;
    private final DataSource dataSource;
    private final TraceRegistry traceRegistry;

    AdminJsonService(TransactionPointDao transactionPointDao, SnapshotDao snapshotDao,
            ConfigService configService, AdviceCache adviceCache, AnalyzedWorld analyzedWorld,
            @Nullable Instrumentation instrumentation, TraceCollectorImpl traceCollector,
            DataSource dataSource, TraceRegistry traceRegistry) {
        this.transactionPointDao = transactionPointDao;
        this.snapshotDao = snapshotDao;
        this.configService = configService;
        this.adviceCache = adviceCache;
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
        this.traceCollector = traceCollector;
        this.dataSource = dataSource;
        this.traceRegistry = traceRegistry;
    }

    @POST("/backend/admin/delete-all-data")
    void deleteAllData() {
        logger.debug("deleteAllData()");
        transactionPointDao.deleteAll();
        snapshotDao.deleteAll();
    }

    @POST("/backend/admin/reweave-pointcuts")
    String reweavePointcuts() throws IOException, UnmodifiableClassException {
        if (instrumentation == null) {
            logger.warn("retransformClasses does not work under IsolatedWeavingClassLoader");
            return "{}";
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            logger.warn("retransformClasses is not supported");
            return "{}";
        }
        ImmutableList<PointcutConfig> pointcutConfigs = configService.getPointcutConfigs();
        adviceCache.updateAdvisors(pointcutConfigs, false);
        Set<String> classNames = Sets.newHashSet();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            classNames.add(pointcutConfig.getClassName());
        }
        Set<Class<?>> classes = Sets.newHashSet();
        List<Class<?>> existingReweavableClasses = analyzedWorld.getClassesWithReweavableAdvice();
        List<Class<?>> possibleNewReweavableClasses =
                analyzedWorld.getExistingSubClasses(classNames);
        classes.addAll(existingReweavableClasses);
        classes.addAll(possibleNewReweavableClasses);
        if (classes.isEmpty()) {
            return "{\"classes\":0}";
        }
        // need to clear these classes from AnalyzedWorld, otherwise if a subclass and its parent
        // class are both in the list and the subclass is re-transformed first, it will use the
        // old cached AnalyzedClass for its parent which will have the old AnalyzedMethod advisors
        analyzedWorld.clearClassesBeforeReweaving(classes);
        instrumentation.retransformClasses(Iterables.toArray(classes, Class.class));
        List<Class<?>> updatedReweavableClasses = analyzedWorld.getClassesWithReweavableAdvice();
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
    @GET("/backend/admin/num-pending-complete-traces")
    String getNumPendingCompleteTraces() {
        logger.debug("getNumPendingCompleteTraces()");
        return Integer.toString(traceCollector.getPendingCompleteTraces().size());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-stored-snapshots")
    String getNumStoredSnapshots() {
        logger.debug("getNumStoredSnapshots()");
        return Long.toString(snapshotDao.count());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-active-traces")
    String getNumActiveTraces() {
        logger.debug("getNumActiveTraces()");
        return Integer.toString(traceRegistry.getTraces().size());
    }
}

/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.dynamicadvice;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

import checkers.igj.quals.ReadOnly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.Pointcut;
import io.informant.common.ObjectMappers;
import io.informant.config.PointcutConfig;
import io.informant.markers.ThreadSafe;
import io.informant.weaving.Advice;
import io.informant.weaving.Advice.AdviceConstructionException;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class DynamicAdviceCache {

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(DynamicAdviceCache.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private volatile ImmutableList<Advice> dynamicAdvisors;
    private volatile ImmutableSet<String> pointcutConfigVersions;

    public DynamicAdviceCache(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        updateAdvisors(pointcutConfigs);
    }

    public Supplier<ImmutableList<Advice>> getDynamicAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            public ImmutableList<Advice> get() {
                return dynamicAdvisors;
            }
        };
    }

    public void updateAdvisors(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        this.dynamicAdvisors = getDynamicAdvisors(pointcutConfigs);
        ImmutableSet.Builder<String> pointcutConfigVersions = ImmutableSet.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutConfigVersions.add(pointcutConfig.getVersion());
        }
        this.pointcutConfigVersions = pointcutConfigVersions.build();
    }

    public boolean isPointcutConfigsOutOfSync(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        Set<String> pointcutConfigVersions = Sets.newHashSet();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutConfigVersions.add(pointcutConfig.getVersion());
        }
        return !pointcutConfigVersions.equals(this.pointcutConfigVersions);
    }

    private ImmutableList<Advice> getDynamicAdvisors(
            @ReadOnly List<PointcutConfig> pointcutConfigs) {
        ImmutableList.Builder<Advice> dynamicAdvisors = ImmutableList.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            try {
                Class<?> dynamicAdviceClass = new DynamicAdviceGenerator(pointcutConfig).generate();
                Pointcut pointcut = dynamicAdviceClass.getAnnotation(Pointcut.class);
                assertNonNull(pointcut, "Class was generated without @Pointcut annotation");
                Advice advice = Advice.from(pointcut, dynamicAdviceClass, true);
                dynamicAdvisors.add(advice);
            } catch (SecurityException e) {
                logger.warn("error creating advice for pointcut config: " + pointcutConfig, e);
            } catch (NoSuchMethodException e) {
                logger.warn("error creating advice for pointcut config: " + pointcutConfig, e);
            } catch (IllegalArgumentException e) {
                logger.error(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                logger.warn("error creating advice for pointcut config: " + pointcutConfig, e);
            } catch (InvocationTargetException e) {
                logger.warn("error creating advice for pointcut config: " + pointcutConfig, e);
            } catch (AdviceConstructionException e) {
                logger.warn("error creating advice for pointcut config: " + pointcutConfig, e);
            }
        }
        return dynamicAdvisors.build();
    }
}

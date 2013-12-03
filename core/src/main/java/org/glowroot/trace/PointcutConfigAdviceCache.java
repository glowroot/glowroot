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
package org.glowroot.trace;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

import checkers.igj.quals.ReadOnly;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Pointcut;
import org.glowroot.config.PointcutConfig;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;

import static org.glowroot.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class PointcutConfigAdviceCache {

    static final String POINTCUT_CONFIG_PLUGIN_ID = "org.glowroot:pointcut-config";

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(PointcutConfigAdviceCache.class);

    private volatile ImmutableList<Advice> advisors;
    private volatile ImmutableSet<String> pointcutConfigVersions;

    PointcutConfigAdviceCache(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        updateAdvisors(pointcutConfigs);
    }

    Supplier<ImmutableList<Advice>> getAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            public ImmutableList<Advice> get() {
                return advisors;
            }
        };
    }

    public void updateAdvisors(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        this.advisors = getAdvisors(pointcutConfigs);
        ImmutableSet.Builder<String> pointcutConfigVersions = ImmutableSet.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutConfigVersions.add(pointcutConfig.getVersion());
        }
        this.pointcutConfigVersions = pointcutConfigVersions.build();
    }

    public boolean isPointcutConfigsOutOfSync(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            versions.add(pointcutConfig.getVersion());
        }
        return !versions.equals(this.pointcutConfigVersions);
    }

    private static ImmutableList<Advice> getAdvisors(
            @ReadOnly List<PointcutConfig> pointcutConfigs) {
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            try {
                Class<?> dynamicAdviceClass = new DynamicAdviceGenerator(pointcutConfig)
                        .generate(POINTCUT_CONFIG_PLUGIN_ID);
                Pointcut pointcut = dynamicAdviceClass.getAnnotation(Pointcut.class);
                assertNonNull(pointcut, "Class was generated without @Pointcut annotation");
                Advice advice = Advice.from(pointcut, dynamicAdviceClass, true);
                advisors.add(advice);
            } catch (SecurityException e) {
                logger.warn("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (NoSuchMethodException e) {
                logger.warn("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (IllegalArgumentException e) {
                logger.error(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                logger.warn("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (InvocationTargetException e) {
                logger.warn("error creating advice for pointcut config: {}", pointcutConfig, e);
            } catch (AdviceConstructionException e) {
                logger.warn("error creating advice for pointcut config: {}", pointcutConfig, e);
            }
        }
        return advisors.build();
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdvisors() {
        return getAdvisorsSupplier().get();
    }
}

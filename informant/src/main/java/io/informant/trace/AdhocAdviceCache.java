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
package io.informant.trace;

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

import io.informant.api.weaving.Pointcut;
import io.informant.config.AdhocPointcutConfig;
import io.informant.dynamicadvice.DynamicAdviceGenerator;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.weaving.Advice;
import io.informant.weaving.Advice.AdviceConstructionException;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class AdhocAdviceCache {

    static final String ADHOC_POINTCUTS_PLUGIN_ID = "io.informant:adhoc-pointcuts";

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(AdhocAdviceCache.class);

    private volatile ImmutableList<Advice> adhocAdvisors;
    private volatile ImmutableSet<String> adhocPointcutConfigVersions;

    public AdhocAdviceCache(@ReadOnly List<AdhocPointcutConfig> adhocPointcutConfigs) {
        updateAdvisors(adhocPointcutConfigs);
    }

    public Supplier<ImmutableList<Advice>> getAdhocAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            public ImmutableList<Advice> get() {
                return adhocAdvisors;
            }
        };
    }

    public void updateAdvisors(@ReadOnly List<AdhocPointcutConfig> adhocPointcutConfigs) {
        this.adhocAdvisors = getAdhocAdvisors(adhocPointcutConfigs);
        ImmutableSet.Builder<String> adhocPointcutConfigVersions = ImmutableSet.builder();
        for (AdhocPointcutConfig adhocPointcutConfig : adhocPointcutConfigs) {
            adhocPointcutConfigVersions.add(adhocPointcutConfig.getVersion());
        }
        this.adhocPointcutConfigVersions = adhocPointcutConfigVersions.build();
    }

    public boolean isAdhocPointcutConfigsOutOfSync(
            @ReadOnly List<AdhocPointcutConfig> adhocPointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (AdhocPointcutConfig adhocPointcutConfig : adhocPointcutConfigs) {
            versions.add(adhocPointcutConfig.getVersion());
        }
        return !versions.equals(this.adhocPointcutConfigVersions);
    }

    private static ImmutableList<Advice> getAdhocAdvisors(
            @ReadOnly List<AdhocPointcutConfig> adhocPointcutConfigs) {
        ImmutableList.Builder<Advice> adhocAdvisors = ImmutableList.builder();
        for (AdhocPointcutConfig adhocPointcutConfig : adhocPointcutConfigs) {
            try {
                Class<?> dynamicAdviceClass = new DynamicAdviceGenerator(adhocPointcutConfig)
                        .generate(ADHOC_POINTCUTS_PLUGIN_ID);
                Pointcut pointcut = dynamicAdviceClass.getAnnotation(Pointcut.class);
                assertNonNull(pointcut, "Class was generated without @Pointcut annotation");
                Advice advice = Advice.from(pointcut, dynamicAdviceClass, true);
                adhocAdvisors.add(advice);
            } catch (SecurityException e) {
                logger.warn("error creating advice for pointcut config: " + adhocPointcutConfig, e);
            } catch (NoSuchMethodException e) {
                logger.warn("error creating advice for pointcut config: " + adhocPointcutConfig, e);
            } catch (IllegalArgumentException e) {
                logger.error(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                logger.warn("error creating advice for pointcut config: " + adhocPointcutConfig, e);
            } catch (InvocationTargetException e) {
                logger.warn("error creating advice for pointcut config: " + adhocPointcutConfig, e);
            } catch (AdviceConstructionException e) {
                logger.warn("error creating advice for pointcut config: " + adhocPointcutConfig, e);
            }
        }
        return adhocAdvisors.build();
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdhocAdvisors() {
        return getAdhocAdvisorsSupplier().get();
    }
}

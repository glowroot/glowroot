/**
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
package io.informant.config;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.Mixin;
import io.informant.api.weaving.Pointcut;
import io.informant.common.ObjectMappers;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.weaving.Advice;
import io.informant.weaving.Advice.AdviceConstructionException;
import io.informant.weaving.MixinType;
import io.informant.weaving.dynamic.DynamicAdviceGenerator;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class AdviceCache {

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(AdviceCache.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> nonDynamicAdvisors;
    private volatile ImmutableList<Advice> advisors;
    private volatile ImmutableSet<String> pointcutConfigVersions;

    public AdviceCache(PluginDescriptorCache pluginDescriptorCache,
            @ReadOnly List<PointcutConfig> pointcutConfigs) {
        ImmutableList.Builder<MixinType> mixinTypes = ImmutableList.builder();
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            for (String aspect : pluginDescriptor.getAspects()) {
                try {
                    // don't initialize the aspect since that will trigger static initializers which
                    // will probably call PluginServices.get()
                    Class<?> aspectClass = Class.forName(aspect, false,
                            PluginDescriptor.class.getClassLoader());
                    mixinTypes.addAll(getMixinTypes(aspectClass));
                    advisors.addAll(getAdvisors(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect);
                }
            }
        }
        this.mixinTypes = mixinTypes.build();
        this.nonDynamicAdvisors = advisors.build();
        updateAdvisors(pointcutConfigs);
    }

    // don't return ImmutableList since this method is used by SameJvmExecutionAdapter and when
    // SameJvmExecutionAdapter is compiled by maven, it is compiled against shaded informant,
    // but then if a unit test is run inside an IDE without rebuilding SameJvmExecutionAdapter it
    // will fail since informant is then unshaded
    @Immutable
    public List<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    public Supplier<ImmutableList<Advice>> getAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            public ImmutableList<Advice> get() {
                return advisors;
            }
        };
    }

    public void updateAdvisors(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        advisors.addAll(nonDynamicAdvisors);
        advisors.addAll(getDynamicAdvisors(pointcutConfigs));
        ImmutableSet.Builder<String> pointcutConfigVersions = ImmutableSet.builder();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutConfigVersions.add(pointcutConfig.getVersion());
        }
        this.advisors = advisors.build();
        this.pointcutConfigVersions = pointcutConfigVersions.build();
    }

    public boolean isPointcutConfigsOutOfSync(@ReadOnly List<PointcutConfig> pointcutConfigs) {
        Set<String> pointcutConfigVersions = Sets.newHashSet();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutConfigVersions.add(pointcutConfig.getVersion());
        }
        return !pointcutConfigVersions.equals(this.pointcutConfigVersions);
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Pointcut pointcut = memberClass.getAnnotation(Pointcut.class);
            if (pointcut != null) {
                try {
                    advisors.add(Advice.from(pointcut, memberClass, false));
                } catch (AdviceConstructionException e) {
                    logger.error("invalid advice '{}': {}", memberClass.getName(), e.getMessage());
                }
            }
        }
        return advisors;
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

    private static List<MixinType> getMixinTypes(Class<?> aspectClass) {
        List<MixinType> mixinTypes = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Mixin mixin = memberClass.getAnnotation(Mixin.class);
            if (mixin != null) {
                mixinTypes.add(MixinType.from(mixin, memberClass));
            }
        }
        return mixinTypes;
    }

    // don't return ImmutableList, see comment above
    @OnlyUsedByTests
    @Immutable
    public List<Advice> getAdvisors() {
        return advisors;
    }
}

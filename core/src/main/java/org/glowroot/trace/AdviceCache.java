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
package org.glowroot.trace;

import java.util.List;
import java.util.Set;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.glowroot.config.PointcutConfig;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.weaving.Advice;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class AdviceCache {

    private final ImmutableList<Advice> pluginAdvisors;

    private volatile ImmutableList<Advice> reweavableAdvisors;
    private volatile ImmutableSet<String> reweavablePointcutConfigVersions;

    private volatile ImmutableList<Advice> allAdvisors;

    AdviceCache(ImmutableList<Advice> pluginAdvisors,
            ImmutableList<PointcutConfig> reweavablePointcutConfigs) {
        this.pluginAdvisors = pluginAdvisors;
        reweavableAdvisors = DynamicAdviceGenerator.createAdvisors(reweavablePointcutConfigs, null);
        reweavablePointcutConfigVersions =
                createReweavablePointcutConfigVersions(reweavablePointcutConfigs);
        allAdvisors = ImmutableList.copyOf(Iterables.concat(pluginAdvisors, reweavableAdvisors));
    }

    Supplier<ImmutableList<Advice>> getAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            @Override
            public ImmutableList<Advice> get() {
                return allAdvisors;
            }
        };
    }

    public void updateAdvisors(ImmutableList<PointcutConfig> reweavablePointcutConfigs) {
        reweavableAdvisors = DynamicAdviceGenerator.createAdvisors(reweavablePointcutConfigs, null);
        reweavablePointcutConfigVersions =
                createReweavablePointcutConfigVersions(reweavablePointcutConfigs);
        allAdvisors = ImmutableList.copyOf(Iterables.concat(pluginAdvisors, reweavableAdvisors));
    }

    public boolean isOutOfSync(ImmutableList<PointcutConfig> reweavablePointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (PointcutConfig reweavablePointcutConfig : reweavablePointcutConfigs) {
            versions.add(reweavablePointcutConfig.getVersion());
        }
        return !versions.equals(this.reweavablePointcutConfigVersions);
    }

    private static ImmutableSet<String> createReweavablePointcutConfigVersions(
            List<PointcutConfig> reweavablePointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (PointcutConfig reweavablePointcutConfig : reweavablePointcutConfigs) {
            versions.add(reweavablePointcutConfig.getVersion());
        }
        return ImmutableSet.copyOf(versions);
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdvisors() {
        return getAdvisorsSupplier().get();
    }
}

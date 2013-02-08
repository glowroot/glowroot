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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.weaving.Mixin;
import io.informant.api.weaving.Pointcut;
import io.informant.core.util.JsonElements;
import io.informant.core.util.Resources2;
import io.informant.core.weaving.Advice;

import java.io.IOException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Singleton;

import checkers.igj.quals.ReadOnly;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class PluginInfoCache {

    private static final Logger logger = LoggerFactory.getLogger(PluginInfoCache.class);

    private final ImmutableList<PluginInfo> pluginInfos;
    private final ImmutableList<Mixin> mixins;
    private final ImmutableList<Advice> advisors;

    public PluginInfoCache() {
        ImmutableList.Builder<PluginInfo> pluginInfos = ImmutableList.builder();
        pluginInfos.addAll(readPackagedPlugins());
        pluginInfos.addAll(readInstalledPlugins());
        this.pluginInfos = pluginInfos.build();

        ImmutableList.Builder<Mixin> mixins = ImmutableList.builder();
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        for (PluginInfo pluginInfo : this.pluginInfos) {
            for (String aspect : pluginInfo.getAspects()) {
                try {
                    // don't initialize the aspect since that will trigger static initializers which
                    // will probably call PluginServices.get()
                    Class<?> aspectClass = Class.forName(aspect, false,
                            PluginInfo.class.getClassLoader());
                    advisors.addAll(getAdvisors(aspectClass));
                    mixins.addAll(getMixins(aspectClass));
                } catch (ClassNotFoundException e) {
                    continue;
                }
            }
        }
        this.mixins = mixins.build();
        this.advisors = advisors.build();
    }

    // don't return ImmutableList since this method is used by UiTestingMain and when UiTestingMain
    // is compiled by maven, it is compiled against shaded informant, but then if it is run inside
    // an IDE without rebuilding UiTestingMain it will fail since informant is then unshaded
    @ReadOnly
    public List<PluginInfo> getPluginInfos() {
        return pluginInfos;
    }

    // don't return ImmutableList since this method is used by SameJvmExecutionAdapter and when
    // SameJvmExecutionAdapter is compiled by maven, it is compiled against shaded informant,
    // but then if a unit test is run inside an IDE without rebuilding SameJvmExecutionAdapter it
    // will fail since informant is then unshaded
    @ReadOnly
    public List<Mixin> getMixins() {
        return mixins;
    }

    // don't return ImmutableList, see comment above
    @ReadOnly
    public List<Advice> getAdvisors() {
        return advisors;
    }

    @ReadOnly
    private static List<PluginInfo> readInstalledPlugins() {
        try {
            List<PluginInfo> plugins = Lists.newArrayList();
            for (URL url : Resources2.getResources("META-INF/io.informant.plugin.json")) {
                String json = Resources.toString(url, Charsets.UTF_8);
                PluginInfo pluginInfo;
                try {
                    pluginInfo = PluginInfoReader.createPluginInfo(json);
                } catch (JsonSyntaxException e) {
                    // no need to log stack trace
                    logger.error("error in file {}: {}", url.toExternalForm(), e.getMessage());
                    continue;
                }
                plugins.add(pluginInfo);
            }
            return plugins;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    @ReadOnly
    private static List<PluginInfo> readPackagedPlugins() {
        try {
            List<URL> urls = Resources2.getResources("META-INF/io.informant.package.json");
            if (urls.isEmpty()) {
                return ImmutableList.of();
            }
            if (urls.size() > 1) {
                List<String> resourcePaths = Lists.newArrayList();
                for (URL url : urls) {
                    resourcePaths.add("'" + url.getPath() + "'");
                }
                logger.error("more than one resource found with name 'META-INF"
                        + "/io.informant.package.json'. This file is only supported inside of an"
                        + " informant packaged jar so there should be only one. Only using the"
                        + " first one of " + Joiner.on(", ").join(resourcePaths) + ".");
            }
            URL url = urls.get(0);
            String json = Resources.toString(url, Charsets.UTF_8);
            try {
                JsonObject rootElement = (JsonObject) new JsonParser().parse(json);
                JsonArray pluginElements = JsonElements.getRequiredArray(rootElement, "plugins");
                List<PluginInfo> plugins = Lists.newArrayList();
                for (Iterator<JsonElement> i = pluginElements.iterator(); i.hasNext();) {
                    plugins.add(PluginInfoReader.createPluginInfo(i.next().getAsJsonObject()));
                }
                return plugins;
            } catch (JsonSyntaxException e) {
                // no need to log stack trace
                logger.error("error in file {}: {}", url.toExternalForm(), e.getMessage());
                return ImmutableList.of();
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Pointcut pointcut = memberClass.getAnnotation(Pointcut.class);
            if (pointcut != null) {
                advisors.add(Advice.from(pointcut, memberClass));
            }
        }
        return advisors;
    }

    private static List<Mixin> getMixins(Class<?> aspectClass) {
        List<Mixin> mixins = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Mixin mixin = memberClass.getAnnotation(Mixin.class);
            if (mixin != null) {
                mixins.add(mixin);
            }
        }
        return mixins;
    }
}

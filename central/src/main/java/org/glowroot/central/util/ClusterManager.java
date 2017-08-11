/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.central.util;

import java.io.File;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.function.SerializableFunction;
import org.infinispan.util.function.TriConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.Cache.CacheLoader;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public abstract class ClusterManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);

    public static ClusterManager create() {
        return new NonClusterManager();
    }

    public static ClusterManager create(File centralDir, Map<String, String> jgroupsProperties) {
        Map<String, String> properties = Maps.newHashMap(jgroupsProperties);
        String jgroupsConfigurationFile = properties.remove("jgroups.configurationFile");
        if (jgroupsConfigurationFile != null) {
            String initialNodes = properties.get("jgroups.initialNodes");
            if (initialNodes != null) {
                // transform from "host1:port1,host2:port2,..." to "host1[port1],host2[port2],..."
                properties.put("jgroups.initialNodes",
                        Pattern.compile(":([0-9]+)").matcher(initialNodes).replaceAll("[$1]"));
            }
            return new ClusterManagerImpl(centralDir, jgroupsConfigurationFile, properties);
        } else {
            return new NonClusterManager();
        }
    }

    public abstract <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object> Cache<K, V> createCache(
            String cacheName, CacheLoader<K, V> loader);

    public abstract <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Serializable> ConcurrentMap<K, V> createReplicatedMap(
            String mapName);

    public abstract <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object> DistributedExecutionMap<K, V> createDistributedExecutionMap(
            String cacheName);

    public abstract void close() throws InterruptedException;

    private static class ClusterManagerImpl extends ClusterManager {

        private final EmbeddedCacheManager cacheManager;

        private ClusterManagerImpl(File centralDir, String jgroupsConfigurationFile,
                Map<String, String> jgroupsProperties) {
            GlobalConfiguration configuration = new GlobalConfigurationBuilder()
                    .transport().defaultTransport()
                    .addProperty("configurationFile",
                            getConfigurationFilePropertyValue(centralDir, jgroupsConfigurationFile))
                    .build();
            cacheManager = doWithSystemProperties(jgroupsProperties,
                    () -> new DefaultCacheManager(configuration));
        }

        @Override
        public <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object> Cache<K, V> createCache(
                String cacheName, CacheLoader<K, V> loader) {
            ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
            configurationBuilder.clustering()
                    .cacheMode(CacheMode.INVALIDATION_ASYNC);
            cacheManager.defineConfiguration(cacheName, configurationBuilder.build());
            return new CacheImpl<K, V>(cacheManager.getCache(cacheName), loader);
        }

        @Override
        public <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Serializable> ConcurrentMap<K, V> createReplicatedMap(
                String mapName) {
            ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
            configurationBuilder.clustering()
                    .cacheMode(CacheMode.REPL_ASYNC);
            cacheManager.defineConfiguration(mapName, configurationBuilder.build());
            return cacheManager.getCache(mapName);
        }

        @Override
        public <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object> DistributedExecutionMap<K, V> createDistributedExecutionMap(
                String cacheName) {
            ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
            configurationBuilder.clustering()
                    .cacheMode(CacheMode.LOCAL);
            cacheManager.defineConfiguration(cacheName, configurationBuilder.build());
            return new DistributedExecutionMapImpl<K, V>(cacheManager.getCache(cacheName));
        }

        @Override
        public void close() throws InterruptedException {
            cacheManager.stop();
            // org.infinispan.factories.NamedExecutorsFactory.stop() calls shutdownNow() on all
            // executors, but does not awaitTermination(), so sleep a bit to allow time
            Thread.sleep(1000);
        }

        private static String getConfigurationFilePropertyValue(File centralDir,
                String jgroupsConfigurationFile) {
            File file = new File(jgroupsConfigurationFile);
            if (file.isAbsolute()) {
                return jgroupsConfigurationFile;
            }
            file = new File(centralDir, jgroupsConfigurationFile);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
            if (jgroupsConfigurationFile.equals("jgroups-tcp.xml")
                    || jgroupsConfigurationFile.equals("jgroups-udp.xml")) {
                return jgroupsConfigurationFile;
            }
            throw new IllegalStateException(
                    "Could not find jgroups.configurationFile: " + jgroupsConfigurationFile);
        }

        private static <V> V doWithSystemProperties(Map<String, String> properties,
                Supplier<V> supplier) {
            Map<String, String> priorSystemProperties = Maps.newHashMap();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String key = entry.getKey();
                String priorValue = System.getProperty(key);
                if (priorValue != null) {
                    priorSystemProperties.put(key, priorValue);
                }
                System.setProperty(key, entry.getValue());
            }
            try {
                return supplier.get();
            } finally {
                for (String key : properties.keySet()) {
                    String priorValue = priorSystemProperties.get(key);
                    if (priorValue == null) {
                        System.clearProperty(key);
                    } else {
                        System.setProperty(key, priorValue);
                    }
                }
            }
        }
    }

    private static class NonClusterManager extends ClusterManager {

        @Override
        public <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object> Cache<K, V> createCache(
                String cacheName, CacheLoader<K, V> loader) {
            return new NonClusterCacheImpl<K, V>(Maps.newConcurrentMap(), loader);
        }

        @Override
        public <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object> DistributedExecutionMap<K, V> createDistributedExecutionMap(
                String cacheName) {
            return new NonClusterDistributedExecutionMapImpl<>(Maps.newConcurrentMap());
        }

        @Override
        public <K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Serializable> ConcurrentMap<K, V> createReplicatedMap(
                String mapName) {
            return Maps.newConcurrentMap();
        }

        @Override
        public void close() {}
    }

    private static class CacheImpl<K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object>
            implements Cache<K, V> {

        private final org.infinispan.Cache<K, V> cache;
        private final CacheLoader<K, V> loader;

        private CacheImpl(org.infinispan.Cache<K, V> cache, CacheLoader<K, V> loader) {
            this.cache = cache;
            this.loader = loader;
        }

        @Override
        public V get(K key) throws Exception {
            V value = cache.get(key);
            if (value == null) {
                value = loader.load(key);
                cache.putForExternalRead(key, value);
            }
            return value;
        }

        @Override
        public void invalidate(K key) {
            cache.remove(key);
        }
    }

    private static class NonClusterCacheImpl<K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object>
            implements Cache<K, V> {

        private final ConcurrentMap<K, V> cache;
        private final CacheLoader<K, V> loader;

        private NonClusterCacheImpl(ConcurrentMap<K, V> cache, CacheLoader<K, V> loader) {
            this.cache = cache;
            this.loader = loader;
        }

        @Override
        public V get(K key) throws Exception {
            V value = cache.get(key);
            if (value == null) {
                value = loader.load(key);
                cache.put(key, value);
            }
            return value;
        }

        @Override
        public void invalidate(K key) {
            cache.remove(key);
        }
    }

    private static class DistributedExecutionMapImpl<K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object>
            implements DistributedExecutionMap<K, V> {

        private final org.infinispan.Cache<K, V> cache;

        private DistributedExecutionMapImpl(org.infinispan.Cache<K, V> cache) {
            this.cache = cache;
        }

        @Override
        public @Nullable V get(K key) {
            return cache.get(key);
        }

        @Override
        public void put(K key, V value) {
            cache.put(key, value);
        }

        @Override
        public void remove(K key, V value) {
            cache.remove(key, value);
        }

        @Override
        public <R extends Serializable> Optional<R> execute(String key,
                SerializableFunction<V, R> task) throws Exception {
            CollectingConsumer<R> consumer = new CollectingConsumer<R>();
            CompletableFuture<Void> future = cache.getCacheManager().executor().submitConsumer(
                    new AdapterFunction<K, V, R>(cache.getName(), key, task), consumer);
            // TODO short-circuit after receiving one (non-empty and non-shutting-down) response,
            // instead of waiting for all responses
            future.get(60, SECONDS);
            if (consumer.logStackTrace) {
                logger.warn("context for remote error(s) logged above",
                        new Exception("location stack trace"));
            }
            if (consumer.values.isEmpty()) {
                return Optional.empty();
            } else {
                // TODO first non-shutting-down response
                return Optional.of(consumer.values.remove());
            }
        }
    }

    private static class NonClusterDistributedExecutionMapImpl<K extends /*@NonNull*/ Serializable, V extends /*@NonNull*/ Object>
            implements DistributedExecutionMap<K, V> {

        private final ConcurrentMap<K, V> cache;

        private NonClusterDistributedExecutionMapImpl(ConcurrentMap<K, V> cache) {
            this.cache = cache;
        }

        @Override
        public @Nullable V get(K key) {
            return cache.get(key);
        }

        @Override
        public void put(K key, V value) {
            cache.put(key, value);
        }

        @Override
        public void remove(K key, V value) {
            cache.remove(key, value);
        }

        @Override
        public <R extends Serializable> Optional<R> execute(String key,
                SerializableFunction<V, R> task) throws Exception {
            V value = cache.get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(task.apply(value));
        }
    }

    @SuppressWarnings("serial")
    private static class AdapterFunction<K, V, R extends /*@NonNull*/ Object>
            implements SerializableFunction<EmbeddedCacheManager, Optional<R>> {

        private final String cacheName;
        private final String key;
        private final SerializableFunction<V, R> task;

        private AdapterFunction(String cacheName, String key, SerializableFunction<V, R> task) {
            this.cacheName = cacheName;
            this.key = key;
            this.task = task;
        }

        @Override
        public Optional<R> apply(EmbeddedCacheManager cacheManager) {
            org.infinispan.Cache<K, V> cache = cacheManager.getCache(cacheName, false);
            if (cache == null) {
                return Optional.empty();
            }
            V value = cache.get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(task.apply(value));
        }
    }

    private static class CollectingConsumer<V extends /*@NonNull*/ Object>
            implements TriConsumer<Address, /*@Nullable*/ Optional<V>, /*@Nullable*/ Throwable> {

        private final Queue<V> values = Queues.newConcurrentLinkedQueue();
        private volatile boolean logStackTrace;

        @Override
        public void accept(Address address, @Nullable Optional<V> value,
                @Nullable Throwable throwable) {
            if (throwable != null) {
                logger.warn("received error from {}: {}", address, throwable.getMessage(),
                        throwable);
                logStackTrace = true;
                return;
            }
            // value is only null when throwable is not null
            if (checkNotNull(value).isPresent()) {
                values.add(value.get());
            }
        }
    }
}

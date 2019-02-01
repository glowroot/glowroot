/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.impl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ScheduledRunnable;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;

public class PreloadSomeSuperTypesCache extends ScheduledRunnable {

    private static final Logger logger =
            LoggerFactory.getLogger(PreloadSomeSuperTypesCache.class);

    private final File file;
    private final int maxSize;
    private final Clock clock;

    private final ConcurrentMap<String, CacheValue> cache;
    private volatile int linesInFile;
    private volatile boolean trackAccessTimes;

    private final Set<String> needsToBeWritten = Sets.newConcurrentHashSet();

    private final long accessTimeBackfill;

    private static final AtomicBoolean isWritingFile = new AtomicBoolean();

    public PreloadSomeSuperTypesCache(File file, int maxSize, Clock clock) {
        this.file = file;
        this.maxSize = maxSize;
        this.clock = clock;

        LoadFromFileResult result = loadFromFile(file);
        cache = result.cache();
        linesInFile = result.linesInFile();
        trackAccessTimes = result.trackAccessTimes();

        accessTimeBackfill = clock.currentTimeMillis();
    }

    public void put(String typeName, String superTypeName) {
        if (trackAccessTimes) {
            putWithTrackAccessTimes(typeName, superTypeName);
        } else {
            putWithoutTrackAccessTimes(typeName, superTypeName);
        }
    }

    void putWithTrackAccessTimes(String typeName, String superTypeName) {
        long accessTime = clock.currentTimeMillis();
        needsToBeWritten.add(typeName);
        CacheValue cacheValue = cache.get(typeName);
        if (cacheValue == null) {
            cacheValue = cache.putIfAbsent(typeName,
                    new CacheValue(accessTime, ImmutableSet.of(superTypeName)));
            if (cacheValue == null) {
                return;
            }
        }
        cacheValue.accessTime = accessTime;
        cacheValue.addSuperTypeName(superTypeName);
    }

    void putWithoutTrackAccessTimes(String typeName, String superTypeName) {
        CacheValue cacheValue = cache.get(typeName);
        if (cacheValue == null) {
            cacheValue =
                    cache.putIfAbsent(typeName, new CacheValue(0, ImmutableSet.of(superTypeName)));
            if (cacheValue == null) {
                needsToBeWritten.add(typeName);
                return;
            }
        }
        if (cacheValue.addSuperTypeName(superTypeName)) {
            needsToBeWritten.add(typeName);
        }
    }

    Set<String> get(String typeName) {
        CacheValue cacheValue = cache.get(typeName);
        if (cacheValue == null) {
            return ImmutableSet.of();
        } else {
            if (trackAccessTimes) {
                cacheValue.accessTime = clock.currentTimeMillis();
                needsToBeWritten.add(typeName);
            }
            return cacheValue.superTypeNames;
        }
    }

    @Override
    protected void runInternal() throws Exception {
        if (needsToBeWritten.size() > 10) {
            writeToFileAsync();
        }
    }

    int size() {
        return cache.size();
    }

    private void writeToFileAsync() {
        if (isWritingFile.compareAndSet(false, true)) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        writeToFile();
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                    isWritingFile.set(false);
                }
            });
            thread.setName("Glowroot-Preload-Some-Super-Types-Cache-Writer");
            thread.start();
        }
    }

    @VisibleForTesting
    void writeToFile() throws FileNotFoundException, IOException {
        if (linesInFile + needsToBeWritten.size() > maxSize) {
            if (cache.size() > maxSize) {
                truncateCache();
                trackAccessTimes = true;
            }
            writeAllToFile();
        } else {
            appendNewLinesToFile();
        }
        // race condition on clearing is ok, worst case clear a few super types that were just
        // added during writing/appending above, and will likely catch them on next JVM start
        needsToBeWritten.clear();
    }

    private void truncateCache() {
        int cacheSize = cache.size();
        long[] accessTimes = new long[cacheSize];
        Iterator<CacheValue> i = cache.values().iterator();
        int j = 0;
        // checking against accessTimes.length is needed in case cache is added to concurrently
        while (i.hasNext() && j < accessTimes.length) {
            accessTimes[j++] = i.next().accessTime;
        }
        Arrays.sort(accessTimes);
        int keepCount = (maxSize * 8) / 10;
        long threshold = accessTimes[accessTimes.length - keepCount - 1];
        Iterator<Map.Entry<String, CacheValue>> k = cache.entrySet().iterator();
        int removeCount = 0;
        while (k.hasNext()) {
            if (k.next().getValue().accessTime <= threshold) {
                k.remove();
                removeCount++;
                if (cacheSize - removeCount == keepCount) {
                    // in case there are multiple entries with accessTime equal to the threshold
                    break;
                }
            }
        }
    }

    private void writeAllToFile() throws FileNotFoundException, IOException {
        BufferedWriter out = Files.newWriter(file, UTF_8);
        try {
            int lineCount = 0;
            for (Map.Entry<String, CacheValue> entry : cache.entrySet()) {
                writeLine(out, entry.getKey(), entry.getValue());
                lineCount++;
            }
            linesInFile = lineCount;
        } finally {
            out.close();
        }
    }

    private void appendNewLinesToFile() throws FileNotFoundException, IOException {
        BufferedWriter out =
                new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), UTF_8));
        try {
            int lineCount = 0;
            Iterator<String> i = needsToBeWritten.iterator();
            while (i.hasNext()) {
                String typeName = i.next();
                CacheValue cacheValue = checkNotNull(cache.get(typeName));
                writeLine(out, typeName, cacheValue);
                lineCount++;
            }
            linesInFile += lineCount;
        } finally {
            out.close();
        }
    }

    private void writeLine(BufferedWriter out, String typeName, CacheValue cacheValue)
            throws IOException {
        if (trackAccessTimes) {
            long accessTime = cacheValue.accessTime;
            if (accessTime == 0) {
                accessTime = accessTimeBackfill;
            }
            out.write(Long.toString(accessTime));
            out.write(",");
        }
        out.write(typeName);
        for (String superTypeName : cacheValue.superTypeNames) {
            out.write(",");
            out.write(superTypeName);
        }
        out.write("\n");
    }

    private static LoadFromFileResult loadFromFile(File file) {
        ConcurrentMap<String, CacheValue> cache = Maps.newConcurrentMap();
        if (file.exists()) {
            try {
                return Files.readLines(file, UTF_8, new LoadFromFile(file));
            } catch (IOException e) {
                logger.error("error reading {}: {}", file.getAbsolutePath(), e.getMessage(), e);
            }
        }
        return ImmutableLoadFromFileResult.builder()
                .cache(cache)
                .linesInFile(0)
                .trackAccessTimes(false)
                .build();
    }

    @Value.Immutable
    interface LoadFromFileResult {
        ConcurrentMap<String, CacheValue> cache();
        int linesInFile();
        boolean trackAccessTimes();
    }

    static class CacheValue {

        private volatile long accessTime;
        // use ImmutableSet for memory consumption (vs Sets.newConcurrentHashSet())
        private volatile ImmutableSet<String> superTypeNames;

        private CacheValue(long accessTime, ImmutableSet<String> superTypeNames) {
            this.accessTime = accessTime;
            this.superTypeNames = superTypeNames;
        }

        private boolean addSuperTypeName(String superTypeName) {
            if (superTypeNames.contains(superTypeName)) {
                return false;
            }
            if (superTypeNames.size() > 10) {
                // just to impose some memory limit
                return false;
            }
            synchronized (this) {
                superTypeNames = ImmutableSet.<String>builder().addAll(superTypeNames)
                        .add(superTypeName)
                        .build();
            }
            return true;
        }
    }

    private static class LoadFromFile implements LineProcessor<LoadFromFileResult> {

        private final File file;
        private final ConcurrentMap<String, CacheValue> cache = Maps.newConcurrentMap();

        private int linesInFile;
        private @Nullable Boolean hasAccessTimes;

        private final Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();

        private boolean errorLogged;

        private LoadFromFile(File file) {
            this.file = file;
        }

        @Override
        public boolean processLine(String line) {
            PeekingIterator<String> i = Iterators.peekingIterator(splitter.split(line).iterator());
            try {
                linesInFile++;
                if (hasAccessTimes == null) {
                    char c = i.peek().charAt(0);
                    hasAccessTimes = c >= '0' && c <= '9';
                }
                long accessTime = hasAccessTimes ? Long.parseLong(i.next()) : 0;
                String typeName = i.next();
                CacheValue cacheValue = new CacheValue(accessTime, ImmutableSet.copyOf(i));
                cache.put(typeName, cacheValue);
            } catch (Exception e) {
                // e.g. NoSuchElementException or NumberFormatException
                if (!errorLogged) {
                    logger.error("error parsing {}: {}", file.getAbsolutePath(), e.getMessage(), e);
                }
                errorLogged = true;
            }
            return true;
        }

        @Override
        public LoadFromFileResult getResult() {
            return ImmutableLoadFromFileResult.builder()
                    .cache(cache)
                    .linesInFile(linesInFile)
                    .trackAccessTimes(hasAccessTimes != null && hasAccessTimes)
                    .build();
        }
    }
}

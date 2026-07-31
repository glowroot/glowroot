/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.common2.config;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Resolves H2 {@code cache_size} (KB) for embedded installs. Auto targets 128 MB; values are
 * clamped to {@code [16 MB, min(5% of max heap, 256 MB)]} so shared-JVM deploys stay safe.
 */
public final class H2CacheSize {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_FIXED = "fixed";
    public static final String MODE_PERCENT = "percent";

    public static final String SYSTEM_PROPERTY = "glowroot.internal.h2.cacheSize";

    public static final int AUTO_MB = 128;
    public static final int FLOOR_MB = 16;
    public static final int CEILING_MB = 256;
    static final int PERCENT_OF_XMX = 5;

    private H2CacheSize() {}

    /**
     * Effective cache size in KB. If {@link #SYSTEM_PROPERTY} is set, that value wins (legacy ops
     * override). Otherwise resolves from UI mode/value against {@code maxMemoryBytes}.
     */
    public static int resolveKb(String mode, int value, long maxMemoryBytes,
            @Nullable String systemPropertyValue) {
        if (systemPropertyValue != null && !systemPropertyValue.isEmpty()) {
            return Integer.parseInt(systemPropertyValue.trim());
        }
        int targetMb;
        if (MODE_FIXED.equals(mode)) {
            targetMb = value;
        } else if (MODE_PERCENT.equals(mode)) {
            int pct = Math.max(0, value);
            targetMb = (int) Math.min(Integer.MAX_VALUE, (maxMemoryBytes / (1024L * 1024L)) * pct / 100L);
        } else {
            // auto (default)
            targetMb = AUTO_MB;
        }
        return clampMb(targetMb, maxMemoryBytes) * 1024;
    }

    public static int resolveKbFromConfig(EmbeddedStorageConfig config, long maxMemoryBytes) {
        return resolveKb(config.h2CacheMode(), config.h2CacheValue(), maxMemoryBytes,
                System.getProperty(SYSTEM_PROPERTY));
    }

    public static int clampMb(int targetMb, long maxMemoryBytes) {
        int fivePercentMb = (int) Math.max(FLOOR_MB, (maxMemoryBytes / (1024L * 1024L)) * PERCENT_OF_XMX / 100L);
        int upper = Math.min(CEILING_MB, fivePercentMb);
        if (upper < FLOOR_MB) {
            upper = FLOOR_MB;
        }
        int mb = Math.max(FLOOR_MB, Math.min(targetMb, upper));
        return mb;
    }

    public static int effectiveMb(String mode, int value, long maxMemoryBytes,
            @Nullable String systemPropertyValue) {
        return resolveKb(mode, value, maxMemoryBytes, systemPropertyValue) / 1024;
    }

    public static boolean exceedsClamp(String mode, int value, long maxMemoryBytes) {
        if (MODE_AUTO.equals(mode) || mode == null || mode.isEmpty()) {
            return false;
        }
        int targetMb;
        if (MODE_PERCENT.equals(mode)) {
            targetMb = (int) ((maxMemoryBytes / (1024L * 1024L)) * Math.max(0, value) / 100L);
        } else {
            targetMb = value;
        }
        return targetMb > clampMb(targetMb, maxMemoryBytes) || targetMb >= CEILING_MB;
    }
}

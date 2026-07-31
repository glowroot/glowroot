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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class H2CacheSizeTest {

    private static final long XMX_10G = 10L * 1024 * 1024 * 1024;
    private static final long XMX_512M = 512L * 1024 * 1024;

    @Test
    public void autoUses128MbClamped() {
        // 5% of 10g = 512 MB but ceiling is 256 → clamp to 256? Wait: target is 128, clamp min(128, 256, 5%) = 128
        int kb = H2CacheSize.resolveKb(H2CacheSize.MODE_AUTO, 0, XMX_10G, null);
        assertThat(kb).isEqualTo(128 * 1024);
    }

    @Test
    public void fixedClampedToFivePercentAndCeiling() {
        // request 1024 MB on 10g: 5%=512, ceiling=256 → 256
        int kb = H2CacheSize.resolveKb(H2CacheSize.MODE_FIXED, 1024, XMX_10G, null);
        assertThat(kb).isEqualTo(256 * 1024);
    }

    @Test
    public void fixedClampedToFivePercentOnSmallHeap() {
        // 5% of 512m = 25 MB → clamp upper 25, floor 16; request 128 → 25
        int kb = H2CacheSize.resolveKb(H2CacheSize.MODE_FIXED, 128, XMX_512M, null);
        assertThat(kb).isEqualTo(25 * 1024);
    }

    @Test
    public void percentOfXmx() {
        // 2% of 10g = 204 MB → under ceiling
        int kb = H2CacheSize.resolveKb(H2CacheSize.MODE_PERCENT, 2, XMX_10G, null);
        assertThat(kb).isEqualTo(204 * 1024);
    }

    @Test
    public void systemPropertyOverrides() {
        int kb = H2CacheSize.resolveKb(H2CacheSize.MODE_AUTO, 0, XMX_10G, "4096");
        assertThat(kb).isEqualTo(4096);
    }

    @Test
    public void floorAt16Mb() {
        int kb = H2CacheSize.resolveKb(H2CacheSize.MODE_FIXED, 1, XMX_10G, null);
        assertThat(kb).isEqualTo(16 * 1024);
    }

    @Test
    public void exceedsClampWhenRawAboveEffective() {
        assertThat(H2CacheSize.exceedsClamp(H2CacheSize.MODE_FIXED, 512, XMX_10G)).isTrue();
        assertThat(H2CacheSize.exceedsClamp(H2CacheSize.MODE_AUTO, 0, XMX_10G)).isFalse();
    }
}

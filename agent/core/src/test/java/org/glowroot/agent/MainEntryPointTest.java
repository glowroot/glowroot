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
package org.glowroot.agent;

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MainEntryPointTest {

    @Test
    public void testUpgradeToCollectorAddress() {
        List<String> lines = ImmutableList.of("before=test", "collector.host=localhost",
                "collector.port=8181", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "collector.address=localhost:8181",
                "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressBothEmpty() {
        List<String> lines =
                ImmutableList.of("before=test", "collector.host=", "collector.port=", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "collector.address=", "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressBothMissing() {
        List<String> lines = ImmutableList.of("before=test", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressOnlyHostEmpty() {
        List<String> lines = ImmutableList.of("before=test", "collector.host=",
                "collector.port=8181", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "collector.address=", "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressOnlyPortEmpty() {
        List<String> lines = ImmutableList.of("before=test", "collector.host=localhost",
                "collector.port=", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "collector.address=localhost:8181",
                "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressOnlyHostMissing() {
        List<String> lines =
                ImmutableList.of("before=test", "collector.port=8181", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressOnlyPortMissing() {
        List<String> lines =
                ImmutableList.of("before=test", "collector.host=localhost", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).containsExactly("before=test", "collector.address=localhost:8181",
                "after=test");
    }

    @Test
    public void testUpgradeToCollectorAddressNotNeeded() {
        List<String> lines =
                ImmutableList.of("before=test", "collector.address=xyz:1234", "after=test");
        List<String> newLines = MainEntryPoint.upgradeToCollectorAddressIfNeeded(lines);
        assertThat(newLines).isEqualTo(lines);
    }
}

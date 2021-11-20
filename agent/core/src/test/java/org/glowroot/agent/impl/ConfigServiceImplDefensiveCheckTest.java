/*
 * Copyright 2015-2018 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ConfigServiceImplDefensiveCheckTest {

    private ConfigServiceImpl configService;

    @BeforeEach
    public void beforeEachTest() {
        ConfigService configService = mock(ConfigService.class);
        this.configService = ConfigServiceImpl.create(configService,
                ImmutableList.<PluginDescriptor>of(), "dummy");
    }

    @Test
    public void testGetProperty() {
        assertThat(configService.getStringProperty(null).value()).isEqualTo("");
        assertThat(configService.getBooleanProperty(null).value()).isEqualTo(false);
        assertThat(configService.getDoubleProperty(null).value()).isEqualTo(null);
        assertThat(configService.getStringProperty("").value()).isEqualTo("");
        assertThat(configService.getBooleanProperty("").value()).isEqualTo(false);
        assertThat(configService.getDoubleProperty("").value()).isEqualTo(null);
    }

    @Test
    public void testRegisterConfigListener() {
        configService.registerConfigListener(null);
    }

    @Test
    public void testRegisterListener() {
        configService.registerConfigListener(null);
    }
}

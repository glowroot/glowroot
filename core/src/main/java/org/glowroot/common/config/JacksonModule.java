/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.common.config;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.glowroot.common.config.GaugeConfig.MBeanAttribute;

public class JacksonModule {

    public static Module create() {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(TransactionConfig.class, ImmutableTransactionConfig.class);
        module.addAbstractTypeMapping(UserInterfaceConfig.class,
                ImmutableUserInterfaceConfig.class);
        module.addAbstractTypeMapping(StorageConfig.class, ImmutableStorageConfig.class);
        module.addAbstractTypeMapping(SmtpConfig.class, ImmutableSmtpConfig.class);
        module.addAbstractTypeMapping(UserRecordingConfig.class,
                ImmutableUserRecordingConfig.class);
        module.addAbstractTypeMapping(AdvancedConfig.class, ImmutableAdvancedConfig.class);
        module.addAbstractTypeMapping(PluginConfig.class, ImmutablePluginConfig.class);
        module.addAbstractTypeMapping(InstrumentationConfig.class,
                ImmutableInstrumentationConfig.class);
        module.addAbstractTypeMapping(GaugeConfig.class, ImmutableGaugeConfig.class);
        module.addAbstractTypeMapping(MBeanAttribute.class, ImmutableMBeanAttribute.class);
        module.addAbstractTypeMapping(AlertConfig.class, ImmutableAlertConfig.class);
        module.addAbstractTypeMapping(PropertyDescriptor.class, ImmutablePropertyDescriptor.class);
        return module;
    }
}

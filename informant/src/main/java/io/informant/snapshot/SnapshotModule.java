/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.snapshot;

import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.markers.ThreadSafe;

import java.util.concurrent.ExecutorService;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class SnapshotModule {

    private final SnapshotTraceSink traceSink;

    public SnapshotModule(ConfigModule configModule, SnapshotSink snapshotSink,
            ExecutorService executorService) throws Exception {
        ConfigService configService = configModule.getConfigService();
        Ticker ticker = configModule.getTicker();
        traceSink = new SnapshotTraceSink(executorService, configService, snapshotSink, ticker);
    }

    public SnapshotTraceSink getSnapshotTraceSink() {
        return traceSink;
    }
}

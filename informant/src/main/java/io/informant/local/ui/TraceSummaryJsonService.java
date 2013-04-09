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
package io.informant.local.ui;

import java.io.IOException;

import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Singleton;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class TraceSummaryJsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSummaryJsonService.class);

    private final TraceCommonService traceCommonService;

    TraceSummaryJsonService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    @JsonServiceMethod
    String getSummary(String id) throws IOException {
        logger.debug("getSummary(): id={}", id);
        CharSource charSource =
                traceCommonService.createCharSourceForSnapshotOrActiveTrace(id, true);
        if (charSource == null) {
            logger.debug("no trace found for id '{}', returning expired=true", id);
            return "{\"expired\":true}";
        } else {
            // summary is small and doesn't need to be streamed
            return charSource.read();
        }
    }
}

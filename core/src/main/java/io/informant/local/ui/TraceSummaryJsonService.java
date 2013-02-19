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

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.util.ByteStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceSummaryJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSummaryJsonService.class);

    private final TraceCommonService traceCommonService;

    @Inject
    TraceSummaryJsonService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    // this method returns byte[] directly to avoid converting to it utf8 string and back again
    @JsonServiceMethod
    byte/*@Nullable*/[] getSummary(String id) throws IOException {
        logger.debug("getSummary(): id={}", id);
        ByteStream byteStream = traceCommonService.getSnapshotOrActiveJson(id, true);
        if (byteStream == null) {
            logger.error("no trace found for id '{}'", id);
            // TODO 404
            return null;
        } else {
            // summary is small and doesn't need to be streamed
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byteStream.writeTo(baos);
            return baos.toByteArray();
        }
    }
}

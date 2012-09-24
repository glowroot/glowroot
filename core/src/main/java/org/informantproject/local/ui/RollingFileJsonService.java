/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.Nullable;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.ByteStream;
import org.informantproject.core.util.FileBlock;
import org.informantproject.core.util.FileBlock.InvalidBlockIdFormatException;
import org.informantproject.core.util.RollingFile;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class RollingFileJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(RollingFileJsonService.class);

    private final RollingFile rollingFile;

    @Inject
    RollingFileJsonService(RollingFile rollingFile) {
        this.rollingFile = rollingFile;
    }

    // this method returns byte[] directly to avoid converting to it utf8 string and back again
    @JsonServiceMethod
    @Nullable
    byte[] getBlock(String id) throws IOException {
        logger.debug("getBlock(): id={}", id);
        FileBlock fileBlock;
        try {
            fileBlock = FileBlock.from(id);
        } catch (InvalidBlockIdFormatException e) {
            logger.warn("invalid block id format '{}'", id);
            return null;
        }
        ByteStream byteStream = rollingFile.read(fileBlock, "\"rolled over\"");
        if (byteStream == null) {
            logger.warn("no block found for id '{}'", id);
            // TODO need to handle rolled over
            return null;
        } else {
            // this json service is used for exceptions and stack traces which are small and don't
            // need to be streamed
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byteStream.writeTo(baos);
            return baos.toByteArray();
        }
    }
}

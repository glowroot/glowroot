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
package io.informant;

import io.informant.markers.Static;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Strings;
import com.google.common.io.Files;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class DataDir {

    private static final Logger logger = LoggerFactory.getLogger(DataDir.class);

    @Nullable
    private static final File BASE_DIR;

    static {
        File baseDir;
        try {
            URL agentJarLocation = DataDir.class.getProtectionDomain().getCodeSource()
                    .getLocation();
            if (agentJarLocation == null) {
                // probably running unit tests, will log warning below in getDataDir() if
                // informant.data.dir is not provided
                baseDir = null;
            } else {
                // by default use the same directory that the agent jar is in
                baseDir = new File(agentJarLocation.toURI()).getParentFile();
            }
        } catch (URISyntaxException e) {
            logger.warn(e.getMessage(), e);
            baseDir = new File(".");
        }
        BASE_DIR = baseDir;
    }

    private DataDir() {}

    static File getDataDirWithNoWarning(@ReadOnly Map<String, String> properties) {
        return getDataDir(properties, true);
    }

    static File getDataDir(@ReadOnly Map<String, String> properties) {
        return getDataDir(properties, false);
    }

    private static File getDataDir(@ReadOnly Map<String, String> properties,
            boolean disableWarnings) {
        String dataDirOverride = properties.get("data.dir");
        if (dataDirOverride != null) {
            // used by unit tests
            return new File(dataDirOverride);
        }
        File baseDir = BASE_DIR;
        if (baseDir == null) {
            if (!disableWarnings) {
                logger.warn("could not determine location of informant.jar, using process current"
                        + " directory as the base directory");
            }
            baseDir = new File(".");
        }
        String id = properties.get("id");
        if (Strings.isNullOrEmpty(id)) {
            return baseDir;
        }
        if (!id.matches("[a-zA-Z0-9 -_]+")) {
            if (!disableWarnings) {
                logger.warn("invalid informant.id '{}', id must include only alphanumeric"
                        + " characters, spaces, dashes underscores and forward slashes, proceeding"
                        + " instead with empty id", id);
            }
            return baseDir;
        }
        File dataDir = new File(baseDir, id);
        try {
            Files.createParentDirs(dataDir);
            return dataDir;
        } catch (IOException e) {
            if (!disableWarnings) {
                logger.warn("unable to create directory '{}', writing to base dir instead '{}'",
                        dataDir.getPath(), baseDir.getPath());
            }
            return baseDir;
        }
    }
}

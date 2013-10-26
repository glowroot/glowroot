/*
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

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

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
        String dataDirPath = properties.get("data.dir");
        if (dataDirPath == null) {
            return getBaseDir(disableWarnings);
        }
        File dataDir = new File(dataDirPath);
        File baseDir = null;
        if (!dataDir.isAbsolute()) {
            // resolve path relative to base dir instead of process current dir
            baseDir = getBaseDir(disableWarnings);
            dataDir = new File(baseDir, dataDirPath);
        }
        try {
            Files.createParentDirs(dataDir);
        } catch (IOException e) {
            if (baseDir == null) {
                baseDir = getBaseDir(disableWarnings);
            }
            logger.warn("unable to create data directory '{}', using '{}' instead",
                    dataDir.getAbsolutePath(), baseDir.getAbsolutePath());
        }
        return dataDir;
    }

    private static File getBaseDir(boolean disableWarnings) {
        if (BASE_DIR == null) {
            if (!disableWarnings) {
                // warning is logged lazily (instead of in static initializer) so that unit tests
                // have a chance to pass in absolute data dir path and bypass this warning
                logger.warn("could not determine location of informant.jar, using process current"
                        + " directory as the data directory");
            }
            return new File(".");
        }
        return BASE_DIR;
    }
}

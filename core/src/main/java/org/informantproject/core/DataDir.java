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
package org.informantproject.core;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.Static;

import com.google.common.io.Files;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class DataDir {

    private static final Logger logger = LoggerFactory.getLogger(DataDir.class);

    private static final File DEFAULT_DATA_DIR;

    static {
        File defaultDataDir;
        try {
            URL agentJarLocation = DataDir.class.getProtectionDomain().getCodeSource()
                    .getLocation();
            if (agentJarLocation == null) {
                // probably running unit tests
                defaultDataDir = new File(".");
            } else {
                // by default use the same directory that the agent jar is in
                defaultDataDir = new File(agentJarLocation.toURI()).getParentFile();
            }
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
            defaultDataDir = new File(".");
        }
        DEFAULT_DATA_DIR = defaultDataDir;
    }

    static File getDataDirWithNoWarning(Map<String, String> properties) {
        return getDataDir(properties, true);
    }

    static File getDataDir(Map<String, String> properties) {
        return getDataDir(properties, false);
    }

    private static File getDataDir(Map<String, String> properties, boolean disableWarning) {
        String path = properties.get("data.dir");
        if (path == null) {
            return DEFAULT_DATA_DIR;
        }
        File dataDir = new File(path);
        if (!dataDir.isAbsolute()) {
            dataDir = new File(DEFAULT_DATA_DIR, path);
        }
        try {
            Files.createParentDirs(dataDir);
            return dataDir;
        } catch (IOException e) {
            if (!disableWarning) {
                logger.warn("unable to create data.dir '{}', proceeding with default value '{}'",
                        dataDir.getAbsolutePath(), DEFAULT_DATA_DIR.getAbsolutePath());
            }
            return DEFAULT_DATA_DIR;
        }
    }
}

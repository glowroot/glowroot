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

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;

import com.google.common.io.Files;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO implement good error reporting for command line args
// to help users get up and running with minimal trouble
@Immutable
class AgentArgs {

    private static final Logger logger = LoggerFactory.getLogger(AgentArgs.class);

    private final int uiPort;
    private final File dataDir;
    // this is for internal use (by plugin-testkit)
    private final boolean h2MemDb;

    static AgentArgs from(@Nullable String agentArgs) {
        Parser parser = new Parser();
        if (agentArgs != null) {
            parser.parse(agentArgs);
        }
        return new AgentArgs(parser.uiPort, parser.dataDir, parser.h2MemDb);
    }

    private AgentArgs(int uiPort, File dataDir, boolean h2MemDb) {
        this.uiPort = uiPort;
        this.dataDir = dataDir;
        this.h2MemDb = h2MemDb;
    }

    int getUiPort() {
        return uiPort;
    }

    File getDataDir() {
        return dataDir;
    }

    boolean isH2MemDb() {
        return h2MemDb;
    }

    private static class Parser {

        private static final File DEFAULT_DATA_DIR;

        static {
            File defaultDataDir;
            try {
                URL agentJarLocation = AgentArgs.class.getProtectionDomain().getCodeSource()
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

        private int uiPort = 4000;
        private File dataDir = DEFAULT_DATA_DIR;
        // this is for internal use (by plugin-testkit)
        private boolean h2MemDb = false;

        private void parse(String agentArgs) {
            for (String agentArg : agentArgs.split(",")) {
                String agentArgName = agentArg.substring(0, agentArg.indexOf(":"));
                String agentArgValue = agentArg.substring(agentArg.indexOf(":") + 1);
                if (agentArgName.equals("ui.port")) {
                    parseUiPort(agentArgValue);
                } else if (agentArgName.equals("data.dir")) {
                    parseDataDir(agentArgValue);
                } else if (agentArgName.equals("internal.h2memdb")) {
                    // this is for internal use (by plugin-testkit)
                    h2MemDb = Boolean.parseBoolean(agentArgValue);
                } else {
                    throw new IllegalStateException("Unsupported agent arg '" + agentArgName
                            + "'");
                }
            }
        }

        private void parseUiPort(String uiPort) {
            try {
                this.uiPort = Integer.parseInt(uiPort);
            } catch (NumberFormatException e) {
                logger.warn("invalid ui.port value '{}', proceeding with default value '4000'",
                        uiPort);
            }
        }

        private void parseDataDir(String path) {
            File dataDir = new File(path);
            if (!dataDir.isAbsolute()) {
                dataDir = new File(DEFAULT_DATA_DIR, path);
            }
            try {
                Files.createParentDirs(dataDir);
                this.dataDir = dataDir;
            } catch (IOException e) {
                logger.error("unable to create data.dir '{}', proceeding with default value '{}'",
                        dataDir.getAbsolutePath(), this.dataDir.getAbsolutePath());
            }
        }
    }
}

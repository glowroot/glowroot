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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Files;

/**
 * 
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO implement good error reporting for command line args
// to help users get up and running with minimal trouble
public class AgentArgs {

    private static final Logger logger = LoggerFactory.getLogger(AgentArgs.class);

    private int uiPort = 4000;
    private File dataDir = getDefaultDataDir();

    public AgentArgs() {}

    public AgentArgs(@Nullable String agentArgs) {
        if (agentArgs != null) {
            for (String agentArg : agentArgs.split(",")) {
                String agentArgName = agentArg.substring(0, agentArg.indexOf(":"));
                String agentArgValue = agentArg.substring(agentArg.indexOf(":") + 1);
                if (agentArgName.equals("ui.port")) {
                    setUiPort(agentArgValue);
                } else if (agentArgName.equals("data.dir")) {
                    setDataDir(agentArgValue);
                } else {
                    throw new IllegalStateException("Unsupported agent arg '" + agentArgName + "'");
                }
            }
        }
    }

    public int getUiPort() {
        return uiPort;
    }

    public File getDataDir() {
        return dataDir;
    }

    private void setUiPort(String uiPort) {
        try {
            this.uiPort = Integer.parseInt(uiPort);
        } catch (NumberFormatException e) {
            logger.warn("invalid ui.port value '{}', proceeding with default value '4000'", uiPort);
        }
    }

    private void setDataDir(String path) {
        File dataDir = new File(path);
        if (!dataDir.isAbsolute()) {
            dataDir = new File(getDefaultDataDir(), path);
        }
        try {
            Files.createParentDirs(dataDir);
            this.dataDir = dataDir;
        } catch (IOException e) {
            logger.error("unable to create data.dir '{}', proceeding with default value '{}'",
                    dataDir.getAbsolutePath(), this.dataDir.getAbsolutePath());
        }
    }

    private static File getDefaultDataDir() {
        try {
            URL agentJarLocation = AgentArgs.class.getProtectionDomain().getCodeSource()
                    .getLocation();
            if (agentJarLocation == null) {
                // probably running unit tests
                return new File(".");
            } else {
                // by default use the same directory that the agent jar is in
                return new File(agentJarLocation.toURI()).getParentFile();
            }
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
            return new File(".");
        }
    }
}

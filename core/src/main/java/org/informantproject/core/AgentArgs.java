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
import java.net.URISyntaxException;
import java.net.URL;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private File dataDir;

    public AgentArgs() {
        dataDir = getDefaultDataDir();
    }

    public AgentArgs(@Nullable String agentArgs) {
        dataDir = getDefaultDataDir();
        if (agentArgs != null) {
            for (String agentArg : agentArgs.split(",")) {
                String agentArgName = agentArg.substring(0, agentArg.indexOf(":"));
                String agentArgValue = agentArg.substring(agentArg.indexOf(":") + 1);
                if (agentArgName.equals("ui.port")) {
                    setUiPort(agentArgValue);
                } else if (agentArgName.equals("data.dir")) {
                    File dataDir = new File(agentArgValue);
                    if (dataDir.isAbsolute()) {
                        setDataDir(dataDir);
                    } else {
                        setDataDir(new File(getDefaultDataDir(), dataDir.getPath()));
                    }
                } else {
                    throw new IllegalStateException("Unsupported agent arg '" + agentArgName + "'");
                }
            }
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

    private void setUiPort(String uiPort) {
        try {
            this.uiPort = Integer.parseInt(uiPort);
        } catch (NumberFormatException e) {
            logger.warn("invalid ui.port value '{}', using default value '4000'", uiPort);
        }
    }

    private void setDataDir(File dataDir) {
        dataDir.mkdirs();
        if (!dataDir.isDirectory()) {
            logger.warn("unable to create data.dir '{}', using default value '{}'",
                    dataDir.getAbsolutePath(), this.dataDir.getAbsolutePath());
        } else {
            this.dataDir = dataDir;
        }
    }

    public int getUiPort() {
        return uiPort;
    }

    public File getDataDir() {
        return dataDir;
    }
}

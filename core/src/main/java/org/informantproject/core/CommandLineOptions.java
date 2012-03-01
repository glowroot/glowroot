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

/**
 * 
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO implement good error reporting for command line args
// to help users get up and running with minimal trouble
public class CommandLineOptions {

    private int uiPort = 4000;
    private String dbFile = "informant";

    public CommandLineOptions() {}

    public CommandLineOptions(String options) {
        if (options != null) {
            for (String option : options.split(",")) {
                String[] optionParts = option.split("=");
                if (optionParts[0].equals("ui.port")) {
                    uiPort = Integer.parseInt(optionParts[1]);
                } else if (optionParts[0].equals("db.file")) {
                    dbFile = optionParts[1];
                } else {
                    throw new IllegalStateException("unsupported option name '" + optionParts[0]
                            + "'");
                }
            }
        }
    }

    public int getUiPort() {
        return uiPort;
    }

    public String getDbFile() {
        return dbFile;
    }
}

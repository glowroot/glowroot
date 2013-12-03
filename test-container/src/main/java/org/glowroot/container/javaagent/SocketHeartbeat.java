/*
 * Copyright 2012-2013 the original author or authors.
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
package org.glowroot.container.javaagent;

import java.io.IOException;
import java.io.ObjectOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SocketHeartbeat implements Runnable {

    public static final String PING_COMMAND = "PING";

    private static final Logger logger = LoggerFactory.getLogger(SocketHeartbeat.class);

    private final ObjectOutputStream objectOut;

    SocketHeartbeat(ObjectOutputStream objectOut) {
        this.objectOut = objectOut;
    }

    public void run() {
        while (true) {
            // sychronizing with SocketCommandProcessor
            synchronized (objectOut) {
                try {
                    objectOut.writeObject(PING_COMMAND);
                } catch (IOException e) {
                    // the test jvm has been terminated
                    System.exit(0);
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                return;
            }
        }
    }
}

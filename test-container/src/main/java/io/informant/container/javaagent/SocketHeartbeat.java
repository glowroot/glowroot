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
package io.informant.container.javaagent;

import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SocketHeartbeat implements Runnable {

    public static final String PING_COMMAND = "PING";
    private final ObjectOutputStream objectOut;

    SocketHeartbeat(ObjectOutputStream objectOut) {
        this.objectOut = objectOut;
    }

    public void run() {
        try {
            runInternal();
        } catch (IOException e) {
            // the test jvm has been terminated
            System.exit(0);
        } catch (InterruptedException e) {
        }
    }

    private void runInternal() throws IOException, InterruptedException {
        while (true) {
            // sychronizing with SocketCommandProcessor
            synchronized (objectOut) {
                objectOut.writeObject(PING_COMMAND);
            }
            Thread.sleep(1000);
        }
    }
}

/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

class SocketHeartbeat implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SocketHeartbeat.class);

    private final OutputStream socketOut;

    private volatile @Nullable Thread thread;
    private volatile boolean closed;

    SocketHeartbeat(int port) throws Exception {
        Socket socket = new Socket((String) null, port);
        socketOut = socket.getOutputStream();
    }

    void close() {
        closed = true;
        // interrupt to break out of sleep immediately
        checkNotNull(thread).interrupt();
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        while (!closed) {
            try {
                socketOut.write(0);
            } catch (IOException e) {
                // the test jvm has been terminated
                System.exit(0);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method above)
                logger.debug(e.getMessage(), e);
            }
        }
        try {
            socketOut.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }
}
